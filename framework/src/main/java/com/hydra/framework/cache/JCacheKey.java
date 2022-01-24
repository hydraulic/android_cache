package com.hydra.framework.cache;

import android.text.TextUtils;

import androidx.annotation.NonNull;

/**
 * Created by Hydra.
 */
@SuppressWarnings("unchecked")
public class JCacheKey {

    public static JCacheKey buildCacheKey(@NonNull Object... ids) {
        return new JCacheKey(ids);
    }

    @NonNull
    private final Object[] mKeys;

    @NonNull
    private final String mKeyStr;

    private JCacheKey(@NonNull Object... keys) {
        mKeys = keys;

        mKeyStr = TextUtils.join(",", keys);
    }

    /**
     * 自行判断index范围
     */
    public <T> T keyAt(int index) {
        return (T) mKeys[index];
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JCacheKey)) {
            return false;
        }

        JCacheKey another = (JCacheKey) o;

        return mKeyStr.equals(another.mKeyStr);
    }

    @NonNull
    @Override
    public String toString() {
        return mKeyStr;
    }

    @Override
    public int hashCode() {
        return mKeyStr.hashCode();
    }
}
