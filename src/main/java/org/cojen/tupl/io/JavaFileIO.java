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

package org.cojen.tupl.io;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.RandomAccessFile;

import java.nio.ByteBuffer;

import java.nio.channels.FileChannel;

import java.util.EnumSet;

import static org.cojen.tupl.io.Utils.*;

/**
 * Basic FileIO implementation which uses the Java RandomAccessFile class,
 * unless a more suitable implementation is available.
 *
 * @author Brian S O'Neill
 */
final class JavaFileIO extends AbstractFileIO {
    private final File mFile;
    private final String mMode;

    // Access these fields while synchronized on mFilePool.
    private final FileAccess[] mFilePool;
    private int mFilePoolTop;

    JavaFileIO(File file, EnumSet<OpenOption> options, int openFileCount) throws IOException {
        this(file, options, openFileCount, true);
    }

    JavaFileIO(File file, EnumSet<OpenOption> options, int openFileCount, boolean allowMap)
        throws IOException
    {
        super(options);

        if (options.contains(OpenOption.NON_DURABLE)) {
            throw new UnsupportedOperationException("Unsupported options: " + options);
        }

        mFile = file;

        String mode;
        if (isReadOnly()) {
            mode = "r";
        } else {
            if (!options.contains(OpenOption.CREATE) && !file.exists()) {
                throw new FileNotFoundException(file.getPath());
            }
            if (options.contains(OpenOption.SYNC_IO)) {
                mode = "rwd";
            } else {
                mode = "rw";
            }
        }

        mMode = mode;

        if (openFileCount < 1) {
            openFileCount = 1;
        }

        mFilePool = new FileAccess[openFileCount];

        try {
            synchronized (mFilePool) {
                for (int i=0; i<openFileCount; i++) {
                    mFilePool[i] = openRaf(file, mode);
                }
            }
        } catch (Throwable e) {
            throw closeOnFailure(this, e);
        }

        if (allowMap && options.contains(OpenOption.MAPPED)) {
            map();
        }

        if (options.contains(OpenOption.CREATE)) {
            dirSync(file);
        }
    }

    @Override
    protected long doLength() throws IOException {
        RandomAccessFile file = accessFile();
        try {
            return file.length();
        } finally {
            yieldFile(file);
        }
    }

    @Override
    protected void doSetLength(long length) throws IOException {
        RandomAccessFile file = accessFile();
        try {
            file.setLength(length);
        } finally {
            yieldFile(file);
        }
    }

    @Override
    protected void doRead(long pos, byte[] buf, int offset, int length) throws IOException {
        try {
            RandomAccessFile file = accessFile();
            try {
                file.seek(pos);
                file.readFully(buf, offset, length);
            } finally {
                yieldFile(file);
            }
        } catch (EOFException e) {
            throw new EOFException("Attempt to read past end of file: " + pos);
        }
    }

    @Override
    protected void doRead(long pos, ByteBuffer bb) throws IOException {
        RandomAccessFile file = accessFile();
        try {
            FileChannel channel = file.getChannel();
            while (bb.hasRemaining()) {
                int amt = channel.read(bb, pos);
                if (amt < 0) {
                    throw new EOFException("Attempt to read past end of file: " + pos);
                }
                pos += amt;
            }
        } finally {
            yieldFile(file);
        }
    }

    @Override
    protected void doRead(long pos, long ptr, int length) throws IOException {
        doRead(pos, DirectAccess.ref(ptr, length));
    }

    @Override
    protected void doWrite(long pos, byte[] buf, int offset, int length) throws IOException {
        RandomAccessFile file = accessFile();
        try {
            file.seek(pos);
            file.write(buf, offset, length);
        } finally {
            yieldFile(file);
        }
    }

    @Override
    protected void doWrite(long pos, ByteBuffer bb) throws IOException {
        RandomAccessFile file = accessFile();
        try {
            FileChannel channel = file.getChannel();
            while (bb.hasRemaining()) {
                pos += channel.write(bb, pos);
            }
        } finally {
            yieldFile(file);
        }
    }

    @Override
    protected void doWrite(long pos, long ptr, int length) throws IOException {
        doWrite(pos, DirectAccess.ref(ptr, length));
    }

    @Override
    protected Mapping openMapping(boolean readOnly, long pos, int size) throws IOException {
        return Mapping.open(mFile, readOnly, pos, size);
    }

    @Override
    protected void reopen() throws IOException {
        IOException ex = null;

        for (int i=0; i<mFilePool.length; i++) {
            try {
                accessFile().close();
            } catch (IOException e) {
                if (ex == null) {
                    ex = e;
                }
            }
        }

        for (int i=0; i<mFilePool.length; i++) {
            try {
                yieldFile(openRaf(mFile, mMode));
            } catch (IOException e) {
                if (ex == null) {
                    ex = e;
                }
            }
        }

        if (ex != null) {
            throw ex;
        }
    }

    @Override
    protected void doSync(boolean metadata) throws IOException {
        RandomAccessFile file = accessFile();
        try {
            file.getChannel().force(metadata);
        } finally {
            yieldFile(file);
        }
    }

    @Override
    public void close() throws IOException {
        close(null);
    }

    @Override
    public void close(Throwable cause) throws IOException {
        if (cause != null && mCause == null) {
            mCause = cause;
        }

        IOException ex = null;
        try {
            unmap(false);
        } catch (IOException e) {
            ex = e;
        }

        RandomAccessFile[] pool = mFilePool;
        synchronized (pool) {
            for (RandomAccessFile file : pool) {
                ex = closeQuietly(ex, file, cause);
            }
        }

        if (ex != null) {
            throw ex;
        }
    }

    private RandomAccessFile accessFile() throws InterruptedIOException {
        RandomAccessFile[] pool = mFilePool;
        synchronized (pool) {
            int top;
            while ((top = mFilePoolTop) == pool.length) {
                try {
                    pool.wait();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
            RandomAccessFile file = pool[top];
            mFilePoolTop = top + 1;
            return file;
        }
    }

    private void yieldFile(RandomAccessFile file) {
        RandomAccessFile[] pool = mFilePool;
        synchronized (pool) {
            pool[--mFilePoolTop] = file;
            pool.notify();
        }
    }

    static FileAccess openRaf(File file, String mode) throws IOException {
        try {
            return new FileAccess(file, mode);
        } catch (FileNotFoundException e) {
            String message = null;

            if (file.isDirectory()) {
                message = "File is a directory";
            } else if (!file.isFile()) {
                message = "Not a normal file";
            } else if ("r".equals(mode)) {
                if (!file.exists()) {
                    message = "File does not exist";
                } else if (!file.canRead()) {
                    message = "File cannot be read";
                }
            } else {
                if (!file.canRead()) {
                    if (!file.canWrite()) {
                        message = "File cannot be read or written";
                    } else {
                        message = "File cannot be read";
                    }
                } else if (!file.canWrite()) {
                    message = "File cannot be written";
                }
            }

            if (message == null) {
                throw e;
            }

            String path = file.getPath();

            String originalMessage = e.getMessage();
            if (originalMessage.indexOf(path) < 0) {
                message = message + ": " + file.getPath() + ' ' + originalMessage;
            } else {
                message = message + ": " + originalMessage;
            }

            throw new FileNotFoundException(message);
        }
    }

    static class FileAccess extends RandomAccessFile {
        private long mPosition;

        FileAccess(File file, String mode) throws IOException {
            super(file, mode);
            seek(0);
        }

        @Override
        public void seek(long pos) throws IOException {
            if (pos != mPosition) {
                super.seek(pos);
                mPosition = pos;
            }
        }

        @Override
        public int read(byte[] buf) throws IOException {
            return read(buf, 0, buf.length);
        }

        @Override
        public int read(byte[] buf, int offset, int length) throws IOException {
            int amt = super.read(buf, offset, length);
            if (amt > 0) {
                mPosition += amt;
            }
            return amt;
        }

        @Override
        public void write(byte[] buf) throws IOException {
            write(buf, 0, buf.length);
        }

        @Override
        public void write(byte[] buf, int offset, int length) throws IOException {
            super.write(buf, offset, length);
            mPosition += length;
        }
    }
}
