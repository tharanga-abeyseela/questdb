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

package com.questdb.ql;

import com.questdb.JournalWriter;
import com.questdb.ex.JournalException;
import com.questdb.ex.NumericException;
import com.questdb.misc.Dates;
import com.questdb.model.Quote;
import com.questdb.ql.impl.JournalPartitionSource;
import com.questdb.ql.impl.JournalRecordSource;
import com.questdb.ql.impl.latest.HeapMergingRowSource;
import com.questdb.ql.impl.latest.KvIndexSymLookupRowSource;
import com.questdb.ql.impl.latest.MergingRowSource;
import com.questdb.test.tools.AbstractTest;
import com.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Test;

public class MergingRowSourceTest extends AbstractTest {
    @Test
    public void testHeapMerge() throws JournalException, NumericException {
        JournalWriter<Quote> w = factory.writer(Quote.class);
        TestUtils.generateQuoteData(w, 100000, Dates.parseDateTime("2014-02-11T00:00:00.000Z"), 10);

        RowSource srcA = new KvIndexSymLookupRowSource("sym", "BP.L", true);
        RowSource srcB = new KvIndexSymLookupRowSource("sym", "WTB.L", true);

        RecordSource rs = new JournalRecordSource(new JournalPartitionSource(w.getMetadata(), true), new HeapMergingRowSource(srcA, srcB));

        long last = 0;
        RecordCursor c = rs.prepareCursor(factory);
        int ts = rs.getMetadata().getColumnIndex("timestamp");
        while (c.hasNext()) {
            long r = c.next().getDate(ts);
            Assert.assertTrue(r > last);
            last = r;
        }
    }

    @Test
    public void testMerge() throws JournalException, NumericException {
        JournalWriter<Quote> w = factory.writer(Quote.class);
        TestUtils.generateQuoteData(w, 100000, Dates.parseDateTime("2014-02-11T00:00:00.000Z"), 10);

        RowSource srcA = new KvIndexSymLookupRowSource("sym", "BP.L", true);
        RowSource srcB = new KvIndexSymLookupRowSource("sym", "WTB.L", true);

        RecordSource rs = new JournalRecordSource(new JournalPartitionSource(w.getMetadata(), true), new MergingRowSource(srcA, srcB));

        long last = 0;
        RecordCursor c = rs.prepareCursor(factory);
        int ts = rs.getMetadata().getColumnIndex("timestamp");
        while (c.hasNext()) {
            long r = c.next().getDate(ts);
            Assert.assertTrue(r > last);
            last = r;
        }
    }

}
