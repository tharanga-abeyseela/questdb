/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2016 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.net.ha;

import com.questdb.JournalKey;
import com.questdb.JournalWriter;
import com.questdb.PartitionBy;
import com.questdb.ex.IncompatibleJournalException;
import com.questdb.ex.JournalException;
import com.questdb.ex.JournalNetworkException;
import com.questdb.factory.JournalWriterFactory;
import com.questdb.factory.configuration.JournalMetadata;
import com.questdb.factory.configuration.JournalStructure;
import com.questdb.log.Log;
import com.questdb.log.LogFactory;
import com.questdb.misc.Chars;
import com.questdb.misc.Files;
import com.questdb.misc.NamedDaemonThreadFactory;
import com.questdb.net.SecureSocketChannel;
import com.questdb.net.SslConfig;
import com.questdb.net.StatsCollectingReadableByteChannel;
import com.questdb.net.ha.auth.AuthConfigurationException;
import com.questdb.net.ha.auth.AuthFailureException;
import com.questdb.net.ha.auth.CredentialProvider;
import com.questdb.net.ha.comsumer.HugeBufferConsumer;
import com.questdb.net.ha.comsumer.JournalDeltaConsumer;
import com.questdb.net.ha.config.ClientConfig;
import com.questdb.net.ha.config.ServerNode;
import com.questdb.net.ha.model.Command;
import com.questdb.net.ha.model.IndexedJournal;
import com.questdb.net.ha.model.IndexedJournalKey;
import com.questdb.net.ha.producer.JournalClientStateProducer;
import com.questdb.net.ha.protocol.CommandConsumer;
import com.questdb.net.ha.protocol.CommandProducer;
import com.questdb.net.ha.protocol.Version;
import com.questdb.net.ha.protocol.commands.*;
import com.questdb.std.IntList;
import com.questdb.std.ObjList;
import com.questdb.store.TxListener;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class JournalClient {
    public static final int DISCONNECT_UNKNOWN = 1;
    public static final int DISCONNECT_CLIENT_HALT = 2;
    public static final int DISCONNECT_CLIENT_EXCEPTION = 3;
    public static final int DISCONNECT_BROKEN_CHANNEL = 4;
    public static final int DISCONNECT_CLIENT_ERROR = 5;
    public static final int DISCONNECT_INCOMPATIBLE_JOURNAL = 6;
    private static final AtomicInteger counter = new AtomicInteger(0);
    private static final Log LOG = LogFactory.getLog(JournalClient.class);
    private final static ThreadFactory CLIENT_THREAD_FACTORY = new NamedDaemonThreadFactory("journal-client", false);
    private final ObjList<JournalKey> remoteKeys = new ObjList<>();
    private final ObjList<JournalKey> localKeys = new ObjList<>();
    private final ObjList<TxListener> listeners = new ObjList<>();
    private final ObjList<JournalWriter> writers = new ObjList<>();
    private final ObjList<JournalDeltaConsumer> deltaConsumers = new ObjList<>();
    private final IntList statusSentList = new IntList();
    private final JournalWriterFactory factory;
    private final CommandProducer commandProducer = new CommandProducer();
    private final CommandConsumer commandConsumer = new CommandConsumer();
    private final SetKeyRequestProducer setKeyRequestProducer = new SetKeyRequestProducer();
    private final CharSequenceResponseConsumer charSequenceResponseConsumer = new CharSequenceResponseConsumer();
    private final JournalClientStateProducer journalClientStateProducer = new JournalClientStateProducer();
    private final IntResponseConsumer intResponseConsumer = new IntResponseConsumer();
    private final IntResponseProducer intResponseProducer = new IntResponseProducer();
    private final ByteArrayResponseProducer byteArrayResponseProducer = new ByteArrayResponseProducer();
    private final ClientConfig config;
    private final ExecutorService service;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CredentialProvider credentialProvider;
    private final DisconnectCallbackImpl disconnectCallback = new DisconnectCallbackImpl();
    private ByteChannel channel;
    private StatsCollectingReadableByteChannel statsChannel;
    private Future handlerFuture;

    public JournalClient(JournalWriterFactory factory) {
        this(factory, null);
    }

    public JournalClient(JournalWriterFactory factory, CredentialProvider credentialProvider) {
        this(new ClientConfig(), factory, credentialProvider);
    }

    public JournalClient(ClientConfig config, JournalWriterFactory factory) {
        this(config, factory, null);
    }

    public JournalClient(ClientConfig config, JournalWriterFactory factory, CredentialProvider credentialProvider) {
        this.config = config;
        this.factory = factory;
        this.service = Executors.newCachedThreadPool(CLIENT_THREAD_FACTORY);
        this.credentialProvider = credentialProvider;
    }

    public void halt() {
        if (running.compareAndSet(true, false)) {
            if (handlerFuture != null) {
                try {
                    handlerFuture.get();
                } catch (InterruptedException | ExecutionException e) {
                    LOG.error().$("Exception while waiting for client to shutdown gracefully").$(e).$();
                } finally {
                    handlerFuture = null;
                }
            }
            close0();
            free();
        } else {
            closeChannel();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setDisconnectCallback(DisconnectCallback callback) {
        this.disconnectCallback.next = callback;
    }

    public void start() throws JournalNetworkException {
        if (running.compareAndSet(false, true)) {
            handshake();
            handlerFuture = service.submit(new Handler());
        }
    }

    public <T> void subscribe(Class<T> clazz) {
        subscribe(clazz, (TxListener) null);
    }

    @SuppressWarnings("unused")
    public <T> void subscribe(Class<T> clazz, String location) {
        subscribe(clazz, location, (TxListener) null);
    }

    public <T> void subscribe(Class<T> clazz, String remote, String local) {
        subscribe(clazz, remote, local, null);
    }

    public <T> void subscribe(Class<T> clazz, String remote, String local, TxListener txListener) {
        subscribe(new JournalKey<>(clazz, remote), new JournalKey<>(clazz, local), txListener);
    }

    public <T> void subscribe(Class<T> clazz, String remote, String local, int recordHint) {
        subscribe(clazz, remote, local, recordHint, null);
    }

    public <T> void subscribe(Class<T> clazz, String remote, String local, int recordHint, TxListener txListener) {
        subscribe(new JournalKey<>(clazz, remote, PartitionBy.DEFAULT, recordHint), new JournalKey<>(clazz, local, PartitionBy.DEFAULT, recordHint), txListener);
    }

    public <T> void subscribe(JournalKey<T> remoteKey, JournalWriter<T> writer, TxListener txListener) {
        remoteKeys.add(remoteKey);
        localKeys.add(writer.getKey());
        listeners.add(txListener);
        set0(remoteKeys.size() - 1, writer, txListener);
    }

    public void subscribe(JournalKey remote, JournalKey local, TxListener txListener) {
        remoteKeys.add(remote);
        localKeys.add(local);
        listeners.add(txListener);
    }

    private void checkAck() throws JournalNetworkException {
        charSequenceResponseConsumer.read(channel);
        fail(Chars.equals("OK", charSequenceResponseConsumer.getValue()), charSequenceResponseConsumer.getValue().toString());
    }

    private void checkAuthAndSendCredential() throws JournalNetworkException {
        commandProducer.write(channel, Command.HANDSHAKE_COMPLETE);
        CharSequence cs = readString();
        if (Chars.equals("AUTH", cs)) {
            if (credentialProvider == null) {
                throw new AuthConfigurationException();
            }
            commandProducer.write(channel, Command.AUTHORIZATION);
            byteArrayResponseProducer.write(channel, getToken());
            CharSequence response = readString();
            if (!Chars.equals("OK", response)) {
                throw new AuthFailureException(response.toString());
            }
        } else if (!Chars.equals("OK", cs)) {
            fail(true, "Unknown server response");
        }
    }

    private void close0() {

        closeChannel();
        for (int i = 0, sz = writers.size(); i < sz; i++) {
            writers.getQuick(i).close();
        }

        writers.clear();
        statusSentList.clear();
        deltaConsumers.clear();
    }

    private void closeChannel() {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException e) {
                LOG.error().$("Error closing channel").$(e).$();
            } finally {
                channel = null;
            }
        }
    }

    private void fail(boolean condition, String message) throws JournalNetworkException {
        if (!condition) {
            throw new JournalNetworkException(message);
        }
    }

    private void free() {
        for (int i = 0, k = deltaConsumers.size(); i < k; i++) {
            deltaConsumers.getQuick(i).free();
        }
        commandConsumer.free();
        charSequenceResponseConsumer.free();
        intResponseConsumer.free();
    }

    private byte[] getToken() throws JournalNetworkException {
        try {
            return credentialProvider.createToken();
        } catch (Exception e) {
            halt();
            throw new JournalNetworkException(e);
        }
    }

    private void handshake() throws JournalNetworkException {
        openChannel(null);
        sendProtocolVersion();
        sendKeys();
        checkAuthAndSendCredential();
        sendState();
        counter.incrementAndGet();
    }

    private void openChannel(ServerNode node) throws JournalNetworkException {
        if (this.channel == null || node != null) {
            if (channel != null) {
                closeChannel();
            }
            SocketChannel channel = node == null ? config.openSocketChannel() : config.openSocketChannel(node);
            try {
                statsChannel = new StatsCollectingReadableByteChannel(channel.getRemoteAddress());
            } catch (IOException e) {
                throw new JournalNetworkException("Cannot get remote address", e);
            }

            SslConfig sslConfig = config.getSslConfig();
            if (sslConfig.isSecure()) {
                this.channel = new SecureSocketChannel(channel, sslConfig);
            } else {
                this.channel = channel;
            }
        }
    }

    private CharSequence readString() throws JournalNetworkException {
        charSequenceResponseConsumer.read(channel);
        return charSequenceResponseConsumer.getValue();
    }

    private void sendDisconnect() throws JournalNetworkException {
        commandProducer.write(channel, Command.CLIENT_DISCONNECT);
    }

    private void sendKeys() throws JournalNetworkException {
        for (int i = 0, sz = remoteKeys.size(); i < sz; i++) {
            commandProducer.write(channel, Command.SET_KEY_CMD);
            setKeyRequestProducer.write(channel, new IndexedJournalKey(i, remoteKeys.getQuick(i)));
            checkAck();


            JournalMetadata metadata;
            File file = Files.makeTempFile();
            try {
                try (HugeBufferConsumer h = new HugeBufferConsumer(file)) {
                    h.read(channel);
                    metadata = new JournalMetadata(h.getHb());
                } catch (JournalException e) {
                    throw new JournalNetworkException(e);
                }
            } finally {
                Files.delete(file);
            }


            try {
                if (writers.getQuiet(i) == null) {
                    set0(i, factory.writer(new JournalStructure(metadata).location(localKeys.getQuick(i).derivedLocation())), listeners.getQuick(i));
                }
            } catch (JournalException e) {
                throw new JournalNetworkException(e);
            }
        }
    }

    private void sendProtocolVersion() throws JournalNetworkException {
        commandProducer.write(channel, Command.PROTOCOL_VERSION);
        intResponseProducer.write(channel, Version.PROTOCOL_VERSION);
        checkAck();
    }

    private void sendReady() throws JournalNetworkException {
        commandProducer.write(channel, Command.CLIENT_READY_CMD);
        LOG.debug().$("Client ready: ").$(channel.toString()).$();
    }

    private void sendState() throws JournalNetworkException {
        for (int i = 0, sz = writers.size(); i < sz; i++) {
            if (statusSentList.get(i) == 0) {
                commandProducer.write(channel, Command.DELTA_REQUEST_CMD);
                journalClientStateProducer.write(channel, new IndexedJournal(i, writers.getQuick(i)));
                checkAck();
                statusSentList.setQuick(i, 1);
            }
        }
        sendReady();
    }

    private <T> void set0(int index, JournalWriter<T> writer, TxListener txListener) {
        statusSentList.extendAndSet(index, 0);
        deltaConsumers.extendAndSet(index, new JournalDeltaConsumer(writer.setCommitOnClose(false)));
        writers.extendAndSet(index, writer);
        if (txListener != null) {
            writer.setTxListener(txListener);
        }
    }

    /**
     * Configures client to subscribe given journal class when client is started
     * and connected. Journals of given class at default location are opened on
     * both client and server. Optionally provided listener will be called back
     * when client journal is committed. Listener is called synchronously with
     * client thread, so callback implementation must be fast.
     *
     * @param clazz      journal class on both client and server
     * @param txListener callback listener to get receive commit notifications.
     * @param <T>        generics to comply with Journal API.
     */
    private <T> void subscribe(Class<T> clazz, TxListener txListener) {
        subscribe(new JournalKey<>(clazz), new JournalKey<>(clazz), txListener);
    }

    private <T> void subscribe(Class<T> clazz, String location, TxListener txListener) {
        subscribe(new JournalKey<>(clazz, location), new JournalKey<>(clazz, location), txListener);
    }

    public interface DisconnectCallback {
        void onDisconnect(int disconnectReason);
    }

    private final class DisconnectCallbackImpl implements DisconnectCallback {
        private DisconnectCallback next;

        public void onDisconnect(int disconnectReason) {
            switch (disconnectReason) {
                case DISCONNECT_BROKEN_CHANNEL:
                case DISCONNECT_UNKNOWN:
                    int retryCount = config.getReconnectPolicy().getRetryCount();
                    int loginRetryCount = config.getReconnectPolicy().getLoginRetryCount();
                    boolean connected = false;
                    while (running.get() && !connected && retryCount-- > 0 && loginRetryCount > 0) {
                        try {
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(config.getReconnectPolicy().getSleepBetweenRetriesMillis()));
                            LOG.info().$("Retrying reconnect ... [").$(retryCount + 1).$(']').$();
                            close0();
                            handshake();
                            connected = true;
                        } catch (AuthConfigurationException | AuthFailureException e) {
                            loginRetryCount--;
                        } catch (JournalNetworkException e) {
                            LOG.info().$("Error during disconnect").$(e).$();
                        }
                    }

                    if (connected) {
                        handlerFuture = service.submit(new Handler());
                    } else {
                        disconnect(disconnectReason);
                    }
                    break;
                default:
                    disconnect(disconnectReason);
                    break;
            }
        }

        private void disconnect(int disconnectReason) {
            LOG.info().$("Client disconnecting").$();
            counter.decrementAndGet();
            running.set(false);
            // set future to null to prevent deadlock
            handlerFuture = null;
            service.shutdown();

            if (next != null) {
                next.onDisconnect(disconnectReason);
            }
        }
    }

    private final class Handler implements Runnable {
        @Override
        public void run() {
            int disconnectReason = DISCONNECT_UNKNOWN;
            try {
                OUT:
                while (true) {
                    assert channel != null;
                    commandConsumer.read(channel);
                    switch (commandConsumer.getCommand()) {
                        case Command.JOURNAL_DELTA_CMD:
                            statsChannel.setDelegate(channel);
                            int index = intResponseConsumer.getValue(statsChannel);
                            deltaConsumers.getQuick(index).read(statsChannel);
                            statusSentList.set(index, 0);
                            statsChannel.logStats();
                            break;
                        case Command.SERVER_READY_CMD:
                            if (isRunning()) {
                                sendState();
                            } else {
                                sendDisconnect();
                                disconnectReason = DISCONNECT_CLIENT_HALT;
                                break OUT;
                            }
                            break;
                        case Command.SERVER_HEARTBEAT:
                            if (isRunning()) {
                                sendReady();
                            } else {
                                sendDisconnect();
                                disconnectReason = DISCONNECT_CLIENT_HALT;
                                break OUT;
                            }
                            break;
                        case Command.SERVER_SHUTDOWN:
                            disconnectReason = DISCONNECT_BROKEN_CHANNEL;
                            break OUT;
                        default:
                            LOG.info().$("Unknown command: ").$(commandConsumer.getCommand()).$();
                            break;
                    }
                }
            } catch (IncompatibleJournalException e) {
                LOG.error().$(e.getMessage()).$();
                disconnectReason = DISCONNECT_INCOMPATIBLE_JOURNAL;
            } catch (JournalNetworkException e) {
                LOG.error().$("Network error. Server died?").$();
                LOG.debug().$("Network error details: ").$(e).$();
                disconnectReason = DISCONNECT_BROKEN_CHANNEL;
            } catch (Error e) {
                LOG.error().$("Unhandled exception in client").$(e).$();
                disconnectReason = DISCONNECT_CLIENT_ERROR;
                throw e;
            } catch (Throwable e) {
                LOG.error().$("Unhandled exception in client").$(e).$();
                disconnectReason = DISCONNECT_CLIENT_EXCEPTION;
            } finally {
                disconnectCallback.onDisconnect(disconnectReason);
            }
        }
    }
}
