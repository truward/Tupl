/*
 *  Copyright 2015 Cojen.org
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

package org.cojen.tupl.ext;

import java.io.IOException;

import java.util.concurrent.locks.Lock;

import org.cojen.tupl.Database;
import org.cojen.tupl.DatabaseConfig;
import org.cojen.tupl.Transaction;

/**
 * Handler for custom transactional operations. Undo operations are applied to roll back
 * transactions, and redo operations are applied by recovery and replication.
 *
 * @author Brian S O'Neill
 * @see DatabaseConfig#customTransactionHandler
 */
public interface TransactionHandler {
    /**
     * Non-transactionally apply an idempotent redo operation.
     *
     * @param txn transaction the operation applies to; can be modified
     * @param message message originally provided to {@link Transaction#customRedo}
     */
    void redo(Database db, Transaction txn, byte[] message)
        throws IOException;

    /**
     * Non-transactionally apply an idempotent redo operation. Locking an index key allows
     * replication to operate concurrently.
     *
     * @param txn transaction the operation applies to; can be modified
     * @param message message originally provided to {@link Transaction#customRedo}
     * @param indexId non-zero index for lock acquisition
     * @param key non-null key which has been locked exclusively
     */
    void redo(Database db, Transaction txn, byte[] message, long indexId, byte[] key)
        throws IOException;

    /**
     * Non-transactionally apply an idempotent undo operation.
     *
     * @param message message originally provided to {@link Transaction#customUndo}
     */
    void undo(Database db, byte[] message) throws IOException;

    /**
     * Called once when the database is opened, providing a shared lock which is later acquired
     * exclusively by checkpoints. Implementation is permitted (but not required) to use this
     * lock for suspending transactional changes when {@link #checkpointStart checkpointStart}
     * is called. Simply hold the lock when making transactional changes, and then no additional
     * locking is required when {@code checkpointStart} is called.
     */
    default void setCheckpointLock(Database db, Lock lock) {
    }

    /**
     * Called to durably write all changes. Implementation must suspend all transactional
     * changes (although transactions can be left open), and then initiate a concurrent
     * checkpoint. The checkpoint must capture a snapshot of all changes at the point it was
     * initiated, and it does not need to contain any changes afterwards. A recovery sequence
     * rolls back to the last checkpoint, and then it applies all redo operations to catch
     * up. Undo operations are applied only for transactions which did not commit.
     *
     * @return optional object to be passed to {@link #checkpointFinish checkpointFinish}
     */
    default Object checkpointStart(Database db) throws IOException {
        return null;
    }

    /**
     * Wait for a concurrent checkpoint to finish.
     *
     * @param obj optional object passed from {@link #checkpointStart checkpointStart}
     */
    default void checkpointFinish(Database db, Object obj) throws IOException {
    }
}
