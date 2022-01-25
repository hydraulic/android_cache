package com.hydra.framework.cache.lru;

import java.util.HashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Created by Hydra.
 * 设计参考文档：https://www.cnblogs.com/cyjb/archive/2012/11/16/LruCache.html
 * 以及：https://blog.51cto.com/yeshaochen/913342
 */
public class HotEndLruCache<K, V> {

    // hot node 和 cold node 分界线，>= 2 时是hot
    private static final int HOT_COLD_BOUNDARY = 2;

    private int mCurSize = 0;
    private int mMaxSize = 0;

    private int mHotSize = 0;
    private int mMaxHotSize = 0;

    private final HashMap<K, LruNode<K, V>> mLocationMap = new HashMap<>(100);

    private LruNode<K, V> mHotHead = null;
    private LruNode<K, V> mColdHead = null;

    private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();

    public HotEndLruCache(int maxSize, float hotPercent) {
        resize(maxSize, hotPercent);
    }

    public void resize(int maxSize, float hotPercent) {
        if (maxSize < HOT_COLD_BOUNDARY || hotPercent < 0.0F || hotPercent >= 1.0F) {
            throw new RuntimeException("HotEndLruCache size parameters error");
        }

        mLock.writeLock().lock();

        try {
            mMaxSize = maxSize;

            // maxSize int [2, +∞]
            // maxHotSize in [1, maxSize - 1]
            mMaxHotSize = Math.min(maxSize - 1, Math.max(1, (int) ((float) maxSize * hotPercent)));

            if (mCurSize > mMaxSize) {
                doTrimTo(mMaxSize);
            }
        } finally {
            mLock.writeLock().unlock();
        }
    }

    @Nullable
    public V get(@NonNull K key) {
        LruNode<K, V> node;

        mLock.readLock().lock();

        if ((node = mLocationMap.get(key)) != null) {
            node.increaseVisitCount();
        }

        mLock.readLock().unlock();

        return node == null ? null : node.value;
    }

    public boolean put(@NonNull K newKey, @NonNull V newValue) {
        LruNode<K, V> newNode = new LruNode<>(newKey, newValue, getSize(newValue));

        if (newNode.size > mMaxSize) {
            return false;
        }

        LruNode<K, V> oldNode;

        mLock.writeLock().lock();

        try {
            if ((oldNode = mLocationMap.put(newKey, newNode)) != null) {
                int lastVisitCount = oldNode.visitCount.get();

                removeNode(oldNode);

                newNode.updateVisitCount(lastVisitCount + 1);
            }

            boolean trimmed = false;

            if (oldNode == null) {
                trimmed = doTrimTo(mMaxSize - newNode.size);
            }

            if (mHotHead != null && mColdHead != null && trimmed) {
                insertBefore(newNode, mColdHead);

                // make a new coldHead
                mColdHead = newNode;
                newNode.isColdNode = true;

                mCurSize += newNode.size;
            } else {
                if (mHotHead != null) {
                    insertBefore(newNode, mHotHead);
                } else {
                    newNode.next = newNode.pre = newNode;
                }

                boolean isDoubleHead = mColdHead == mHotHead;

                //make a new hotHead
                mHotHead = newNode;

                mHotSize += newNode.size;
                mCurSize += newNode.size;

                if (mColdHead == null) {
                    if (mCurSize > mMaxHotSize) {
                        setNewColdHead(mHotHead.pre);
                    }
                } else {
                    if (mHotSize > mMaxHotSize) {
                        if (isDoubleHead && mColdHead.pre != mColdHead) {
                            mHotSize -= mColdHead.size;
                            mColdHead.isColdNode = true;
                        }

                        setNewColdHead(mColdHead.pre);
                    }
                }
            }
        } finally {
            mLock.writeLock().unlock();
        }

        return true;
    }

    protected int getSize(@NonNull V value) {
        return 1;
    }

    public boolean trimTo(int targetSize) {
        mLock.writeLock().lock();

        try {
            return doTrimTo(targetSize);
        } finally {
            mLock.writeLock().unlock();
        }
    }

    // 把trim的锁加去掉了，这个函数在被内部调用时一定要放到写锁里
    private boolean doTrimTo(int targetSize) {
        LruNode<K, V> removed = null;

        while (mCurSize > targetSize) {
            while (true) {
                LruNode<K, V> coldTail = mHotHead.pre;

                if (coldTail.visitCount.get() >= HOT_COLD_BOUNDARY) {
                    coldTail.updateVisitCount(1);

                    setNewHotHead(coldTail);

                    while (true) {
                        if (mHotSize <= mMaxHotSize || !setNewColdHead(mColdHead.pre)) {
                            break;
                        }
                    }

                    continue;
                }

                removed = coldTail;

                mLocationMap.remove(removed.key);
                removeNode(removed);

                break;
            }
        }

        return removed != null;
    }

    /**
     * 把newNode插到existNode的前面
     */
    private void insertBefore(@NonNull LruNode<K, V> newNode, @NonNull LruNode<K, V> existNode) {
        newNode.next = existNode;
        newNode.pre = existNode.pre;

        existNode.pre.next = newNode;
        existNode.pre = newNode;
    }

    @Nullable
    public final V remove(@NonNull K key) {
        LruNode<K, V> node;

        mLock.writeLock().lock();

        try {
            if ((node = mLocationMap.remove(key)) != null) {
                node.updateVisitCount(-1);

                if (node.pre != null) {
                    removeNode(node);
                }
            }
        } finally {
            mLock.writeLock().unlock();
        }

        if (node == null) {
            return null;
        }

        return node.value;
    }

    private void removeNode(@NonNull LruNode<K, V> node) {
        if (node.next == node) {
            //这里就是指最后一个node
            setNewHotHead(null);
            setNewColdHead(null);
        } else {
            node.next.pre = node.pre;
            node.pre.next = node.next;

            if (mHotHead == node) {
                setNewHotHead(node.next);
            }

            if (mColdHead == node) {
                setNewColdHead(node.next);
            }
        }

        mCurSize -= node.size;

        if (!node.isColdNode) {
            mHotSize -= node.size;
        }
    }

    /**
     * @param node 把当前节点设置成新的hotHead
     */
    private void setNewHotHead(@Nullable LruNode<K, V> node) {
        if (node != null) {
            if (node.isColdNode) {
                mHotSize += node.size;
            }

            node.isColdNode = false;
        }

        mHotHead = node;
    }

    private boolean setNewColdHead(@Nullable LruNode<K, V> node) {
        mColdHead = node;

        if (node == null || mHotHead == node) {
            return false;
        }

        if (!node.isColdNode) {
            mHotSize -= node.size;
        }

        node.isColdNode = true;

        return true;
    }

    public interface TraverseCallback<K, V> {
        boolean onTraverse(@NonNull K key, @NonNull V value);
    }

    /**
     * 这里的逻辑有点绕，因为逻辑是这样的：
     * 1、因为外部缓存的两层缓存设计，我们永远也用不到LruCache自带的trim，所以我们需要自己接管trim逻辑
     * 2、在外部缓存中，等于我们对于trim的条件有两个：
     * 1) 在硬缓存中我们有依赖于每个节点自己的 canValueBeTrimmed 逻辑判断
     * 2) 在软引用中，我们有是否被GC 这个条件来判断
     * <p>
     * 所以，我们在这里替换了原有算法里对visitCount的判断来移动hot指针
     */
    public int traverseTrim(int maxCount, @NonNull TraverseCallback<K, V> callback) {
        mLock.writeLock().lock();

        int count = 0;

        try {
            if (mHotHead == null) {
                return 0;
            }

            LruNode<K, V> node = mHotHead.pre;  // cold tail

            for (; count < maxCount; ++count) {
                // 替换了 node.visitCount >= HOT_COLD_BOUNDARY的判断
                //后续可以把这个判断加在前面 node.visitCount.get() >= HOT_COLD_BOUNDARY ||
                if (!callback.onTraverse(node.key, node.value)) {
                    node.updateVisitCount(1);

                    setNewHotHead(node);

                    while (mHotSize > mMaxHotSize) {
                        if (!setNewColdHead(mColdHead.pre)) {
                            break;
                        }
                    }
                }

                LruNode<K, V> pre = node.pre;

                if (pre == node) {
                    break;
                }

                node = pre;
            }
        } finally {
            mLock.writeLock().unlock();
        }

        return count;
    }

    public void clear() {
        mLock.writeLock().lock();

        mLocationMap.clear();

        setNewHotHead(null);
        setNewColdHead(null);

        mCurSize = 0;
        mHotSize = 0;

        mLock.writeLock().unlock();
    }

    public final int size() {
        return mCurSize;
    }

    public final int maxSize() {
        return mMaxSize;
    }

    public final int maxHotSize() {
        return mMaxHotSize;
    }

    @NonNull
    @Override
    public String toString() {
        return "HotEndLruCache{" + "mCurSize=" + mCurSize +
                ", mMaxSize=" + mMaxSize + ", mHotSize=" + mHotSize +
                ", mMaxHotSize=" + mMaxHotSize +
                ", mHotHead=" + mHotHead + ", mColdHead=" + mColdHead + '}';
    }
}
