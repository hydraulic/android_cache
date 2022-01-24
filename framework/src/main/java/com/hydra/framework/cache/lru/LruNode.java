package com.hydra.framework.cache.lru;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Hydra.
 */
public class LruNode<K, V> {

    @NonNull
    public final K key;

    @NonNull
    public final V value;

    @Nullable
    public LruNode<K, V> pre;

    @Nullable
    public LruNode<K, V> next;

    public final int size;

    // 默认是1
    public final AtomicInteger visitCount = new AtomicInteger(1);

    public boolean isColdNode = false;

    public LruNode(@NonNull K key, @NonNull V value, int size) {
        this.key = key;
        this.value = value;
        this.size = size;
    }

    public void updateVisitCount(int newCount) {
        visitCount.set(newCount);
    }

    public void increaseVisitCount() {
        int current;
        int nextFlag;

        do {
            current = visitCount.get();

            //visitCount < 0 代表此节点已经被remove
            if (current < 0) {
                break;
            }

            nextFlag = current + 1;
        } while (!visitCount.compareAndSet(current, nextFlag));
    }

    @NonNull
    public String toString() {
        return "LruNode@" + this.hashCode() + "[key:" + this.key + ", value:" +
                this.value + ", visitCount:" + this.visitCount.get() + ", size:" +
                this.size + ", isColdNode:" + this.isColdNode + "]";
    }
}
