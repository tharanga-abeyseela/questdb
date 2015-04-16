/*
 * Copyright (c) 2014-2015. Vlad Ilyushchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nfsdb.ql.ops;

import com.nfsdb.collections.mmap.MapValues;
import com.nfsdb.factory.configuration.ColumnMetadata;
import com.nfsdb.ql.Record;

public class SumIntAggregationFunction extends AbstractSingleColumnAggregatorFunction {
    public SumIntAggregationFunction(ColumnMetadata meta) {
        super(meta);
    }

    @Override
    public void calculate(Record rec, MapValues values) {
        if (values.isNew()) {
            values.putInt(valueIndex, rec.getInt(recordIndex));
        } else {
            values.putInt(valueIndex, values.getInt(valueIndex) + rec.getInt(recordIndex));
        }
    }
}