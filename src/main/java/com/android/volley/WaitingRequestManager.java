package com.android.volley;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

class WaitingRequestManager implements Request.NetworkRequestCompleteListener {

    /**
     * Staging area for requests that already have a duplicate request in flight.
     *
     * <ul>
     *   <li>containsKey(cacheKey) indicates that there is a request in flight for the given
     *       cache key.
     *   <li>get(cacheKey) returns waiting requests for the given cache key. The in flight
     *       request is <em>not</em> contained in that list. Is null if no requests are staged.
     * </ul>
     */
    private final Map<String, List<Request<?>>> mWaitingRequests = new HashMap<>();

    private final ResponseDelivery mResponseDelivery;

    private final RequestQueue mRequestQueue;

    private final CacheDispatcher mCacheDispatcher;
    private final BlockingQueue<Request<?>> mNetworkQueue;

    WaitingRequestManager(RequestQueue requestQueue) {
        mRequestQueue = requestQueue;
        mResponseDelivery = mRequestQueue.getResponseDelivery();
        mCacheDispatcher = null;
        mNetworkQueue = null;
    }

    WaitingRequestManager(
            CacheDispatcher cacheDispatcher,
            BlockingQueue<Request<?>> networkQueue,
            ResponseDelivery responseDelivery) {
        mRequestQueue = null;
        mResponseDelivery = responseDelivery;
        mCacheDispatcher = cacheDispatcher;
        mNetworkQueue = networkQueue;
    }

    /** Request received a valid response that can be used by other waiting requests. */
    @Override
    public void onResponseReceived(Request<?> request, Response<?> response) {
        if (response.cacheEntry == null || response.cacheEntry.isExpired()) {
            onNoUsableResponseReceived(request);
            return;
        }
        String cacheKey = request.getCacheKey();
        List<Request<?>> waitingRequests;
        synchronized (this) {
            waitingRequests = mWaitingRequests.remove(cacheKey);
        }
        if (waitingRequests != null) {
            if (VolleyLog.DEBUG) {
                VolleyLog.v(
                        "Releasing %d waiting requests for cacheKey=%s.",
                        waitingRequests.size(), cacheKey);
            }
            // Process all queued up requests.
            for (Request<?> waiting : waitingRequests) {
                mResponseDelivery.postResponse(waiting, response);
            }
        }
    }

    /** No valid response received from network, release waiting requests. */
    @Override
    public synchronized void onNoUsableResponseReceived(Request<?> request) {
        String cacheKey = request.getCacheKey();
        List<Request<?>> waitingRequests = mWaitingRequests.remove(cacheKey);
        if (waitingRequests != null && !waitingRequests.isEmpty()) {
            if (VolleyLog.DEBUG) {
                VolleyLog.v(
                        "%d waiting requests for cacheKey=%s; resend to network",
                        waitingRequests.size(), cacheKey);
            }
            Request<?> nextInLine = waitingRequests.remove(0);
            mWaitingRequests.put(cacheKey, waitingRequests);
            nextInLine.setNetworkRequestCompleteListener(this);
            if (mRequestQueue != null) {
                mRequestQueue.sendRequestOverNetwork(request);
            } else {
                try {
                    mNetworkQueue.put(request);
                } catch(InterruptedException iex){
                    VolleyLog.e("Couldn't add request to queue. %s", iex.toString());
                    // Restore the interrupted status of the calling thread (i.e. NetworkDispatcher)
                    Thread.currentThread().interrupt();
                    // Quit the current CacheDispatcher thread.
                    mCacheDispatcher.quit();
                }
            }
        }
    }

    /**
     * For cacheable requests, if a request for the same cache key is already in flight, add it
     * to a queue to wait for that in-flight request to finish.
     *
     * @return whether the request was queued. If false, we should continue issuing the request
     *     over the network. If true, we should put the request on hold to be processed when the
     *     in-flight request finishes.
     */
    synchronized boolean maybeAddToWaitingRequests(Request<?> request) {
        String cacheKey = request.getCacheKey();
        // Insert request into stage if there's already a request with the same cache key
        // in flight.
        if (mWaitingRequests.containsKey(cacheKey)) {
            // There is already a request in flight. Queue up.
            List<Request<?>> stagedRequests = mWaitingRequests.get(cacheKey);
            if (stagedRequests == null) {
                stagedRequests = new ArrayList<>();
            }
            request.addMarker("waiting-for-response");
            stagedRequests.add(request);
            mWaitingRequests.put(cacheKey, stagedRequests);
            if (VolleyLog.DEBUG) {
                VolleyLog.d("Request for cacheKey=%s is in flight, putting on hold.", cacheKey);
            }
            return true;
        } else {
            // Insert 'null' queue for this cacheKey, indicating there is now a request in
            // flight.
            mWaitingRequests.put(cacheKey, null);
            request.setNetworkRequestCompleteListener(this);
            if (VolleyLog.DEBUG) {
                VolleyLog.d("new request, sending to network %s", cacheKey);
            }
            return false;
        }
    }
}