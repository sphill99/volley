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

    public AsyncRequestQueue(AsyncCache cache, AsyncNetwork network) {
        this(cache, network, new ExecutorDelivery(new Handler(Looper.getMainLooper())));
    }

    public AsyncRequestQueue(Cache cache, AsyncNetwork network) {
        this(cache, network, new ExecutorDelivery(new Handler(Looper.getMainLooper())));
    }

    public AsyncRequestQueue(
            AsyncCache cache, AsyncNetwork network, ResponseDelivery responseDelivery) {
        super(null, network, /* threadPoolSize= */ 0, responseDelivery);
        mAsyncCache = cache;
        mNetwork = network;
        mCache = null;
        mWaitingRequestManager = new WaitingRequestManager(this);
    }

    public AsyncRequestQueue(
            Cache cache, AsyncNetwork network, ResponseDelivery responseDelivery) {
        super(cache, network, /* threadPoolSize= */ 0, responseDelivery);
        mAsyncCache = null;
        mNetwork = network;
        mCache = cache;
        mWaitingRequestManager = new WaitingRequestManager(this);
    }

    public AsyncRequestQueue(AsyncCache cache, AsyncNetwork network, ResponseDelivery responseDelivery, ExecutorFactory executorFactory) {
        super(null, network, 0, responseDelivery);
        mNonBlockingExecutor = executorFactory.createNonBlockingExecutor()

    }

    public void setmNonBlockingExecutor(ExecutorService nonBlockingExecutor) {
        mNonBlockingExecutor = nonBlockingExecutor;
    }

    @Override
    public void start() {
        stop(); // Make sure any currently running dispatchers are stopped.
        // TODO: Uncaught exception handler?
        if (mNonBlockingExecutor == null) {
            mNonBlockingExecutor = new ThreadPoolExecutor(
                    /* corePoolSize= */ 0,
                    /* maximumPoolSize= */ 1,
                    /* keepAliveTime= */ 60,
                    /* unit= */ TimeUnit.SECONDS,
                    new PriorityBlockingQueue<>(11, new Comparator<Runnable>() {
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
                    }),
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(@NonNull Runnable runnable) {
                            Thread t = Executors.defaultThreadFactory().newThread(runnable);
                            t.setName("Volley-NonBlockingExecutor");
                            return t;
                        }
                    });
        }

        mNonBlockingExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // This is intentionally blocking, because we don't want to process any requests
                // until the cache is initialized.
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
            }
        });
    }

    @Override
    public void stop() {
        if (mNonBlockingExecutor != null) {
            mNonBlockingExecutor.shutdownNow();
            mNonBlockingExecutor = null;
        }
        if (mBlockingExecutor != null) {
            mBlockingExecutor.shutdownNow();
            mBlockingExecutor = null;
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

    private void handleEntry(Entry entry, final Request<?> mRequest) {
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
        // TODO: Move this parsing to a background thread.
        mRequest.addMarker("cache-hit");
        Response<?> response =
                mRequest.parseNetworkResponse(
                        new NetworkResponse(entry.data, entry.responseHeaders));
        mRequest.addMarker("cache-hit-parsed");

        if (!entry.refreshNeeded()) {
            // Completely unexpired cache hit. Just deliver the response.
            getResponseDelivery().postResponse(mRequest, response);
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
                getResponseDelivery().postResponse(
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
                getResponseDelivery().postResponse(mRequest, response);
            }
        }
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
                public void onSuccess(NetworkResponse networkResponse) {
                    mRequest.addMarker("network-http-complete");

                    // If the server returned 304 AND we delivered a response already,
                    // we're done -- don't deliver a second identical response.
                    if (networkResponse.notModified && mRequest.hasHadResponseDelivered()) {
                        mRequest.finish("not-modified");
                        mRequest.notifyListenerResponseNotUsable();
                        return;
                    }

                    // Parse the response here on the worker thread.
                    // TODO: Move this parsing to a background thread.
                    final Response<?> response = mRequest.parseNetworkResponse(networkResponse);
                    mRequest.addMarker("network-parse-complete");

                    // Write to cache if applicable.
                    // TODO: Only update cache metadata instead of entire record for 304s.
                    if (mRequest.shouldCache() && response.cacheEntry != null) {
                        mAsyncCache.put(mRequest.getCacheKey(), response.cacheEntry, new AsyncCache.OnWriteCompleteCallback() {
                            @Override
                            public void onWriteComplete() {
                                mRequest.addMarker("network-cache-written");
                                // Post the response back.
                                mRequest.markDelivered();
                                getResponseDelivery().postResponse(mRequest, response);
                                mRequest.notifyListenerResponseReceived(response);
                            }
                        });
                    } else {
                        // Post the response back.
                        mRequest.markDelivered();
                        getResponseDelivery().postResponse(mRequest, response);
                        mRequest.notifyListenerResponseReceived(response);
                    }
                }

                @Override
                public void onError(VolleyError volleyError) {
                    volleyError.setNetworkTimeMs(SystemClock.elapsedRealtime() - startTimeMs);
                    // TODO: Move this parsing to a background thread.
                    VolleyError parsedError = mRequest.parseNetworkError(volleyError);
                    getResponseDelivery().postError(mRequest, parsedError);
                    mRequest.notifyListenerResponseNotUsable();
                }
            });
        }
    }

    public interface ExecutorFactory {
        ExecutorService createNonBlockingExecutor(BlockingQueue<?> taskQueue);
        ExecutorService createBlockingExecutor(BlockingQueue<?> taskQueue);
    }

}