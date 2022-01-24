package com.hydra.framework.cache;

import androidx.annotation.NonNull;

/**
 * Created by Hydra.
 *
 */
@SuppressWarnings("rawtypes")
public class JCacheValue<T> {

    @NonNull
    public final JCacheKey cacheKey;

    @NonNull
    public final T value;

    public volatile long lastRefreshTime = System.currentTimeMillis();

    public JCacheValue(@NonNull JCacheKey cacheKey, @NonNull T value) {
        this.cacheKey = cacheKey;
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JCacheValue)) {
            return false;
        }

        JCacheValue other = (JCacheValue) o;

        return this.cacheKey.equals(other.cacheKey);
    }

    @Override
    public int hashCode() {
        return cacheKey.hashCode();
    }
}
