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

package com.questdb.io.parser.listener;

import com.questdb.JournalEntryWriter;
import com.questdb.JournalKey;
import com.questdb.JournalWriter;
import com.questdb.ex.*;
import com.questdb.factory.JournalWriterFactory;
import com.questdb.factory.configuration.ColumnMetadata;
import com.questdb.factory.configuration.JournalMetadata;
import com.questdb.factory.configuration.JournalStructure;
import com.questdb.io.ImportedColumnMetadata;
import com.questdb.io.ImportedColumnType;
import com.questdb.log.Log;
import com.questdb.log.LogFactory;
import com.questdb.misc.Chars;
import com.questdb.misc.Dates;
import com.questdb.misc.Misc;
import com.questdb.misc.Numbers;
import com.questdb.std.DirectByteCharSequence;
import com.questdb.std.LongList;
import com.questdb.std.Mutable;
import com.questdb.std.ObjList;
import com.questdb.store.ColumnType;

import java.io.Closeable;

import static com.questdb.factory.configuration.JournalConfiguration.DOES_NOT_EXIST;
import static com.questdb.factory.configuration.JournalConfiguration.EXISTS;

public class JournalImportListener implements InputAnalysisListener, Closeable, Mutable {
    private static final Log LOG = LogFactory.getLog(JournalImportListener.class);
    private final JournalWriterFactory factory;
    private final LongList errors = new LongList();
    private String location;
    private ObjList<ImportedColumnMetadata> metadata;
    private JournalWriter writer;
    private long _size;
    private boolean overwrite;

    public JournalImportListener(JournalWriterFactory factory) {
        this.factory = factory;
    }

    @Override
    public void clear() {
        writer = Misc.free(writer);
        errors.clear();
        _size = 0;
    }

    @Override
    public void close() {
        clear();
    }

    public void commit() {
        if (writer != null) {
            try {
                writer.commit();
            } catch (JournalException e) {
                throw new JournalRuntimeException(e);
            }
        }
    }

    public LongList getErrors() {
        return errors;
    }

    public long getImportedRowCount() {
        try {
            return writer.size() - _size;
        } catch (JournalException e) {
            throw new JournalRuntimeException(e);
        }
    }

    public JournalMetadata getMetadata() {
        return writer.getMetadata();
    }

    public JournalImportListener of(String location, boolean overwrite) {
        this.location = location;
        this.overwrite = overwrite;
        return this;
    }

    @Override
    public void onError(int line) {

    }

    @Override
    public void onFieldCount(int count) {
    }

    @Override
    public void onFields(int line, ObjList<DirectByteCharSequence> values, int hi) {
        boolean append = true;
        try {
            JournalEntryWriter w = writer.entryWriter();
            for (int i = 0; i < hi; i++) {
                if (values.getQuick(i).length() == 0) {
                    continue;
                }
                try {
                    switch (metadata.getQuick(i).importedColumnType) {
                        case ImportedColumnType.STRING:
                            w.putStr(i, values.getQuick(i));
                            break;
                        case ImportedColumnType.DOUBLE:
                            w.putDouble(i, Numbers.parseDouble(values.getQuick(i)));
                            break;
                        case ImportedColumnType.INT:
                            w.putInt(i, Numbers.parseInt(values.getQuick(i)));
                            break;
                        case ImportedColumnType.FLOAT:
                            w.putFloat(i, Numbers.parseFloat(values.getQuick(i)));
                            break;
                        case ImportedColumnType.DATE_ISO:
                            w.putDate(i, Dates.parseDateTime(values.getQuick(i)));
                            break;
                        case ImportedColumnType.DATE_1:
                            w.putDate(i, Dates.parseDateTimeFmt1(values.getQuick(i)));
                            break;
                        case ImportedColumnType.DATE_2:
                            w.putDate(i, Dates.parseDateTimeFmt2(values.getQuick(i)));
                            break;
                        case ImportedColumnType.DATE_3:
                            w.putDate(i, Dates.parseDateTimeFmt3(values.getQuick(i)));
                            break;
                        case ImportedColumnType.SYMBOL:
                            w.putSym(i, values.getQuick(i));
                            break;
                        case ImportedColumnType.LONG:
                            w.putLong(i, Numbers.parseLong(values.getQuick(i)));
                            break;
                        case ImportedColumnType.BOOLEAN:
                            w.putBool(i, Chars.equalsIgnoreCase(values.getQuick(i), "true"));
                            break;
                        default:
                            break;
                    }
                } catch (Exception e) {
                    errors.increment(i);
                    LOG.debug().$("Error at (").$(line).$(',').$(i).$(") as ").$(metadata.getQuick(i).importedColumnType).$(": ").$(e.getMessage()).$();
                    append = false;
                    break;
                }
            }
            if (append) {
                w.append();
            }
        } catch (JournalException e) {
            throw new JournalRuntimeException(e);
        }
    }

    @Override
    public void onHeader(ObjList<DirectByteCharSequence> values, int hi) {
    }

    @Override
    public void onLineCount(int count) {
    }

    @Override
    public void onMetadata(ObjList<ImportedColumnMetadata> metadata) {
        if (writer == null) {
            try {
                switch (factory.getConfiguration().exists(location)) {
                    case DOES_NOT_EXIST:
                        this.metadata = metadata;
                        writer = factory.bulkWriter(createStructure());
                        break;
                    case EXISTS:
                        this.metadata = metadata;
                        if (overwrite) {
                            factory.getConfiguration().delete(location);
                            writer = factory.bulkWriter(createStructure());
                        } else {
                            writer = mapColumnsAndOpenWriter();
                        }
                        break;
                    default:
                        throw ImportNameException.INSTANCE;
                }
                _size = writer.size();
                errors.seed(writer.getMetadata().getColumnCount(), 0);
            } catch (JournalException e) {
                throw new JournalRuntimeException(e);
            }
        }
    }

    private JournalStructure createStructure() {
        ObjList<ColumnMetadata> m = new ObjList<>(metadata.size());
        for (int i = 0, n = metadata.size(); i < n; i++) {
            ColumnMetadata cm = new ColumnMetadata();
            ImportedColumnMetadata im = metadata.getQuick(i);
            cm.name = im.name.toString();
            cm.type = ImportedColumnType.columnTypeOf(im.importedColumnType);

            switch (cm.type) {
                case ColumnType.STRING:
                    cm.size = cm.avgSize + 4;
                    break;
                default:
                    cm.size = ColumnType.sizeOf(cm.type);
                    break;
            }
            m.add(cm);
        }
        return new JournalStructure(location, m);
    }

    @SuppressWarnings("unchecked")
    private JournalWriter mapColumnsAndOpenWriter() throws JournalException {

        JournalMetadata<Object> jm = factory.getConfiguration().createMetadata(new JournalKey<>(location));

        // now, compare column count.
        // Cannot continue if different

        if (jm.getColumnCount() != metadata.size()) {
            throw ImportColumnCountException.INSTANCE;
        }


        // Go over "discovered" metadata and really adjust it
        // to what journal can actually take
        // one useful thing discovered type can bring is information
        // about date format. The rest of it we will pretty much overwrite

        for (int i = 0, n = metadata.size(); i < n; i++) {
            ImportedColumnMetadata im = metadata.getQuick(i);
            ColumnMetadata cm = jm.getColumnQuick(i);
            im.importedColumnType = toImportedType(cm.type, im.importedColumnType);
        }

        return factory.bulkWriter(jm);
    }

    private int toImportedType(int columnType, int importedColumnType) {

        switch (columnType) {
            case ColumnType.BINARY:
                throw ImportBinaryException.INSTANCE;
            case ColumnType.DATE:
                switch (importedColumnType) {
                    case ImportedColumnType.DATE_1:
                    case ImportedColumnType.DATE_2:
                    case ImportedColumnType.DATE_3:
                    case ImportedColumnType.DATE_ISO:
                        return importedColumnType;
                    default:
                        return ImportedColumnType.DATE_ISO;
                }
            case ColumnType.STRING:
                return ImportedColumnType.STRING;
            case ColumnType.BOOLEAN:
                return ImportedColumnType.BOOLEAN;
            case ColumnType.BYTE:
                return ImportedColumnType.BYTE;
            case ColumnType.DOUBLE:
                return ImportedColumnType.DOUBLE;
            case ColumnType.FLOAT:
                return ImportedColumnType.FLOAT;
            case ColumnType.INT:
                return ImportedColumnType.INT;
            case ColumnType.LONG:
                return ImportedColumnType.LONG;
            case ColumnType.SHORT:
                return ImportedColumnType.SHORT;
            case ColumnType.SYMBOL:
                return ImportedColumnType.SYMBOL;
            default:
                return importedColumnType;

        }
    }
}
