package com.hydra.framework.thread;

import android.os.Process;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.NonNull;

public class PriorityThreadFactory implements ThreadFactory {

    private static final AtomicInteger poolNumber = new AtomicInteger(1);
    private final ThreadGroup group;
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final String namePrefix;
    private final int mPriority;

    public PriorityThreadFactory(String poolName, int priority) {
        SecurityManager s = System.getSecurityManager();
        group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        namePrefix = "pool-" + poolName + "-" + poolNumber.getAndIncrement() + "-thread-" + priority + "-";
        mPriority = priority;
    }

    @Override
    public Thread newThread(@NonNull Runnable r) {
        Thread t = new PriorityThread(group, r, namePrefix + threadNumber.getAndIncrement(),
                0, mPriority);

        if (t.isDaemon()) {
            t.setDaemon(false);
        }

        return t;
    }

    private static class PriorityThread extends Thread {

        private int mPriority;

        PriorityThread(ThreadGroup group, Runnable target, String name,
                       long stackSize, int priority) {
            super(group, target, name, stackSize);

            mPriority = priority;
        }

        @Override
        public void run() {
            //对于线程池来说，设置优先级的地方，不要放到runnable的run里
            //因为对于线程池来说，一个线程是存在复用的，不断的切换Thread的优先级是有开销的
            //所以在ThreadBus中一个线程池的优先级是固定的
            Process.setThreadPriority(mPriority);

            super.run();
        }
    }
}
