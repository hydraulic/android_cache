package com.hydra.framework.thread;

import android.os.Looper;
import android.os.Process;
import android.util.Log;

import com.hydra.framework.thread.HandlerThreadAsyncAdapter;
import com.hydra.framework.thread.ThreadAsyncAdapter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.annotation.NonNull;

/**
 * The android thread priority -20 is the highest and the 19 is the lowest.
 * The java thread priority 10 is the highest and the 1 is the lowest.
 * The priority of main-thread is 5.
 * Note:The priority of work-thread SHOULD NOT higher MUCH than main-thread,otherwise it will
 * occur stuck.
 */
public class ThreadBus {

    // higher than main-thread.
    public static final int THREAD_BUS_PRIORITY_HIGH = Process.THREAD_PRIORITY_MORE_FAVORABLE;

    // higher than main-thread.It is also the default priority for HandlerThread.
    public static final int THREAD_BUS_PRIORITY_MIDDLE = Process.THREAD_PRIORITY_DEFAULT;

    // lower than main-thread.
    public static final int THREAD_BUS_PRIORITY_LOW = Process.THREAD_PRIORITY_BACKGROUND;

    private static ConcurrentHashMap<Integer, ThreadAsyncAdapter> bus = new ConcurrentHashMap<>();

    public static final int Sync = 0;

    //主线程只有一个
    public static final int Main = 1;

    //后续可以根据使用情况调整优先级
    //priority high handler thread
    public static final int Working = 2;
    public static final int Net = 3;

    //priority middle handler thread
    public static final int Bind = 4;
    public static final int Db = 3;

    //priority low handler thread
    public static final int IO = 6;
    public static final int Shit = 4;

    //three priority thread pool
    public static final int High_Pool = 8;
    public static final int Mid_Pool = 9;
    public static final int Low_Pool = 4;

    //固定的线程id的最大值，往后加都是自定义的
    public static final int Inherent_Thread_Index = 5;

    private static AtomicInteger BusThreadBegin = new AtomicInteger(Inherent_Thread_Index);

    private static int gen() {
        return BusThreadBegin.incrementAndGet();
    }

    static {
        init();
    }

    private static void init() {
        addThread(Main, new HandlerThreadAsyncAdapter(Looper.getMainLooper()));

        addThread(Working, new HandlerThreadAsyncAdapter("ThreadBus-Working",
                THREAD_BUS_PRIORITY_HIGH));
        addThread(Net, new HandlerThreadAsyncAdapter("Net", THREAD_BUS_PRIORITY_HIGH));

        addThread(Bind, new HandlerThreadAsyncAdapter("Bind", THREAD_BUS_PRIORITY_MIDDLE));
        addThread(Db, new HandlerThreadAsyncAdapter("ThreadBus-Db", THREAD_BUS_PRIORITY_MIDDLE));

        addThread(IO, new HandlerThreadAsyncAdapter("IO", THREAD_BUS_PRIORITY_LOW));
        addThread(Shit, new HandlerThreadAsyncAdapter("ThreadBus-Shit", THREAD_BUS_PRIORITY_LOW));

        addThread(High_Pool, new ScheduledThreadPoolAsyncAdapter("High_Pool", 4, THREAD_BUS_PRIORITY_HIGH));
        addThread(Mid_Pool, new ScheduledThreadPoolAsyncAdapter("Mid_Pool", 5, THREAD_BUS_PRIORITY_MIDDLE));
        addThread(Low_Pool, new ScheduledThreadPoolAsyncAdapter("Low_Pool", 6, THREAD_BUS_PRIORITY_LOW));
    }

    /**
     * 可自定义添加配置
     */
    public static int addThread(@NonNull ThreadAsyncAdapter threadAsyncAdapter) {
        return addThread(gen(), threadAsyncAdapter);
    }

    private static int addThread(int id, @NonNull ThreadAsyncAdapter threadAsyncAdapter) {
        Log.i("ThreadBus", "addThread id = " + id);

        bus.put(id, threadAsyncAdapter);

        return id;
    }

    public static ThreadAsyncAdapter threadAdapter(int idx) {
        return bus.get(idx);
    }

    public static boolean post(int idx, Runnable r) {
        return threadAdapter(idx).post(r);
    }

    public static boolean postDelayed(int idx, Runnable r, long delayMillis) {
        return threadAdapter(idx).postDelayed(r, delayMillis);
    }

    public static boolean postAtTime(int idx, Runnable r, long uptimeMillis) {
        return threadAdapter(idx).postAtTime(r, uptimeMillis);
    }

    public static void removeCallbacks(int idx, Runnable r, Object token) {
        threadAdapter(idx).removeCallbacks(r, token);
    }

    public static boolean callThreadSafe(int idx, Runnable r) {
        ThreadAsyncAdapter adapter = threadAdapter(idx);
        if (adapter != null) {
            if (adapter.isCurrentThread()) {
                r.run();
            } else {
                adapter.post(r);
            }
            return true;
        }
        return false;
    }

    public static void quitAndRemove(int idx) {
        ThreadAsyncAdapter adapter = bus.remove(idx);

        if (adapter != null) {
            adapter.quit();
        }
    }

    public static boolean isMainThread() {
        return Looper.getMainLooper() == Looper.myLooper();
    }
}
