/*
 *  Copyright 2012-2015 Cojen.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.tupl;

import java.util.*;

import org.junit.*;
import static org.junit.Assert.*;

import static org.cojen.tupl.TestUtils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ExtraLargeValueTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(ExtraLargeValueTest.class.getName());
    }

    @Before
    public void createTempDb() throws Exception {
        DatabaseConfig config = new DatabaseConfig();
        config.directPageAccess(false);
        config.durabilityMode(DurabilityMode.NO_FLUSH);
        // Use smaller page size so that more inode levels are required without
        // requiring super large arrays.
        config.pageSize(512);
        mDb = newTempDatabase(config);
    }

    @After
    public void teardown() throws Exception {
        deleteTempDatabases();
        mDb = null;
    }

    protected Database mDb;

    @Test
    public void testStoreBasic() throws Exception {
        Random rnd = new Random(82348976232L);
        int[] sizes = {20000, 30000, 43519, 43520, 43521, 50000, 100000, 1000000, 4000000};

        for (int size : sizes) {
            testStoreBasic(rnd, size, null);
            testStoreBasic(rnd, size, Transaction.BOGUS);
            testStoreBasic(rnd, size, mDb.newTransaction());
        }
    }

    private void testStoreBasic(Random rnd, int size, Transaction txn) throws Exception {
        Index ix = mDb.openIndex("test");

        byte[] key = "hello".getBytes();
        byte[] key2 = "howdy".getBytes();

        byte[] value = randomStr(rnd, size);
        byte[] value2 = randomStr(rnd, size);

        ix.store(txn, key, value);
        fastAssertArrayEquals(value, ix.load(txn, key));

        ix.store(txn, key, value2);
        fastAssertArrayEquals(value2, ix.load(txn, key));

        assertNull(ix.load(txn, key2));

        ix.store(txn, key, null);
        assertNull(ix.load(txn, key));

        if (txn != null && txn != Transaction.BOGUS) {
            ix.store(txn, key, value);
            txn.commit();
            fastAssertArrayEquals(value, ix.load(txn, key));

            ix.store(txn, key, value2);
            txn.commit();
            fastAssertArrayEquals(value2, ix.load(txn, key));

            ix.store(txn, key, value);
            txn.exit();
            fastAssertArrayEquals(value2, ix.load(txn, key));
            txn.exit();
        }
    }
}
