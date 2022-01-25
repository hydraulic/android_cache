package com.hydra.framework.thread;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

/**
 * Created by Hydra.
 */
public class JBlockRunnable implements Runnable {

    private final Runnable mTask;
    private boolean mDone;

    public JBlockRunnable(Runnable task) {
        mTask = task;
    }

    @Override
    public void run() {
        try {
            mTask.run();
        } finally {
            synchronized (this) {
                mDone = true;
                notifyAll();
            }
        }
    }

    public boolean postAndWait(Handler handler, long timeout) {
        if (!handler.post(this)) {
            return false;
        }

        synchronized (this) {
            if (timeout > 0) {
                final long expirationTime = SystemClock.uptimeMillis() + timeout;
                try {
                    while (!mDone) {
                        long delay = expirationTime - SystemClock.uptimeMillis();
                        if (delay <= 0) {
                            return false; // timeout
                        }

                        wait(delay);
                    }
                } catch (InterruptedException ex) {
                    Log.e("JBlockRunnable", "postAndWait error : " + ex.toString());
                }
            } else {
                try {
                    while (!mDone) {
                        wait();
                    }
                } catch (InterruptedException ex) {
                    Log.e("JBlockRunnable", "postAndWait error : " + ex.toString());
                }
            }
        }
        return true;
    }
}
