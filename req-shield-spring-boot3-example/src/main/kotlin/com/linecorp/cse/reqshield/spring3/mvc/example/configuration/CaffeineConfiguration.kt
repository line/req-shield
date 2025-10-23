package com.linecorp.cse.reqshield.spring3.mvc.example.configuration

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.linecorp.cse.reqshield.spring3.mvc.example.dto.Member
import com.linecorp.cse.reqshield.spring3.mvc.example.dto.Product
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
@EnableCaching
class CaffeineConfiguration {

    @Bean
    fun cacheManager(): CacheManager {
        val caffeineCacheManager = CaffeineCacheManager()

        caffeineCacheManager.setCacheNames(listOf("memberCache", "productCache"))
        caffeineCacheManager.setCacheLoader { name ->
            val cache = when (name) {
                "memberCache" -> Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(Duration.ofSeconds(10))
                    .build<String, ReqShieldData<Member>>()

                "productCache" -> Caffeine.newBuilder()
                    .maximumSize(5000)
                    .expireAfterWrite(Duration.ofSeconds(30))
                    .build<String, ReqShieldData<Product>>()

                else -> Caffeine.newBuilder()
                    .maximumSize(100)
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .build<Any, Any>()
            }
            cache
        }

        return caffeineCacheManager
    }
}

