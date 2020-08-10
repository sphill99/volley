package com.android.volley;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.volley.AsyncCache.OnGetCompleteCallback;
import com.android.volley.AsyncNetwork.OnRequestComplete;
import com.android.volley.Cache.Entry;
import com.android.volley.toolbox.DiskBasedCache;
import com.android.volley.toolbox.ThrowingCache;

import java.util.Comparator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AsyncRequestQueue extends RequestQueue {

    private final AsyncCache mAsyncCache;
    private final AsyncNetwork mNetwork;
    private final Cache mCache;
    private final ResponseDelivery mDelivery;

    /** Executor for non-blocking tasks. */
    private ExecutorService mNonBlockingExecutor;

    /**
     * Executor for blocking tasks.
     *
     * <p>Some tasks in handling requests may not be easy to implement in a non-blocking way, such
     * as reading or parsing the response data. This executor is used to run these tasks.
     */
    private ExecutorService mBlockingExecutor;

    /** Manage list of waiting requests and de-duplicate requests with same cache key. */
    private final WaitingRequestManager mWaitingRequestManager;

    private AsyncRequestQueue(Cache cache, AsyncNetwork network, @Nullable AsyncCache asyncCache, ResponseDelivery responseDelivery, ExecutorService nonBlockingExecutor, ExecutorService blockingExecutor) {
        super(cache, network, 0, responseDelivery);
        mAsyncCache = asyncCache;
        mCache = cache;
        mNetwork = network;
        mNonBlockingExecutor = nonBlockingExecutor;
        mBlockingExecutor = blockingExecutor;
        mDelivery = responseDelivery;
        mWaitingRequestManager = new WaitingRequestManager(this);
    }

    @Override
    public void start() {
        stop(); // Make sure any currently running dispatchers are stopped.
        // TODO: Uncaught exception handler?
        mNonBlockingExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // This is intentionally blocking, because we don't want to process any requests
                // until the cache is initialized.
                if (mAsyncCache != null) {
                    final CountDownLatch latch = new CountDownLatch(1);
                    mAsyncCache.initialize(new AsyncCache.OnWriteCompleteCallback() {
                        @Override
                        public void onWriteComplete() {
                            latch.countDown();
                        }
                    });
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        // TODO: DO SOMETHING
                    }
                } else {
                    mCache.initialize();
                }
            }
        });
    }

    @Override
    public void stop() {
        if (mNonBlockingExecutor != null) {
            mNonBlockingExecutor.shutdownNow();
        }
        if (mBlockingExecutor != null) {
            mBlockingExecutor.shutdownNow();
        }
    }

    <T> void beginRequest(Request<T> request) {
        if (mAsyncCache != null) {
            mNonBlockingExecutor.execute(new AsyncCacheTask<>(request));
        } else {
            mBlockingExecutor.execute(new CacheTask<>(request));
        }
    }

    @Override
    <T> void sendRequestOverNetwork(Request<T> request) {
        mNonBlockingExecutor.execute(new NetworkTask<>(request));
    }

    private static abstract class RequestTask<T> implements Runnable {
        final Request<T> mRequest;

        RequestTask(Request<T> request) {
            mRequest = request;
        }
    }

    private class AsyncCacheTask<T> extends RequestTask<T> {
        AsyncCacheTask(Request<T> request) {super(request); }

        @Override
        public void run() {
            // If the request has been canceled, don't bother dispatching it.
            if (mRequest.isCanceled()) {
                mRequest.finish("cache-discard-canceled");
                return;
            }

            mRequest.addMarker("cache-queue-take");

            // Attempt to retrieve this item from cache.
            assert mAsyncCache != null;
            mAsyncCache.get(mRequest.getCacheKey(), new OnGetCompleteCallback() {
                @Override
                public void onGetComplete(Entry entry) {
                    handleEntry(entry, mRequest);
                }
            });
        }
    }

    private class CacheTask<T> extends RequestTask<T> {
        CacheTask(Request<T> request) {
            super(request);
        }

        @Override
        public void run() {
            // If the request has been canceled, don't bother dispatching it.
            if (mRequest.isCanceled()) {
                mRequest.finish("cache-discard-canceled");
                return;
            }

            mRequest.addMarker("cache-queue-take");


            // Attempt to retrieve this item from cache.
            Entry entry = mCache.get(mRequest.getCacheKey());
            handleEntry(entry, mRequest);
        }
    }

    private void handleEntry(final Entry entry, final Request<?> mRequest) {
        if (entry == null) {
            mRequest.addMarker("cache-miss");
            // Cache miss; send off to the network dispatcher.
            if (!mWaitingRequestManager.maybeAddToWaitingRequests(mRequest)) {
                sendRequestOverNetwork(mRequest);
            }
            return;
        }

        // If it is completely expired, just send it to the network.
        if (entry.isExpired()) {
            mRequest.addMarker("cache-hit-expired");
            mRequest.setCacheEntry(entry);
            if (!mWaitingRequestManager.maybeAddToWaitingRequests(mRequest)) {
                sendRequestOverNetwork(mRequest);
            }
            return;
        }

        // We have a cache hit; parse its data for delivery back to the request.
        mBlockingExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mRequest.addMarker("cache-hit");
                Response<?> response =
                        mRequest.parseNetworkResponse(
                                new NetworkResponse(entry.data, entry.responseHeaders));
                mRequest.addMarker("cache-hit-parsed");

                if (!entry.refreshNeeded()) {
                    // Completely unexpired cache hit. Just deliver the response.
                    mDelivery.postResponse(mRequest, response);
                } else {
                    // Soft-expired cache hit. We can deliver the cached response,
                    // but we need to also send the request to the network for
                    // refreshing.
                    mRequest.addMarker("cache-hit-refresh-needed");
                    mRequest.setCacheEntry(entry);
                    // Mark the response as intermediate.
                    response.intermediate = true;

                    if (!mWaitingRequestManager.maybeAddToWaitingRequests(mRequest)) {
                        // Post the intermediate response back to the user and have
                        // the delivery then forward the request along to the network.
                        mDelivery.postResponse(
                                mRequest,
                                response,
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        sendRequestOverNetwork(mRequest);
                                    }
                                });
                    } else {
                        // request has been added to list of waiting requests
                        // to receive the network response from the first request once it returns.
                        mDelivery.postResponse(mRequest, response);
                    }
                }
            }
        });
    }

    private class NetworkTask<T> extends RequestTask<T> {
        NetworkTask(Request<T> request) {
            super(request);
        }

        @Override
        public void run() {
            // If the request was cancelled already, do not perform the
            // network request.
            if (mRequest.isCanceled()) {
                mRequest.finish("network-discard-cancelled");
                mRequest.notifyListenerResponseNotUsable();
                return;
            }

            final long startTimeMs = SystemClock.elapsedRealtime();
            mRequest.addMarker("network-queue-take");

            // TODO: Figure out what to do with traffic stats tags. Can this be pushed to the
            // HTTP stack, or is it no longer feasible to support?

            // Perform the network request.
            mNetwork.performRequest(mRequest, new OnRequestComplete() {
                @Override
                public void onSuccess(final NetworkResponse networkResponse) {
                    mRequest.addMarker("network-http-complete");

                    // If the server returned 304 AND we delivered a response already,
                    // we're done -- don't deliver a second identical response.
                    if (networkResponse.notModified && mRequest.hasHadResponseDelivered()) {
                        mRequest.finish("not-modified");
                        mRequest.notifyListenerResponseNotUsable();
                        return;
                    }

                    // Parse the response here on the worker thread.
                    mBlockingExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            final Response<?> response = mRequest.parseNetworkResponse(networkResponse);
                            mRequest.addMarker("network-parse-complete");

                            // Write to cache if applicable.
                            // TODO: Only update cache metadata instead of entire record for 304s.
                            if (mRequest.shouldCache() && response.cacheEntry != null) {
                                if (mAsyncCache != null) {
                                    mAsyncCache.put(mRequest.getCacheKey(), response.cacheEntry, new AsyncCache.OnWriteCompleteCallback() {
                                        @Override
                                        public void onWriteComplete() {
                                            handleRequestAfterCached(response);
                                        }
                                    });
                                } else {
                                    mCache.put(mRequest.getCacheKey(), response.cacheEntry);
                                    handleRequestAfterCached(response);
                                }
                            } else {
                                // Post the response back.
                                mRequest.markDelivered();
                                mDelivery.postResponse(mRequest, response);
                                mRequest.notifyListenerResponseReceived(response);
                            }
                        }

                        private void handleRequestAfterCached(Response<?> response) {
                            mRequest.addMarker("network-cache-written");
                            // Post the response back.
                            mRequest.markDelivered();
                            mDelivery.postResponse(mRequest, response);
                            mRequest.notifyListenerResponseReceived(response);
                        }
                    });
                }

                @Override
                public void onError(final VolleyError volleyError) {
                    volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
                    mBlockingExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            VolleyError parsedError = mRequest.parseNetworkError(volleyError);
                            mDelivery.postError(mRequest, parsedError);
                            mRequest.notifyListenerResponseNotUsable();
                        }
                    });
                }
            });
        }
    }

    public interface ExecutorFactory {
        ExecutorService createNonBlockingExecutor(BlockingQueue<?> taskQueue);
        ExecutorService createBlockingExecutor(BlockingQueue<?> taskQueue);
    }

    public static class Builder {
        private static final int DEFAULT_BLOCKING_THREAD_POOL_SIZE = 4;
        private AsyncCache mAsyncCache;
        private final AsyncNetwork mNetwork;
        private Cache mCache;
        private ExecutorFactory mExecutorFactory;
        private ResponseDelivery mResponseDelivery;

        private static final PriorityBlockingQueue<Runnable> mBlockingQueue = new PriorityBlockingQueue<>(11, new Comparator<Runnable>() {
            @Override
            @SuppressWarnings("unchecked")
            public int compare(Runnable r1, Runnable r2) {
                // Vanilla runnables are prioritized first, then RequestTasks are ordered
                // by the underlying Request.
                if (r1 instanceof RequestTask) {
                    if (r2 instanceof RequestTask) {
                        return ((RequestTask) r1).mRequest.compareTo(((RequestTask) r2).mRequest);
                    }
                    return 1;
                }
                return r2 instanceof RequestTask ? -1 : 0;
            }
        });

        public Builder(AsyncNetwork asyncNetwork) {
            if (asyncNetwork == null) {
                throw new IllegalArgumentException("Network cannot be null");
            }
            mNetwork = asyncNetwork;
            mExecutorFactory = null;
            mResponseDelivery = new ExecutorDelivery(new Handler(Looper.getMainLooper()));
            mAsyncCache = null;
            mCache = null;
        }

        public Builder setExecutorFactory(ExecutorFactory executorFactory) {
            mExecutorFactory = executorFactory;
            return this;
        }

        public Builder setResponseDelivery(ResponseDelivery responseDelivery) {
            mResponseDelivery = responseDelivery;
            return this;
        }

        public Builder setAsyncCache(AsyncCache asyncCache) {
            mAsyncCache = asyncCache;
            return this;
        }

        public Builder setCache(Cache cache) {
            mCache = cache;
            return this;
        }

        private ExecutorService getNonBlockingExecutor() {
            ExecutorService nonBlockingExecutor;
            if (mExecutorFactory == null) {
                nonBlockingExecutor = getThreadPoolExecutor(1, "Volley-NonBlockingExecutor");
            } else {
                nonBlockingExecutor = mExecutorFactory.createNonBlockingExecutor(mBlockingQueue);
            }
            return nonBlockingExecutor;
        }

        private ExecutorService getBlockingExecutor() {
            ExecutorService blockingExecutor;
            if (mExecutorFactory == null) {
                blockingExecutor = getThreadPoolExecutor(DEFAULT_BLOCKING_THREAD_POOL_SIZE, "Volley-BlockingExecutor");
            } else {
                blockingExecutor = mExecutorFactory.createBlockingExecutor(mBlockingQueue);
            }
            return blockingExecutor;
        }

        private ExecutorService getThreadPoolExecutor(int poolSize, final String threadName) {
            return new ThreadPoolExecutor(
                    /* corePoolSize= */ 0,
                    /* maximumPoolSize= */ poolSize,
                    /* keepAliveTime= */ 60,
                    /* unit= */ TimeUnit.SECONDS,
                    mBlockingQueue,
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(@NonNull Runnable runnable) {
                            Thread t = Executors.defaultThreadFactory().newThread(runnable);
                            t.setName(threadName);
                            return t;
                        }
                    });
        }

        public AsyncRequestQueue build() {
            if (mCache == null && mAsyncCache == null) {
                throw new IllegalArgumentException("You must set one of the cache objects");
            }
            if (mCache == null) {
                mCache = new ThrowingCache();
            }
            if (mResponseDelivery == null) {
                mResponseDelivery = new ExecutorDelivery(new Handler(Looper.getMainLooper()));
            }
            return new AsyncRequestQueue(mCache, mNetwork, mAsyncCache, mResponseDelivery, getNonBlockingExecutor(), getBlockingExecutor());
        }
    }

}