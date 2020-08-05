package com.android.volley;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AsyncNetwork implements Network {

    // TODO: How to handle runtime exceptions?
    public interface OnRequestComplete {
        void onSuccess(NetworkResponse networkResponse);

        void onError(VolleyError volleyError);
    }

    public abstract void performRequest(Request<?> request, OnRequestComplete callback);

    @Override
    public NetworkResponse performRequest(Request<?> request) throws VolleyError {
        // Is there a better way to go about this?
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<NetworkResponse> reference = new AtomicReference<>();
        final AtomicReference<VolleyError> error = new AtomicReference<>();
        performRequest(
                request,
                new OnRequestComplete() {
                    @Override
                    public void onSuccess(NetworkResponse networkResponse) {
                        reference.set(networkResponse);
                        latch.countDown();
                    }

                    @Override
                    public void onError(VolleyError volleyError) {
                        reference.set(null);
                        error.set(volleyError);
                        latch.countDown();
                    }
                });
        try {
            latch.await();
            NetworkResponse response = reference.get();
            if (response == null) {
                throw new VolleyError(error.get());
            } else {
                return response;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
