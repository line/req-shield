package com.linecorp.cse.reqshield.spring3.bootcompat

import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Boots the library the way a Spring Boot application does, through its auto-configuration entry.
 *
 * Kept outside the example's package so that the example's component scan never picks these configurations up.
 */
class LibAutoConfigurationBootTest {
    @Test
    fun bootTaskExecutorShouldBeKeptInAnApplicationWithoutSpringMvc() {
        // WebMvcAutoConfiguration orders Boot's task executor configuration early; without it, a library
        // Executor bean registered first would make Boot back off its applicationTaskExecutor.
        run(MinimalApplication::class.java, "spring.autoconfigure.exclude=$WEB_MVC_AUTO_CONFIGURATION").use {
            assertTrue(it.containsBean("applicationTaskExecutor"))
        }
    }

    @Test
    fun userDefinedReqShieldExecutorShouldStartAndBeUsed() {
        run(ApplicationWithOwnExecutor::class.java).use {
            it.getBean(CachedService::class.java).get("boot")
            val cache = it.getBean(InMemoryReqShieldCache::class.java)

            await().atMost(Duration.ofSeconds(5)).until { cache.lastWriterThread != null }
            assertEquals(USER_EXECUTOR_THREAD, cache.lastWriterThread)
        }
    }

    private fun run(
        source: Class<*>,
        vararg properties: String,
    ): ConfigurableApplicationContext =
        SpringApplicationBuilder(source)
            .web(WebApplicationType.NONE)
            .properties(*properties)
            .run()

    @SpringBootConfiguration
    @EnableAutoConfiguration
    open class MinimalApplication {
        @Bean
        open fun reqShieldCache(): ReqShieldCache<String> = InMemoryReqShieldCache()
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    open class ApplicationWithOwnExecutor {
        @Bean
        open fun reqShieldCache(): ReqShieldCache<String> = InMemoryReqShieldCache()

        // Spring shuts the pool down with the context through its inferred destroy method
        @Bean
        open fun reqShieldExecutor(): ExecutorService = Executors.newSingleThreadExecutor { Thread(it, USER_EXECUTOR_THREAD) }

        @Bean
        open fun cachedService(): CachedService = CachedService()
    }

    open class CachedService {
        @ReqShieldCacheable(cacheName = "boot", timeToLiveMillis = 10_000)
        open fun get(key: String): String = "value-$key"
    }

    class InMemoryReqShieldCache : ReqShieldCache<String> {
        private val store = ConcurrentHashMap<String, ReqShieldData<String>>()

        /** Name of the thread that ran the most recent write, to tell which executor performed it. */
        @Volatile
        var lastWriterThread: String? = null

        override fun get(key: String): ReqShieldData<String>? = store[key]

        override fun put(
            key: String,
            value: ReqShieldData<String>,
            timeToLiveMillis: Long,
        ) {
            lastWriterThread = Thread.currentThread().name
            store[key] = value
        }

        override fun evict(key: String): Boolean = store.remove(key) != null
    }

    companion object {
        private const val WEB_MVC_AUTO_CONFIGURATION =
            "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration"

        private const val USER_EXECUTOR_THREAD = "user-executor"
    }
}
