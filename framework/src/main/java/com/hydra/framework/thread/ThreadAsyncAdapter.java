package com.hydra.framework.thread;

public interface ThreadAsyncAdapter {

    boolean post(Runnable r);

    boolean postDelayed(Runnable r, long delayMillis);

    boolean postAtTime(Runnable r, long uptimeMillis);

    void removeCallbacks(Runnable r, Object token);

    void quit();

    boolean isCurrentThread();
}
