package com.hydra.framework.cache;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hydra.framework.cache.JCacheContainer.JCacheBuilder;
import com.hydra.framework.cache.lru.HotEndLruCache;

import java.lang.ref.WeakReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Created by Hydra.
 * <p>
 * 现在是定时trim策略，
 * 有一个地方要避免的就是 缩容和扩容 之间的size的大小对比；避免出现 trim和扩容频繁出现
 * <p>
 * 后续还可以有 put触发的trim策略
 */
public class JCache<T> {

    private static final float DEFAULT_HARD_HOT_PERCENT = 0.75F;
    private static final float DEFAULT_WEAK_HOT_PERCENT = 0.6F;

    // 默认的扩容倍数
    private static final float DEFAULT_SIZE_INCREASE_STEP = 1.5F;

    private static final long TRIM_HARD_INTERVAL = 1000 * 90L;
    private static final long TRIM_WEAK_INTERVAL = 1000 * 90 * 3L;
    private static final long TRIM_WEAK_MAX_INTERVAL = 1000 * 60 * 6L;

    private static final int TRIM_HARD_MAX_COUNT = 1000;
    private static final int TRIM_WEAK_MAX_COUNT = 2000;

    private static final String TAG_PREFIX = "JCache_";

    public static abstract class CacheController<T> {
        public abstract T createNewCacheObject(@NonNull JCacheKey cacheKey);

        public void onNeedRefresh(@NonNull JCacheKey cacheKey, @NonNull JCacheValue<T> cacheObject) {
            // can add refresh action here like refresh from server
        }

        public boolean canValueBeTrimmed(JCacheKey cacheKey, T value) {
            // 自定义每个节点的trim策略
            return true;
        }
    }

    private final HotEndLruCache<JCacheKey, JCacheValue<T>> mHardCache;
    private final HotEndLruCache<JCacheKey, JCacheValue<WeakReference<T>>> mWeakCache;

    private final String mCacheName;
    private final long mExpireTime; //-1 for no expire
    private final String mTag;

    private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();

    private final CacheController<T> mCacheController;

    private final int mHardInitSize, mWeakInitSize;

    private long mLastTrimWeakTime = System.currentTimeMillis();

    public JCache(@NonNull JCacheBuilder<T> builder) {
        this.mCacheName = builder.cacheClazz.getName();

        this.mTag = TAG_PREFIX + this.mCacheName;

        this.mCacheController = builder.cacheController;
        this.mExpireTime = builder.expireTime;

        this.mHardInitSize = builder.minHardSize;
        this.mWeakInitSize = builder.minHardSize * 8;   // weak的初始size == hardSize * 8

        mHardCache = new HotEndLruCache<>(mHardInitSize, DEFAULT_HARD_HOT_PERCENT);
        mWeakCache = new HotEndLruCache<>(mWeakInitSize, DEFAULT_WEAK_HOT_PERCENT);

        startTrimTask();
    }

    /**
     * @return null if not exist in cache and put new data to hard cache, or return exist value
     */
    @Nullable
    public T putIfAbsent(@NonNull JCacheKey cacheKey, @NonNull T data) {
        mLock.readLock().lock();

        JCacheValue<T> cacheObject = mHardCache.get(cacheKey);

        if (cacheObject != null) {
            mLock.readLock().unlock();

            return cacheObject.value;
        }

        mLock.readLock().unlock();
        mLock.writeLock().lock();

        // double check
        cacheObject = mHardCache.get(cacheKey);

        if (cacheObject != null) {
            mLock.writeLock().unlock();

            return cacheObject.value;
        }

        JCacheValue<WeakReference<T>> weakCacheObject = mWeakCache.remove(cacheKey);

        if (weakCacheObject == null) {
            putToHard(cacheKey, new JCacheValue<>(cacheKey, data));

            mLock.writeLock().unlock();

            return null;
        }

        T weakValue = weakCacheObject.value.get();

        if (weakValue == null) {
            putToHard(cacheKey, new JCacheValue<>(cacheKey, data));

            mLock.writeLock().unlock();

            return null;
        }

        putToHard(cacheKey, new JCacheValue<>(cacheKey, weakValue));

        mLock.writeLock().unlock();

        return weakValue;
    }

    @NonNull
    public T get(@NonNull JCacheKey cacheKey) {
        return get(cacheKey, true);
    }

    /**
     * 这里传true时，返回一定会有值
     */
    @Nullable
    public T get(@NonNull JCacheKey cacheKey, boolean autoCreate) {
        JCacheValue<T> cacheObject = cacheObjectForKey(cacheKey, autoCreate);

        if (cacheObject != null) {
            if (mExpireTime != -1L) {
                long current = System.currentTimeMillis();

                long lastRefreshTime = cacheObject.lastRefreshTime;

                if (current - lastRefreshTime >= mExpireTime) {
                    cacheObject.lastRefreshTime = current;

                    YYTaskExecutor.execute(() -> mCacheController.onNeedRefresh(cacheKey, cacheObject));
                }
            }

            return cacheObject.value;
        }

        return null;
    }

    @Nullable
    public JCacheValue<T> cacheObjectForKey(@NonNull JCacheKey cacheKey, boolean autoCreate) {
        JCacheValue<T> cacheObject;

        try {
            mLock.readLock().lock();

            cacheObject = mHardCache.get(cacheKey);

            if (cacheObject != null) {
                return cacheObject;
            }
        } finally {
            mLock.readLock().unlock();
        }

        try {
            mLock.writeLock().lock();

            // double check
            cacheObject = mHardCache.get(cacheKey);

            if (cacheObject == null) {
                cacheObject = createNewValue(cacheKey, autoCreate);
            }
        } finally {
            mLock.writeLock().unlock();
        }

        return cacheObject;
    }

    @Nullable
    private JCacheValue<T> createNewValue(@NonNull JCacheKey cacheKey, boolean autoCreate) {
        JCacheValue<T> cacheObject = null;

        JCacheValue<WeakReference<T>> weakCacheObject = mWeakCache.remove(cacheKey);

        if (weakCacheObject != null) {
            T weakValue = weakCacheObject.value.get();

            if (weakValue != null) {
                cacheObject = new JCacheValue<>(cacheKey, weakValue);
            } else {
                if (autoCreate) {
                    cacheObject = new JCacheValue<>(cacheKey,
                            mCacheController.createNewCacheObject(cacheKey));
                }
            }
        } else if (autoCreate) {
            cacheObject = new JCacheValue<>(cacheKey, mCacheController.createNewCacheObject(cacheKey));
        }

        if (cacheObject == null) {
            return null;
        }

        putToHard(cacheKey, cacheObject);

        return cacheObject;
    }

    private void putToHard(@NonNull JCacheKey cacheKey, @NonNull JCacheValue<T> value) {
        int hardSize = mHardCache.size();
        int hardMaxSize = mHardCache.maxSize();

        if (hardSize + 1 > hardMaxSize) {
            int newHardMaxSize = (int) (hardMaxSize * DEFAULT_SIZE_INCREASE_STEP);

            Log.i(mTag, "putToHard newHardMaxSize: " + newHardMaxSize);

            mHardCache.resize(newHardMaxSize, DEFAULT_HARD_HOT_PERCENT);
        }

        mHardCache.put(cacheKey, value);
    }

    public void clear() {
        mLock.writeLock().lock();

        mHardCache.clear();
        mWeakCache.clear();

        mLock.writeLock().unlock();
    }

    public void releaseCache() {
        clear();

        stopTrimTask();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JCache)) {
            return false;
        }

        JCache other = (JCache) o;

        return mCacheName.equals(other.mCacheName);
    }

    @Override
    public int hashCode() {
        return mCacheName.hashCode();
    }

    private final Runnable mTrimHardTask = new Runnable() {
        @Override
        public void run() {
            trimHard();

            ThreadBus.postDelayed(ThreadBus.Shit, mTrimHardTask, TRIM_HARD_INTERVAL);
        }
    };

    private final Runnable mTrimWeakTask = new Runnable() {
        @Override
        public void run() {
            trimWeak();

            ThreadBus.postDelayed(ThreadBus.Shit, mTrimWeakTask, TRIM_WEAK_INTERVAL);
        }
    };

    private void startTrimTask() {
        ThreadBus.postDelayed(ThreadBus.Shit, mTrimHardTask, TRIM_HARD_INTERVAL);
        ThreadBus.postDelayed(ThreadBus.Shit, mTrimWeakTask, TRIM_WEAK_INTERVAL);
    }

    private void stopTrimTask() {
        ThreadBus.removeCallbacks(ThreadBus.Shit, mTrimHardTask, null);
        ThreadBus.removeCallbacks(ThreadBus.Shit, mTrimWeakTask, null);
    }

    // for count in closure
    private static class IntCounter {
        int count = 0;
    }

    private void trimHard() {
        mLock.writeLock().lock();

        try {
            int maxSize = mHardCache.maxSize();

            if (maxSize <= mHardInitSize) {
                return;
            }

            int currentSize = mHardCache.size();
            int maxHotSize = mHardCache.maxHotSize();

            // max hot size 的 0.75
            int trimThresholdSize = (int) (maxHotSize * 0.75F);

            int maxTrimCount = Math.min(currentSize - trimThresholdSize, TRIM_HARD_MAX_COUNT);

            Log.i(mTag, "trimHard maxTrimCount: " + maxTrimCount + ", trimThresholdSize: " +
                    trimThresholdSize + ", curSize: " + currentSize + ", maxSize: " + maxSize);

            if (maxTrimCount <= 0) {
                return;
            }

            long start = System.currentTimeMillis();

            IntCounter realTrimCount = new IntCounter();

            int traverseTrimCount = mHardCache.traverseTrim(maxTrimCount, (key, value) -> {
                if (!canValueBeTrimmed(key, value.value)) {
                    return false;
                }

                mHardCache.remove(key);

                JCacheValue<WeakReference<T>> weakValue = new JCacheValue<>(key,
                        new WeakReference<>(value.value));
                weakValue.lastRefreshTime = value.lastRefreshTime;

                int weakSize = mWeakCache.size();
                int weakMaxSize = mWeakCache.maxSize();

                if (weakSize + 1 > weakMaxSize) {
                    int newWeakMaxSize = (int) (weakMaxSize * DEFAULT_SIZE_INCREASE_STEP);

                    Log.i(mTag, "trimHard weak resize: " + newWeakMaxSize);

                    mWeakCache.resize(newWeakMaxSize, DEFAULT_WEAK_HOT_PERCENT);
                }

                mWeakCache.put(key, weakValue);

                realTrimCount.count++;

                return true;
            });

            currentSize = mHardCache.size();

            if (currentSize <= trimThresholdSize) {
                int newMaxSize = Math.max(maxHotSize, mHardInitSize);

                Log.i(mTag, "trimHard resize: " + newMaxSize);

                mHardCache.resize(newMaxSize, DEFAULT_HARD_HOT_PERCENT);
            }

            Log.i(mTag, "trimHard traverseTrimCount: " + traverseTrimCount +
                    ", realTrimCount: " + realTrimCount.count + ", trimThresholdSize: " +
                    trimThresholdSize + ", curSize: " + currentSize + ", maxSize: " +
                    mHardCache.maxSize() + ", cost: " + (System.currentTimeMillis() - start));
        } finally {
            mLock.writeLock().unlock();
        }
    }

    private void trimWeak() {
        mLock.writeLock().lock();

        try {
            int maxSize = mWeakCache.maxSize();

            if (maxSize <= mWeakInitSize) {
                return;
            }

            int currentSize = mWeakCache.size();
            int maxHotSize = mWeakCache.maxHotSize();

            int trimThresholdSize = (int) (maxHotSize * 0.75F);

            int maxTrimCount = Math.min(currentSize - trimThresholdSize, TRIM_WEAK_MAX_COUNT);

            Log.i(mTag, "trimWeak maxTrimCount: " + maxTrimCount + ", trimThresholdSize: " +
                    trimThresholdSize + ", curSize: " + currentSize + ", maxSize: " + maxSize);

            long current = System.currentTimeMillis();

            if (maxTrimCount <= 0) {
                //因为weak里的引用是会被回收的，所以再加上一个时间间隔
                if (current - mLastTrimWeakTime < TRIM_WEAK_MAX_INTERVAL || currentSize <= 0) {
                    return;
                }

                maxTrimCount = maxSize - maxHotSize;
            }

            mLastTrimWeakTime = current;

            IntCounter realTrimCount = new IntCounter();

            int traverseTrimCount = mWeakCache.traverseTrim(maxTrimCount, (key, cacheValue) -> {
                T value = cacheValue.value.get();

                if (value == null) {
                    mWeakCache.remove(key);

                    realTrimCount.count++;

                    return true;
                }

                // 这里不需要再从weak移到hard里了，在trim的过程中不做反向移动，会乱掉

                return false;
            });

            currentSize = mWeakCache.size();

            if (currentSize <= trimThresholdSize) {
                int newMaxSize = Math.max(maxHotSize, mWeakInitSize);

                Log.i(mTag, "trimWeak resize: " + newMaxSize);

                mWeakCache.resize(newMaxSize, DEFAULT_WEAK_HOT_PERCENT);
            }

            Log.i(mTag, "trimWeak traverseTrimCount: " + traverseTrimCount +
                    ", realTrimCount: " + realTrimCount.count + ", trimThresholdSize: " +
                    trimThresholdSize + ", curSize: " + currentSize + ", maxSize: " +
                    mWeakCache.maxSize() + ", cost: " + (System.currentTimeMillis() - mLastTrimWeakTime));
        } finally {
            mLock.writeLock().unlock();
        }
    }

    protected boolean canValueBeTrimmed(JCacheKey cacheKey, T value) {
        return mCacheController.canValueBeTrimmed(cacheKey, value);
    }
}
