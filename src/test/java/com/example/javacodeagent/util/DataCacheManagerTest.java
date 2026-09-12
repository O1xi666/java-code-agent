package com.example.javacodeagent.util;

import com.example.javacodeagent.config.CachePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * DataCacheManager L1 缓存行为测试（无 Spring 上下文、无网络）
 *
 * 说明：RedisTemplate 为字段注入，这里保持为 null，用于验证现有代码对 Redis 异常的
 * try/catch 兜底：null 时 opsForValue() 会抛异常并被捕获后继续，不影响 L1 逻辑。
 */
class DataCacheManagerTest {

    private DataCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new DataCacheManager();
    }

    @Test
    void testL1Hit_DoesNotInvokeFetcherAgain() {
        AtomicInteger fetchCount = new AtomicInteger();
        Supplier<String> fetcher = () -> {
            fetchCount.incrementAndGet();
            return "quote-1683.50";
        };

        String first = cacheManager.getOrFetch("test:quote:600519", fetcher, CachePolicy.STOCK_QUOTE);
        String second = cacheManager.getOrFetch("test:quote:600519", fetcher, CachePolicy.STOCK_QUOTE);

        assertEquals(1, fetchCount.get(), "L1 命中时不应再次调用 fetcher");
        assertEquals(first, second);
        assertEquals("quote-1683.50", second);
    }

    @Test
    void testDifferentKey_InvokesFetcherAgain() {
        AtomicInteger fetchCount = new AtomicInteger();
        Supplier<String> fetcher = () -> {
            fetchCount.incrementAndGet();
            return "value-" + fetchCount.get();
        };

        cacheManager.getOrFetch("test:quote:600519", fetcher, CachePolicy.STOCK_QUOTE);
        cacheManager.getOrFetch("test:quote:300750", fetcher, CachePolicy.STOCK_QUOTE);

        assertEquals(2, fetchCount.get(), "不同 key 应各自触发一次 fetcher");
    }

    @Test
    void testStats_ReturnsDocumentedKeys() {
        cacheManager.getOrFetch("test:quote:600519", () -> "quote", CachePolicy.STOCK_QUOTE);

        Map<String, Object> stats = cacheManager.stats();

        assertNotNull(stats);
        assertTrue(stats.containsKey("hitCount"));
        assertTrue(stats.containsKey("missCount"));
        assertTrue(stats.containsKey("hitRate"));
        assertTrue(stats.containsKey("evictionCount"));
        assertTrue(stats.containsKey("estimatedSize"));
        assertNotNull(stats.get("hitCount"));
        assertNotNull(stats.get("missCount"));
        assertNotNull(stats.get("hitRate"));
        assertNotNull(stats.get("evictionCount"));
        assertNotNull(stats.get("estimatedSize"));
    }
}
