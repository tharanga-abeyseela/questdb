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

package com.questdb.ql.impl.sys;

import com.questdb.ql.parser.AbstractOptimiserTest;
import org.junit.BeforeClass;
import org.junit.Test;

public class $ColsRecordSourceTest extends AbstractOptimiserTest {
    @BeforeClass
    public static void setUp() throws Exception {
        compiler.execute(factory, "create table xyz (x int, y string, ts date), index(y buckets 30) timestamp(ts) partition by YEAR");
        compiler.execute(factory, "create table abc (a symbol, b boolean, d double), index(a buckets 70)");
        $ColsRecordSource.init();
    }

    @Test
    public void testCompiled() throws Exception {
        assertThat("abc\ta\tSYMBOL\tfalse\tnull\ttrue\t127\n" +
                        "abc\tb\tBOOLEAN\tfalse\tnull\tfalse\t0\n" +
                        "abc\td\tDOUBLE\tfalse\tnull\tfalse\t0\n" +
                        "xyz\tx\tINT\tfalse\tnull\tfalse\t0\n" +
                        "xyz\ty\tSTRING\tfalse\tnull\ttrue\t31\n" +
                        "xyz\tts\tDATE\ttrue\tYEAR\tfalse\t0\n",
                "$cols");
    }
}