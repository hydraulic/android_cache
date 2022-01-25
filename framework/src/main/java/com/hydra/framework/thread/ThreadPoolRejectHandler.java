package com.hydra.framework.thread;

import android.util.Log;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

public class ThreadPoolRejectHandler implements RejectedExecutionHandler {

    private String mName;
    private int mPriority;

    public ThreadPoolRejectHandler(String name, int priority) {
        mName = name;
        mPriority = priority;
    }

    //RejectedExecutionHandler里的Runnable参数，不一定是调用时传进去的原来的那个Runnable
    //在ScheduledThreadPoolExecutor中是经过包装过的task
    //在ThreadPoolExecutor中就是原来的runnable
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        Log.e("ThreadPoolRejectHandler", "thread pool reject name : " + mName +
                " priority : " + mPriority + " maxSize : " + executor.getMaximumPoolSize() +
                " curSize : " + executor.getPoolSize() + " activeCount : " + executor.getActiveCount() +
                " runnable : " + r.getClass().getName());
    }
}
