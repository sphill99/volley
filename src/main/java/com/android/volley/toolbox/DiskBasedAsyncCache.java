/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley.toolbox;

import android.os.Build;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import com.android.volley.AsyncCache;
import com.android.volley.Cache;
import com.android.volley.VolleyLog;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileLock;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * AsyncCache implementation that uses Java NIO's AsynchronousFileChannel to perform asynchronous
 * disk reads and writes.
 */
@SuppressWarnings("LockNotBeforeTry")
@RequiresApi(Build.VERSION_CODES.O)
public class DiskBasedAsyncCache extends AsyncCache {

    /** Map of the Key, CacheHeader pairs */
    private final Map<String, CacheHeader> mEntries = new LinkedHashMap<>(16, .75f, true);

    /** The supplier for the root directory to use for the cache. */
    private final FileSupplier mRootDirectorySupplier;

    /** Total amount of space currently used by the cache in bytes. */
    private long mTotalSize = 0;

    /** The maximum size of the cache in bytes. */
    private final int mMaxCacheSizeInBytes;

    private final ReadWriteLock entriesMapLock = new ReentrantReadWriteLock();

    private final ExecutorService mBlockingExecutor;

    /**
     * Constructs an instance of the DiskBasedAsyncCache at the specified directory.
     *
     * @param rootDirectory The root directory of the cache.
     */
    public DiskBasedAsyncCache(final File rootDirectory, int maxCacheSizeInBytes) {
        mRootDirectorySupplier =
                new FileSupplier() {
                    @Override
                    public File get() {
                        return rootDirectory;
                    }
                };
        mMaxCacheSizeInBytes = maxCacheSizeInBytes;
        mBlockingExecutor = Executors.newSingleThreadExecutor();
    }

    /** Returns the cache entry with the specified key if it exists, null otherwise. */
    @Override
    public void get(final String key, final OnGetCompleteCallback callback) {
        entriesMapLock.readLock().lock();
        final CacheHeader entry = mEntries.get(key);
        entriesMapLock.readLock().unlock();
        // if the entry does not exist, return null.
        if (entry == null) {
            callback.onGetComplete(null);
            return;
        }
        final File file = DiskBasedCacheUtility.getFileForKey(key, mRootDirectorySupplier);
        Path path = Paths.get(file.getPath());

        // channel we can close after IOException
        AsynchronousFileChannel channel = null;
        final AsynchronousFileChannel afc;
        try {
            afc =
                    AsynchronousFileChannel.open(
                            path,
                            Collections.singleton(StandardOpenOption.READ),
                            mBlockingExecutor);
        } catch (IOException e) {
            VolleyLog.e(e, "Failed to read file %s", file.getAbsolutePath());
            closeChannel(channel, "IOException");
            deleteFileAndInvokeCallback(key, callback, file);
            return;
        }
        mBlockingExecutor.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        FileLock fileLock;
                        do {
                            try {
                                fileLock = afc.tryLock(0L, Long.MAX_VALUE, true);
                            } catch (IOException e) {
                                VolleyLog.e(
                                        "Failed to retrieve lock for file", file.getAbsolutePath());
                                closeChannel(afc, "IOException");
                                deleteFileAndInvokeCallback(key, callback, file);
                                return;
                            }
                        } while (fileLock == null);
                        final int size = (int) file.length();
                        final ByteBuffer buffer = ByteBuffer.allocate(size);
                        afc.read(
                                /* destination= */ buffer,
                                /* position= */ 0,
                                /* attachment= */ null,
                                new CompletionHandler<Integer, Void>() {
                                    @Override
                                    public void completed(Integer result, Void v) {
                                        closeChannel(afc, "completed read");
                                        if (size != result) {
                                            VolleyLog.e(
                                                    "File changed while reading: %s",
                                                    file.getAbsolutePath());
                                            deleteFileAndInvokeCallback(key, callback, file);
                                            return;
                                        }
                                        buffer.flip();
                                        CacheHeader entryOnDisk = CacheHeader.readHeader(buffer);
                                        if (entryOnDisk == null) {
                                            // BufferUnderflowException was thrown
                                            // while reading header
                                            deleteFileAndInvokeCallback(key, callback, file);
                                        } else if (!TextUtils.equals(key, entryOnDisk.key)) {
                                            // File shared by two keys and holds
                                            // data for a different entry!
                                            VolleyLog.d(
                                                    "%s: key=%s, found=%s",
                                                    file.getAbsolutePath(), key, entryOnDisk.key);
                                            deleteFileAndInvokeCallback(key, callback, file);
                                        } else {
                                            byte[] data = new byte[buffer.remaining()];
                                            buffer.get(data);
                                            callback.onGetComplete(entry.toCacheEntry(data));
                                        }
                                    }

                                    @Override
                                    public void failed(Throwable exc, Void ignore) {
                                        closeChannel(afc, "failed read");
                                        VolleyLog.e(
                                                exc,
                                                "Failed to read file %s",
                                                file.getAbsolutePath());
                                        deleteFileAndInvokeCallback(key, callback, file);
                                    }
                                });
                    }
                });
    }

    /** Puts the cache entry with a specified key into the cache. */
    @Override
    public void put(
            final String key, final Cache.Entry entry, final OnWriteCompleteCallback callback) {
        if (DiskBasedCacheUtility.wouldBePruned(
                mTotalSize, entry.data.length, mMaxCacheSizeInBytes)) {
            return;
        }

        final File file = DiskBasedCacheUtility.getFileForKey(key, mRootDirectorySupplier);
        Path path = Paths.get(file.getPath());

        // channel we can close after IOException
        final AsynchronousFileChannel afc;
        final CacheHeader header = new CacheHeader(key, entry);

        try {
            Set<OpenOption> set = new HashSet<>();
            set.add(StandardOpenOption.CREATE);
            set.add(StandardOpenOption.WRITE);
            afc = AsynchronousFileChannel.open(path, set, mBlockingExecutor);
            Runnable write =
                    new Runnable() {
                        @Override
                        public void run() {
                            FileLock fileLock;
                            do {
                                try {
                                    fileLock = afc.tryLock();
                                } catch (IOException e) {
                                    deleteFile(file);
                                    initializeIfRootDirectoryDeleted();
                                    callback.onWriteComplete();
                                    return;
                                }
                            } while (fileLock == null);

                            int headerSize = header.getHeaderSize();
                            final int size = entry.data.length + headerSize;
                            ByteBuffer buffer = ByteBuffer.allocate(size);
                            header.writeHeader(buffer);
                            buffer.put(entry.data);
                            buffer.flip();
                            entriesMapLock.writeLock().lock();
                            afc.write(
                                    /* source= */ buffer,
                                    /* position= */ 0,
                                    /* attachment= */ null,
                                    new CompletionHandler<Integer, Void>() {
                                        @Override
                                        public void completed(Integer resultLen, Void ignore) {
                                            if (closeChannel(afc, "completed write")) {
                                                if (resultLen != size) {
                                                    VolleyLog.e(
                                                            "File changed while writing: %s",
                                                            file.getAbsolutePath());
                                                    deleteFile(file);
                                                    callback.onWriteComplete();
                                                    return;
                                                }
                                                header.size = resultLen;
                                                mTotalSize =
                                                        DiskBasedCacheUtility.putEntry(
                                                                key, header, mTotalSize, mEntries);
                                                mTotalSize =
                                                        DiskBasedCacheUtility.pruneIfNeeded(
                                                                mTotalSize,
                                                                mMaxCacheSizeInBytes,
                                                                mEntries,
                                                                mRootDirectorySupplier);
                                            } else {
                                                deleteFile(file);
                                            }
                                            entriesMapLock.writeLock().unlock();
                                            callback.onWriteComplete();
                                        }

                                        @Override
                                        public void failed(Throwable throwable, Void ignore) {
                                            VolleyLog.e(
                                                    throwable,
                                                    "Failed to read file %s",
                                                    file.getAbsolutePath());
                                            deleteFile(file);
                                            closeChannel(afc, "failed read");
                                            entriesMapLock.writeLock().unlock();
                                            callback.onWriteComplete();
                                        }
                                    });
                        }
                    };
            mBlockingExecutor.execute(write);
        } catch (IOException e) {
            deleteFile(file);
            initializeIfRootDirectoryDeleted();
            callback.onWriteComplete();
        }
    }

    /** Clears the cache. Deletes all cached files from disk. */
    @Override
    public void clear(OnWriteCompleteCallback callback) {
        File[] files = mRootDirectorySupplier.get().listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFile(file);
            }
        }
        entriesMapLock.writeLock().lock();
        mEntries.clear();
        entriesMapLock.writeLock().unlock();
        mTotalSize = 0;
        VolleyLog.d("Cache cleared.");
        callback.onWriteComplete();
    }

    /**
     * Initializes the cache. We are suppressing warnings, since we create the futures above and
     * there is no chance this fails.
     */
    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void initialize(final OnWriteCompleteCallback callback) {
        File rootDirectory = mRootDirectorySupplier.get();
        if (!rootDirectory.exists()) {
            createCacheDirectory(rootDirectory);
            callback.onWriteComplete();
            return;
        }
        File[] files = rootDirectory.listFiles();
        if (files == null) {
            callback.onWriteComplete();
            return;
        }
        List<CompletableFuture<Void>> reads = new ArrayList<>();
        entriesMapLock.writeLock().lock();
        for (File file : files) {
            Path path = file.toPath();
            AsynchronousFileChannel channel = null;
            final int entrySize = (int) file.length();
            final ByteBuffer buffer = ByteBuffer.allocate(entrySize);
            final CompletableFuture<Void> fileRead = new CompletableFuture<>();
            try {
                channel = AsynchronousFileChannel.open(path, StandardOpenOption.READ);
                final AsynchronousFileChannel afc = channel;
                afc.read(
                        buffer,
                        0,
                        file,
                        new CompletionHandler<Integer, File>() {
                            @Override
                            public void completed(Integer result, File file) {
                                if (entrySize != result) {
                                    VolleyLog.e(
                                            "File changed while reading: %s",
                                            file.getAbsolutePath());
                                    deleteFile(file);
                                    fileRead.complete(null);
                                    return;
                                }
                                buffer.flip();
                                CacheHeader entry = CacheHeader.readHeader(buffer);
                                if (entry != null) {
                                    closeChannel(afc, "after successful read");
                                    entry.size = entrySize;
                                    mTotalSize =
                                            DiskBasedCacheUtility.putEntry(
                                                    entry.key, entry, mTotalSize, mEntries);
                                } else {
                                    closeChannel(afc, "after failed read");
                                    deleteFile(file);
                                }
                                fileRead.complete(null);
                            }

                            @Override
                            public void failed(Throwable throwable, File file) {
                                closeChannel(afc, "after failed read");
                                deleteFile(file);
                                fileRead.complete(null);
                            }
                        });
            } catch (IOException e) {
                closeChannel(channel, "IOException in initialize");
                deleteFile(file);
            }
            reads.add(fileRead);
        }
        CompletableFuture<Void> voidCompletableFuture =
                CompletableFuture.allOf(reads.toArray(new CompletableFuture[0]));

        voidCompletableFuture.thenRun(
                new Runnable() {
                    @Override
                    public void run() {
                        entriesMapLock.writeLock().unlock();
                        callback.onWriteComplete();
                    }
                });
    }

    /** Invalidates an entry in the cache. */
    @Override
    public void invalidate(
            final String key, final boolean fullExpire, final OnWriteCompleteCallback callback) {
        Cache.Entry entry = null;
        get(
                key,
                new OnGetCompleteCallback() {
                    @Override
                    public void onGetComplete(@Nullable Cache.Entry entry) {
                        if (entry == null) {
                            callback.onWriteComplete();
                        } else {
                            entry.softTtl = 0;
                            if (fullExpire) {
                                entry.ttl = 0;
                            }
                            put(
                                    key,
                                    entry,
                                    new OnWriteCompleteCallback() {
                                        @Override
                                        public void onWriteComplete() {
                                            callback.onWriteComplete();
                                        }
                                    });
                        }
                    }
                });
    }

    /** Removes an entry from the cache. */
    @Override
    public void remove(String key, OnWriteCompleteCallback callback) {
        entriesMapLock.writeLock().lock();
        try {
            deleteFile(DiskBasedCacheUtility.getFileForKey(key, mRootDirectorySupplier));
            mTotalSize = DiskBasedCacheUtility.removeEntry(key, mTotalSize, mEntries);
        } finally {
            entriesMapLock.writeLock().unlock();
        }
        callback.onWriteComplete();
    }

    /** Re-initialize the cache if the directory was deleted. */
    private void initializeIfRootDirectoryDeleted() {
        if (mRootDirectorySupplier.get().exists()) {
            return;
        }
        VolleyLog.d("Re-initializing cache after external clearing.");
        mEntries.clear();
        mTotalSize = 0;
        createCacheDirectory(mRootDirectorySupplier.get());
    }

    /**
     * Closes the asynchronous file channel.
     *
     * @param afc Channel that is being closed.
     * @param endOfMessage End of error message that logs where the close is happening.
     * @return Returns true if the channel is successfully closed or the channel was null, fails if
     *     close results in an IOException.
     */
    private boolean closeChannel(@Nullable AsynchronousFileChannel afc, String endOfMessage) {
        if (afc == null) {
            return true;
        }
        try {
            afc.close();
            return true;
        } catch (IOException e) {
            VolleyLog.e(e, "failed to close file after %s", endOfMessage);
            return false;
        }
    }

    /** Deletes the specified file, and reinitializes the root if it was deleted. */
    private void deleteFile(File file) {
        boolean deleted = file.delete();
        if (!deleted) {
            VolleyLog.d("Could not clean up file %s", file.getAbsolutePath());
        }
    }

    /** Attempts to create the root directory, logging if it was unable to. */
    private void createCacheDirectory(File rootDirectory) {
        if (!rootDirectory.mkdirs()) {
            VolleyLog.e("Unable to create cache dir %s", rootDirectory.getAbsolutePath());
        }
    }

    /**
     * Deletes the file, removes the entry from the map, and calls OnGetComplete with a null value.
     *
     * @param key of the file to be removed.
     * @param callback to be called after removing.
     * @param file to be deleted.
     */
    private void deleteFileAndInvokeCallback(
            String key, OnGetCompleteCallback callback, File file) {
        deleteFile(file);
        mTotalSize = DiskBasedCacheUtility.removeEntry(key, mTotalSize, mEntries);
        callback.onGetComplete(null);
    }
}
