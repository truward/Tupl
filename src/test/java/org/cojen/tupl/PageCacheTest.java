/*
 *  Copyright 2014-2015 Cojen.org
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

import java.util.Random;

import org.junit.*;
import static org.junit.Assert.*;

import static org.cojen.tupl.PageOps.*;
import static org.cojen.tupl.TestUtils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class PageCacheTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(PageCacheTest.class.getName());
    }

    @Test
    public void fill() {
        fill(false);
    }

    @Test
    public void fillScrambled() {
        fill(true);
    }

    private void fill(boolean scramble) {
        PageCache cache = new BasicPageCache(1_000_000, 4096);
        assertTrue(cache.capacity() > 0);
        assertTrue(cache.capacity() <= 1_000_000);
        assertTrue(cache.maxEntryCount() > 0);
        assertTrue(cache.maxEntryCount() < 1_000_000);

        final long seed = System.nanoTime();
        final /*P*/ byte[] page = p_alloc(4096);
        try {
            Random rnd = new Random(seed);

            for (int i = 0; i < cache.maxEntryCount(); i++) {
                long pageId = i;
                if (scramble) {
                    pageId = Utils.scramble(pageId);
                }
                rndFill(page, 4096, rnd);
                cache.add(pageId, page, 0, true);
            }

            final /*P*/ byte[] actual = p_alloc(4096);
            try {
                rnd = new Random(seed);

                for (int i = 0; i < cache.maxEntryCount(); i++) {
                    long pageId = i;
                    if (scramble) {
                        pageId = Utils.scramble(pageId);
                    }
                    rndFill(page, 4096, rnd);
                    assertTrue(cache.copy(pageId, 0, actual, 0));
                    assertPageEquals(page, 4096, actual);
                }

                rnd = new Random(seed);

                for (int i = 0; i < cache.maxEntryCount(); i++) {
                    long pageId = i;
                    if (scramble) {
                        pageId = Utils.scramble(pageId);
                    }
                    rndFill(page, 4096, rnd);
                    assertTrue(cache.remove(pageId, actual, 0, 4096));
                    assertPageEquals(page, 4096, actual);
                }

                assertFalse(cache.remove(1, actual, 0, 4096));
            } finally {
                p_delete(actual);
            }
        } finally {
            p_delete(page);
        }

        cache.close();
    }

    @Test
    public void evict() {
        evict(false);
    }

    @Test
    public void evictScrambled() {
        evict(true);
    }

    private void evict(boolean scramble) {
        PageCache cache = new BasicPageCache(100_000, 100);

        final long seed = System.nanoTime();
        final /*P*/ byte[] page = p_alloc(100);
        try {
            Random rnd = new Random(seed);

            for (int i = 0; i < cache.maxEntryCount() * 2; i++) {
                long pageId = i + 1;
                if (scramble) {
                    pageId = Utils.scramble(pageId);
                }
                rndFill(page, 100, rnd);
                cache.add(pageId, page, 0, true);
            }

            final /*P*/ byte[] actual = p_alloc(100);
            try {
                rnd = new Random(seed);

                for (int i = 0; i < cache.maxEntryCount(); i++) {
                    rndFill(page, 100, rnd);
                }

                for (long i = cache.maxEntryCount(); i < cache.maxEntryCount() * 2; i++) {
                    long pageId = i + 1;
                    if (scramble) {
                        pageId = Utils.scramble(pageId);
                    }
                    rndFill(page, 100, rnd);
                    assertTrue(cache.remove(pageId, actual, 0, 100));
                    assertPageEquals(page, 100, actual);
                }

                assertFalse(cache.remove(1, actual, 0, 100));
            } finally {
                p_delete(actual);
            }
        } finally {
            p_delete(page);
        }

        cache.close();
    }

    @Test
    public void closed() {
        PageCache cache = new BasicPageCache(256, 4);
        cache.close();

        final /*P*/ byte[] p1 = p_alloc(4);
        try {
            final /*P*/ byte[] p2 = p_alloc(4);
            try {
                cache.add(1, p1, 0, true);
                assertFalse(cache.remove(1, p2, 0, 4));
            } finally {
                p_delete(p2);
            }
        } finally {
            p_delete(p1);
        }

        cache.close();
    }

    @Test
    public void partitions() {
        PageCache cache = new PartitionedPageCache(1_000_000, 4096, 16);
        assertTrue(cache.capacity() > 0);
        assertTrue(cache.capacity() <= 1_000_000);
        assertTrue(cache.maxEntryCount() > 0);
        assertTrue(cache.maxEntryCount() < 1_000_000);

        final long seed = System.nanoTime();
        final /*P*/ byte[] page = p_alloc(4096);
        try {
            Random rnd = new Random(seed);

            for (int i = 0; i < cache.maxEntryCount(); i++) {
                long pageId = i + 1;
                rndFill(page, 4096, rnd);
                cache.add(pageId, page, 0, true);
            }

            final /*P*/ byte[] actual = p_alloc(4096);
            try {
                rnd = new Random(seed);

                // Might have evicted some, due to uneven distribution.
                int removedCount = 0;

                for (int i = 0; i < cache.maxEntryCount(); i++) {
                    long pageId = i + 1;
                    rndFill(page, 4096, rnd);
                    boolean removed = cache.remove(pageId, actual, 0, 4096);
                    if (removed) {
                        assertPageEquals(page, 4096, actual);
                        removedCount++;
                    }
                }

                assertTrue(removedCount >= cache.maxEntryCount() * 0.9);
            } finally {
                p_delete(actual);
            }
        } finally {
            p_delete(page);
        }

        cache.close();
    }

    static void rndFill(/*P*/ byte[] page, int len, Random rnd) {
        for (int i=0; i<len; i++) {
            p_bytePut(page, i, rnd.nextInt());
        }
    }

    static void assertPageEquals(/*P*/ byte[] a, int len, /*P*/ byte[] b) {
        for (int i=0; i<len; i++) {
            assertEquals(p_byteGet(a, i), p_byteGet(b, i));
        }
    }
}
