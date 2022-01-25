package com.hydra.framework.thread;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

public class HandlerThreadAsyncAdapter implements ThreadAsyncAdapter {

    private Handler mHandler;

    public HandlerThreadAsyncAdapter(Looper looper) {
        mHandler = new Handler(looper);
    }

    public HandlerThreadAsyncAdapter(String name, int priority) {
        HandlerThread thread = new HandlerThread(name, priority);
        thread.start();

        mHandler = new Handler(thread.getLooper());
    }

    @Override
    public boolean post(Runnable r) {
        return mHandler.post(r);
    }

    @Override
    public boolean postDelayed(Runnable r, long delayMillis) {
        return mHandler.postDelayed(r, delayMillis);
    }

    @Override
    public boolean postAtTime(Runnable r, long uptimeMillis) {
        return mHandler.postAtTime(r, uptimeMillis);
    }

    @Override
    public void removeCallbacks(Runnable r, Object token) {
        mHandler.removeCallbacks(r, token);
    }

    @Override
    public void quit() {
        mHandler.getLooper().quitSafely();
    }

    @Override
    public boolean isCurrentThread() {
        return mHandler.getLooper() == Looper.myLooper();
    }
}
