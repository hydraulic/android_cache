package com.hydra.framework.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hydra.framework.cache.JCache.CacheController;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 描述一下内存缓存的设计结构和思想：
 * 1、HotEndLruCache：改进的LRU算法，双向循环链表+热冷双指针，配合读写锁，性能比普通的LRUCache好很多，特别是在读的时候，纯读锁可以支持很高的并发
 * 2、在HotEndLruCache的基础上，我们做了两层缓存： hardCache size比较小，操作速度快；weakCache size比较大，负责垃圾回收
 * 3、两层缓存的都是可以无限expand，保证一定可以存进去，这时HotEndLruCache自带的trim就失效了
 * 4、所以我们在JCache里，做了定时的扫描清理策略，通过当前的size、配合一个低优先级的线程进行定时清理，测过10000量级的清理耗时，最大时耗时在30ms左右
 * 5、如何平衡 内存使用 和 垃圾回收导致的不可控因素，加入了canValueBeTrimmed接口，这个接口有三种逻辑：
 * 1) 当你觉得自己的缓存很重要，从服务器拉到数据后，放到缓存里要保证一定能拿到而不被回收，那你可以无脑返回 false，即不能被trim，这样你的缓存会
 * 一直被保存在hardCache中，无限扩容不会丢。
 * 你需要做的就是：你需要明确知道缓存的生命周期，并且在数据量比较大的时候，在适当的时机做clear
 * 2) 当你觉得自己的缓存是可以丢的，被回收了可以随时再去服务器拉一次，那你可以无脑返回 true，即随时可以被trim，这样你的缓存会不定时地
 * 从hard移到weak，并遵从java自己的垃圾回收机制。
 * 你需要做的就是：要有缓存里数据不在的觉悟
 * 3) 如kvoSource这种，有条件的trim。 如果当前source有connection存在时，即source去持有别人的时候，不能被trim；如果没有connection存在，
 * 就可以从hard移到weak。
 * 你需要做的就是：你需要明确知道自己的数据在什么时候可以被回收，在什么时候不被回收。
 * <p>
 * 总体来说：对于客户端来说，数据本身占据的内存不会太多，大部分的内存还是图片资源这种
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class JCacheContainer {

    public static class JCacheBuilder<T> {

        private static final long DEFAULT_EXPIRE_TIME = 5 * 60 * 1000L; //默认是五分钟过期
        private static final int DEFAULT_HARD_MIN_SIZE = 64;

        public Class<T> cacheClazz;

        public CacheController<T> cacheController;

        public long expireTime = DEFAULT_EXPIRE_TIME;

        public int minHardSize = DEFAULT_HARD_MIN_SIZE;

        public JCacheBuilder<T> cacheController(@NonNull CacheController<T> cacheController) {
            this.cacheController = cacheController;

            return this;
        }

        public JCacheBuilder<T> clazz(@NonNull Class<T> cacheClazz) {
            this.cacheClazz = cacheClazz;

            return this;
        }

        public JCacheBuilder<T> expireTime(long expireTime) {
            this.expireTime = expireTime;

            return this;
        }

        public JCacheBuilder<T> minHardSize(int minHardSize) {
            this.minHardSize = minHardSize;

            return this;
        }
    }

    private static final ConcurrentHashMap<Class<?>, JCache<?>> ALL_CACHES = new ConcurrentHashMap<>();

    /**
     * 这里之前的几个接口，把 get 和 build 放在了一起，现在把build和get分开
     * 对于build来说，如果需要再次build，要先remove才可以
     * 对于get来说，返回有可能为空，需要先build才可以
     */
    @Nullable
    public static <T> JCache<T> cacheForClazz(@NonNull Class<T> clazz) {
        return (JCache<T>) ALL_CACHES.get(clazz);
    }

    @NonNull
    public static <T> JCache<T> buildCache(@NonNull Class<T> clazz, @NonNull CacheController<T> cacheController) {
        return buildCache(new JCacheBuilder<T>().cacheController(cacheController).clazz(clazz));
    }

    @NonNull
    public static <T> JCache<T> buildCache(@NonNull JCacheBuilder<T> builder) {
        JCache<T> constCache = (JCache<T>) ALL_CACHES.get(builder.cacheClazz);

        if (constCache == null) {
            synchronized (builder.cacheClazz) {
                constCache = (JCache<T>) ALL_CACHES.get(builder.cacheClazz);

                if (constCache == null) {
                    constCache = new JCache<>(builder);

                    ALL_CACHES.put(builder.cacheClazz, constCache);
                }
            }
        } else {
            throw new RuntimeException("class " + builder.cacheClazz +
                    " already exist in JCache , don't build it again");
        }

        return constCache;
    }

    public static <T> void removeCache(@NonNull Class<T> cacheClazz) {
        JCache<T> cache = (JCache<T>) ALL_CACHES.remove(cacheClazz);

        if (cache != null) {
            cache.releaseCache();
        }
    }
}
