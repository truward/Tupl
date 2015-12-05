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

package org.cojen.tupl;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Random;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;

import java.security.GeneralSecurityException;

import org.cojen.tupl.io.FileFactory;
import org.cojen.tupl.io.FileIO;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class RedoLog extends RedoWriter {
    private static final long MAGIC_NUMBER = 431399725605778814L;
    private static final int ENCODING_VERSION = 20130106;

    private final Crypto mCrypto;
    private final File mBaseFile;
    private final FileFactory mFileFactory;

    private final boolean mReplayMode;

    private long mLogId;
    private long mPosition;
    private OutputStream mOut;
    private volatile FileChannel mChannel;

    private int mTermRndSeed;

    private long mNextLogId;
    private long mNextPosition;
    private OutputStream mNextOut;
    private FileChannel mNextChannel;
    private int mNextTermRndSeed;

    private volatile OutputStream mOldOut;
    private volatile FileChannel mOldChannel;

    private long mDeleteLogId;

    /**
     * Open for replay.
     *
     * @param logId first log id to open
     */
    RedoLog(DatabaseConfig config, long logId, long redoPos) throws IOException {
        this(config.mCrypto, config.mBaseFile, config.mFileFactory, logId, redoPos, true);
    }

    /**
     * Open after replay.
     *
     * @param logId first log id to open
     */
    RedoLog(DatabaseConfig config, RedoLog replayed) throws IOException {
        this(config.mCrypto, config.mBaseFile, config.mFileFactory,
             replayed.mLogId, replayed.mPosition, false);
    }

    /**
     * @param crypto optional
     * @param factory optional
     * @param logId first log id to open
     */
    RedoLog(Crypto crypto, File baseFile, FileFactory factory,
            long logId, long redoPos, boolean replay)
        throws IOException
    {
        super(4096, 0);

        mCrypto = crypto;
        mBaseFile = baseFile;
        mFileFactory = factory;
        mReplayMode = replay;

        synchronized (this) {
            mLogId = logId;
            mPosition = redoPos;
            if (!replay) {
                openNextFile(logId);
                applyNextFile();
                // Log will be deleted after next checkpoint finishes.
                mDeleteLogId = logId;
            }
        }
    }

    /**
     * @return all the files which were replayed
     */
    synchronized Set<File> replay(RedoVisitor visitor,
                                  EventListener listener, EventType type, String message)
        throws IOException
    {
        if (!mReplayMode || mBaseFile == null) {
            throw new IllegalStateException();
        }

        try {
            Set<File> files = new LinkedHashSet<>(2);

            while (true) {
                File file = fileFor(mBaseFile, mLogId);

                InputStream in;
                try {
                    in = new FileInputStream(file);
                } catch (FileNotFoundException e) {
                    break;
                }

                boolean finished;
                try {
                    if (mCrypto != null) {
                        try {
                            in = mCrypto.newDecryptingStream(mLogId, in);
                        } catch (IOException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new DatabaseException(e);
                        }
                    }

                    if (listener != null) {
                        listener.notify(type, message, mLogId);
                    }

                    files.add(file);

                    DataIn din = new DataIn.Stream(mPosition, in);
                    finished = replay(din, visitor, listener);
                    mPosition = din.mPos;
                } finally {
                    Utils.closeQuietly(null, in);
                }

                mLogId++;

                if (!finished) {
                    // Last log file was truncated, so chuck the rest.
                    Utils.deleteNumberedFiles(mBaseFile, LocalDatabase.REDO_FILE_SUFFIX, mLogId);
                    break;
                }
            }

            return files;
        } catch (IOException e) {
            throw Utils.rethrow(e, mCause);
        }
    }

    static void deleteOldFile(File baseFile, long logId) {
        fileFor(baseFile, logId).delete();
    }

    private void openNextFile(long logId) throws IOException {
        byte[] header = new byte[8 + 4 + 8 + 4];

        final File file = fileFor(mBaseFile, logId);
        if (file.exists() && file.length() > header.length) {
            throw new FileNotFoundException("Log file already exists: " + file.getPath());
        }

        if (mFileFactory != null) {
            mFileFactory.createFile(file);
        }

        FileOutputStream fout = null;
        OutputStream nextOut;
        FileChannel nextChannel;
        int nextTermRndSeed = Utils.randomSeed();

        try {
            fout = new FileOutputStream(file);
            nextChannel = fout.getChannel();

            if (mCrypto == null) {
                nextOut = fout;
            } else {
                try {
                    nextOut = mCrypto.newEncryptingStream(logId, fout);
                } catch (GeneralSecurityException e) {
                    throw new DatabaseException(e);
                }
            }

            int offset = 0;
            Utils.encodeLongLE(header, offset, MAGIC_NUMBER); offset += 8;
            Utils.encodeIntLE(header, offset, ENCODING_VERSION); offset += 4;
            Utils.encodeLongLE(header, offset, logId); offset += 8;
            Utils.encodeIntLE(header, offset, nextTermRndSeed); offset += 4;
            if (offset != header.length) {
                throw new AssertionError();
            }

            nextOut.write(header);

            // Make sure that parent directory durably records the new log file.
            FileIO.dirSync(file);
        } catch (IOException e) {
            Utils.closeQuietly(null, fout);
            file.delete();
            throw new WriteFailureException(e);
        }

        mNextLogId = logId;
        mNextOut = nextOut;
        mNextChannel = nextChannel;
        mNextTermRndSeed = nextTermRndSeed;
    }

    private void applyNextFile() throws IOException {
        final OutputStream oldOut;
        final FileChannel oldChannel;
        synchronized (this) {
            oldOut = mOut;
            oldChannel = mChannel;

            if (oldOut != null) {
                endFile();
            }

            mNextPosition = mPosition;

            mOut = mNextOut;
            mChannel = mNextChannel;
            mTermRndSeed = mNextTermRndSeed;
            mLogId = mNextLogId;

            mNextOut = null;
            mNextChannel = null;

            timestamp();
            reset();
        }

        // Close old file if previous checkpoint aborted.
        Utils.closeQuietly(null, mOldOut);

        mOldOut = oldOut;
        mOldChannel = oldChannel;
    }

    /**
     * @return null if non-durable
     */
    private static File fileFor(File base, long logId) {
        return base == null ? null : new File(base.getPath() + ".redo." + logId);
    }

    @Override
    public final long encoding() {
        return 0;
    }

    @Override
    public final RedoWriter txnRedoWriter() {
        return this;
    }

    @Override
    boolean isOpen() {
        FileChannel channel = mChannel;
        return channel != null && channel.isOpen();
    }

    @Override
    boolean shouldCheckpoint(long size) {
        try {
            FileChannel channel = mChannel;
            return channel != null && channel.size() >= size;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    void checkpointPrepare() throws IOException {
        if (mReplayMode) {
            throw new IllegalStateException();
        }

        final long logId;
        synchronized (this) {
            logId = mLogId;
        }
        openNextFile(logId + 1);
    }

    @Override
    void checkpointSwitch() throws IOException {
        applyNextFile();
    }

    @Override
    long checkpointNumber() {
        return mNextLogId;
    }

    @Override
    long checkpointPosition() {
        return mNextPosition;
    }

    @Override
    long checkpointTransactionId() {
        // Log file always begins with a reset.
        return 0;
    }

    @Override
    void checkpointAborted() {
        if (mNextOut != null) {
            Utils.closeQuietly(null, mNextOut);
            mNextOut = null;
        }
    }

    @Override
    void checkpointStarted() throws IOException {
        /* Forcing the old redo log increases I/O and slows down the checkpoint. If the
           checkpoint completes, then durable persistence of the old redo log file was
           unnecessary. Applications which require stronger durability can select an
           appropriate mode or call sync periodically.

        FileChannel oldChannel = mOldChannel;

        if (oldChannel != null) {
            // Make sure any exception thrown by this call is not caught here,
            // because a checkpoint cannot complete successfully if the redo
            // log has not been durably written.
            oldChannel.force(true);
            mOldChannel = null;
        }

        Utils.closeQuietly(null, mOldOut);
        */
    }

    @Override
    void checkpointFlushed() throws IOException {
        // Nothing to do.
    }

    @Override
    void checkpointFinished() throws IOException {
        mOldChannel = null;
        Utils.closeQuietly(null, mOldOut);
        long id = mDeleteLogId;
        for (; id < mNextLogId; id++) {
            // Typically deletes one file, but more will accumulate if checkpoints abort.
            deleteOldFile(mBaseFile, id);
        }
        // Log will be deleted after next checkpoint finishes.
        mDeleteLogId = id;
    }

    @Override
    void write(byte[] buffer, int len) throws IOException {
        try {
            mOut.write(buffer, 0, len);
            mPosition += len;
        } catch (IOException e) {
            throw new WriteFailureException(e);
        }
    }

    @Override
    long writeCommit(byte[] buffer, int len) throws IOException {
        try {
            long pos = mPosition + len;
            mOut.write(buffer, 0, len);
            mPosition = pos;
            return pos;
        } catch (IOException e) {
            throw new WriteFailureException(e);
        }
    }

    @Override
    void force(boolean metadata) throws IOException {
        FileChannel oldChannel = mOldChannel;
        if (oldChannel != null) {
            // Ensure old file is forced before current file. Proper ordering is critical.
            try {
                oldChannel.force(true);
            } catch (ClosedChannelException e) {
                // Ignore.
            }
            mOldChannel = null;
        }

        FileChannel channel = mChannel;
        if (channel != null) {
            try {
                channel.force(metadata);
            } catch (ClosedChannelException e) {
                // Ignore.
            }
        }
    }

    @Override
    void forceAndClose() throws IOException {
        FileChannel channel = mChannel;
        if (channel != null) {
            try {
                channel.force(true);
                try {
                    channel.close();
                } catch (IOException e) {
                    // Ignore.
                }
            } catch (ClosedChannelException e) {
                // Ignore.
            }
        }
    }

    @Override
    void writeTerminator() throws IOException {
        writeIntLE(nextTermRnd());
    }

    // Caller must be synchronized (replay is exempt)
    int nextTermRnd() {
        return mTermRndSeed = Utils.nextRandom(mTermRndSeed);
    }

    private boolean replay(DataIn in, RedoVisitor visitor, EventListener listener)
        throws IOException
    {
        try {
            long magic = in.readLongLE();
            if (magic != MAGIC_NUMBER) {
                if (magic == 0) {
                    // Assume file was flushed improperly and discard it.
                    return false;
                }
                throw new DatabaseException("Incorrect magic number in redo log file");
            }
        } catch (EOFException e) {
            // Assume file was flushed improperly and discard it.
            return false;
        }

        int version = in.readIntLE();
        if (version != ENCODING_VERSION) {
            throw new DatabaseException("Unsupported redo log encoding version: " + version);
        }

        long id = in.readLongLE();
        if (id != mLogId) {
            throw new DatabaseException
                ("Expected redo log identifier of " + mLogId + ", but actual is: " + id);
        }

        mTermRndSeed = in.readIntLE();

        try {
            return new RedoLogDecoder(this, in, listener).run(visitor);
        } catch (EOFException e) {
            if (listener != null) {
                listener.notify(EventType.RECOVERY_REDO_LOG_CORRUPTION, "Unexpected end of file");
            }
            return false;
        }
    }
}
