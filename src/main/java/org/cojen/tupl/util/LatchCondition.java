/*
 *  Copyright 2011-2015 Cojen.org
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

package org.cojen.tupl.util;

import java.util.concurrent.locks.LockSupport;

/**
 * Manages a queue of waiting threads, associated with a {@link Latch} instance. Unlike the
 * built-in Java Condition class, spurious wakeup does not occur when waiting.
 *
 * @author Brian S O'Neill
 */
public final class LatchCondition {
    private static final ThreadLocal<Node> cLocalNode = ThreadLocal.withInitial(Node::new);

    Node mHead;
    Node mTail;

    /**
     * Returns true if no waiters are enqueued. Caller must hold shared or exclusive latch.
     */
    public boolean isEmpty() {
        return mHead == null;
    }

    /**
     * Blocks the current thread until a signal is received. Exclusive latch must be acquired
     * by caller, which is released and then re-acquired by this method.
     *
     * @param latch latch being used by this condition
     * @param nanosTimeout relative nanosecond time to wait; infinite if &lt;0
     * @param nanosEnd absolute nanosecond time to wait until; used only with &gt;0 timeout
     * @return -1 if interrupted, 0 if timed out, 1 if signaled
     */
    public int await(Latch latch, long nanosTimeout, long nanosEnd) {
        Node node = cLocalNode.get();
        node.mWaitState = 1;
        return await(latch, node, nanosTimeout, nanosEnd);
    }

    /**
     * Blocks the current thread until a signal is received. Exclusive latch must be acquired
     * by caller, which is released and then re-acquired by this method. A shared waiter
     * intends to access a resource with shared access, and it can be signaled specially. After
     * waiting, caller is responsible for signaling the next shared waiter.
     *
     * @param latch latch being used by this condition
     * @param nanosTimeout relative nanosecond time to wait; infinite if &lt;0
     * @param nanosEnd absolute nanosecond time to wait until; used only with &gt;0 timeout
     * @return -1 if interrupted, 0 if timed out, 1 if signaled
     */
    public int awaitShared(Latch latch, long nanosTimeout, long nanosEnd) {
        Node node = cLocalNode.get();
        node.mWaitState = 2;
        return await(latch, node, nanosTimeout, nanosEnd);
    }

    private int await(Latch latch, Node node, long nanosTimeout, long nanosEnd) {
        Node tail = mTail;
        if (tail == null) {
            mHead = node;
        } else {
            tail.mNext = node;
            node.mPrev = tail;
        }
        mTail = node;

        if (nanosTimeout < 0) {
            while (true) {
                latch.releaseExclusive();
                LockSupport.park(this);
                latch.acquireExclusive();
                int result = node.resumed(this);
                if (result != 0) {
                    return result;
                }
            }
        } else {
            while (true) {
                latch.releaseExclusive();
                LockSupport.parkNanos(this, nanosTimeout);
                latch.acquireExclusive();
                int result = node.resumed(this);
                if (result != 0) {
                    return result;
                }
                if (nanosTimeout == 0 || (nanosTimeout = nanosEnd - System.nanoTime()) <= 0) {
                    node.remove(this);
                    return 0;
                }
            }
        }
    }

    /**
     * Signals the first waiter, of any type. Caller must hold shared or exclusive latch.
     */
    public void signal() {
        Node head = mHead;
        if (head != null) {
            head.signal();
        }
    }

    /**
     * Signals the first waiter, of any type. Caller must hold shared or exclusive latch.
     *
     * @return false if no waiters of any type exist
     */
    public boolean signalNext() {
        Node head = mHead;
        if (head == null) {
            return false;
        }
        head.signal();
        return true;
    }

    /**
     * Signals all waiters, of any type. Caller must hold shared or exclusive latch.
     */
    public void signalAll() {
        Node node = mHead;
        while (node != null) {
            node.signal();
            node = node.mNext;
        }
    }

    /**
     * Signals the first waiter, but only if it's a shared waiter. Caller must hold shared or
     * exclusive latch.
     */
    public void signalShared() {
        Node head = mHead;
        if (head != null && head.mWaitState == 2) {
            head.signal();
        }
    }

    /**
     * Signals the first waiter, but only if it's a shared waiter. Caller must hold shared or
     * exclusive latch.
     *
     * @return false if no waiters of any type exist
     */
    public boolean signalNextShared() {
        Node head = mHead;
        if (head == null) {
            return false;
        }
        if (head.mWaitState == 2) {
            head.signal();
        }
        return true;
    }

    /**
     * Clears out all waiting threads and interrupts them. Caller must hold exclusive latch.
     */
    public void clear() {
        Node node = mHead;
        while (node != null) {
            if (node.mWaitState != 0) {
                node.mOwner.interrupt();
            }
            node.mPrev = null;
            Node next = node.mNext;
            node.mNext = null;
            node = next;
        }
        mHead = null;
        mTail = null;
    }

    static class Node {
        final Thread mOwner;

        // 0: signaled or not waiting, 1: waiting for signal, 2: waiting for shared signal
        int mWaitState;

        Node mPrev;
        Node mNext;

        Node() {
            mOwner = Thread.currentThread();
        }

        /**
         * Called by thread which was parked after it resumes. Caller is
         * responsible for removing node from queue if return value is 0.
         *
         * @return -1 if interrupted, 0 if not signaled, 1 if signaled
         */
        final int resumed(LatchCondition queue) {
            if (mWaitState == 0) {
                remove(queue);
                return 1;
            }
            if (mOwner.isInterrupted()) {
                Thread.interrupted();
                remove(queue);
                return -1;
            }
            return 0;
        }

        final void signal() {
            mWaitState = 0;
            LockSupport.unpark(mOwner);
        }

        final void remove(LatchCondition queue) {
            Node prev = mPrev;
            Node next = mNext;
            if (prev == null) {
                if ((queue.mHead = next) == null) {
                    queue.mTail = null;
                } else {
                    next.mPrev = null;
                }
            } else {
                if ((prev.mNext = next) == null) {
                    queue.mTail = prev;
                } else {
                    next.mPrev = prev;
                }
                mPrev = null;
            }
            mNext = null;
        }
    }
}
