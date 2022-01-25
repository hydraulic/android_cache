package com.hydra.framework.thread;

import android.annotation.SuppressLint;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.SystemClock;
import android.util.Log;

import java.util.WeakHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import androidx.annotation.NonNull;

/**
 * 对于ScheduledThreadPoolExecutor来说，设置maximumPoolSize是没用的，因为默认是无界队列
 * 所以做了一个简单的maxPoolSize限制
 * <p>
 * 而且系统的建议是不要设置allowCoreThreadTimeOut和保持核心线程数大于0
 */
public class ScheduledThreadPoolAsyncAdapter implements ThreadAsyncAdapter {

    private static final String LOG_TAG = "ScheduledThreadPoolAsyncAdapter";

    public static final int MAX_THREAD_POOL_SIZE = 30;

    //默认一分钟
    private static final long DEFAULT_KEEP_ALIVE_TIME = 60;

    private final ScheduledThreadPoolExecutor mScheduledExecutor;

    private final String mName;

    private WeakHashMap<Runnable, ScheduledFuture> mTaskMap = new WeakHashMap<>(MAX_THREAD_POOL_SIZE);
    private final ReentrantLock mTaskMapLock = new ReentrantLock();

    public ScheduledThreadPoolAsyncAdapter(String name, int corePoolSize, int priority) {
        mName = name;

        mScheduledExecutor = new ScheduledThreadPoolExecutor(corePoolSize,
                new PriorityThreadFactory(name, priority));

        mScheduledExecutor.setKeepAliveTime(DEFAULT_KEEP_ALIVE_TIME, TimeUnit.SECONDS);
        mScheduledExecutor.setRejectedExecutionHandler(new ThreadPoolRejectHandler(name, priority));

        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            mScheduledExecutor.setRemoveOnCancelPolicy(true);
        }
    }

    public ScheduledThreadPoolAsyncAdapter(String name, ScheduledThreadPoolExecutor threadPoolExecutor) {
        mName = name;

        mScheduledExecutor = threadPoolExecutor;
    }

    @Override
    public boolean post(Runnable r) {
        return postDelayed(r, 0L);
    }

    @Override
    public boolean postDelayed(@NonNull Runnable r, long delayMillis) {
        if (verifyMaxPoolSize(r)) {
            try {
                mTaskMapLock.lock();

                mTaskMap.put(r, mScheduledExecutor.schedule(r, delayMillis, TimeUnit.MILLISECONDS));
            } finally {
                mTaskMapLock.unlock();
            }

            return true;
        }

        return false;
    }

    @Override
    public boolean postAtTime(Runnable r, long uptimeMillis) {
        return postDelayed(r, Math.max(uptimeMillis - SystemClock.uptimeMillis(), 0));
    }

    @SuppressLint("LongLogTag")
    @Override
    public void removeCallbacks(Runnable r, Object token) {
        ScheduledFuture runnableFuture;

        try {
            mTaskMapLock.lock();

            runnableFuture = mTaskMap.remove(r);
        } finally {
            mTaskMapLock.unlock();
        }

        try {
            if (runnableFuture != null) {
                runnableFuture.cancel(false);

                //在21版本以上时我们设置了setRemoveOnCancelPolicy
                if (VERSION.SDK_INT < VERSION_CODES.LOLLIPOP) {
                    mScheduledExecutor.purge();
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "removeCallbacks future cancel error : " + Log.getStackTraceString(e));
        }
    }

    @Override
    public void quit() {
        mScheduledExecutor.shutdown();
    }

    @Override
    public boolean isCurrentThread() {
        return false;
    }

    @SuppressLint("LongLogTag")
    private boolean verifyMaxPoolSize(Runnable runnable) {
        if (mScheduledExecutor.getPoolSize() >= MAX_THREAD_POOL_SIZE) {
            if (true) {
                throw new IllegalThreadStateException("thread pool " + mName + " reach max pool size, " +
                        "runnable is " + runnable.getClass().getSimpleName());
            }

            Log.e(LOG_TAG, "thread pool reach max size : " + mScheduledExecutor.getPoolSize());

            //这里直接调用了ScheduledThreadPoolExecutor的getRejectedExecutionHandler
            //本来这个函数在ScheduledThreadPoolExecutor中传的参数是一个包装后的ScheduledFutureTask
            //这里直接把runnable传进去了，类型是不一样的，使用的时候要知道
            mScheduledExecutor.getRejectedExecutionHandler().rejectedExecution(runnable, mScheduledExecutor);

            return false;
        }

        return true;
    }
}
