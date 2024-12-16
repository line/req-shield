package com.linecorp.cse.reqshield.spring3.mvc.example.service

import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring3.mvc.example.dto.Product
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

private val log = LoggerFactory.getLogger(SampleService::class.java)

@Service
class SampleService {
    private val atomicInteger: AtomicInteger = AtomicInteger(0)

    @ReqShieldCacheable(cacheName = "product", decisionForUpdate = 90, timeToLiveMillis = 60 * 1000)
    fun getProduct(productId: String): Product {
        log.info("find product with db request - req-shield local lock (will take 1 second)")
        Thread.sleep(500)

        atomicInteger.incrementAndGet()
        return Product(productId, "product_$productId")
    }

    @ReqShieldCacheable(cacheName = "product", isLocalLock = false, decisionForUpdate = 90)
    fun getProductForGlobalLock(productId: String): Product {
        log.info("find product with db request - req-shield global lock  (will take 1 second)")
        Thread.sleep(500)

        atomicInteger.incrementAndGet()
        return Product(productId, "product_$productId")
    }

    @ReqShieldCacheEvict(cacheName = "product")
    fun removeProduct(productId: String) {
        log.info("remove product ($productId)")
    }

    fun getRequestCount(): Int = atomicInteger.get()

    fun resetRequestCount() = atomicInteger.set(0)
}
