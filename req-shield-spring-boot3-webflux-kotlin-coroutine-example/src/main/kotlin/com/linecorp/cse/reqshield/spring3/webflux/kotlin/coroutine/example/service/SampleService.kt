package com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example.service

import com.linecorp.cse.reqshield.kotlin.coroutine.ReqShield
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example.dto.Product
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

private val log = LoggerFactory.getLogger(SampleService::class.java)

@Service
class SampleService(
    val reqShield: ReqShield<Product>,
) {
    private val atomicInteger: AtomicInteger = AtomicInteger(0)

    @ReqShieldCacheable(cacheName = "product", decisionForUpdate = 80, timeToLiveMillis = 60 * 1000)
    suspend fun getProduct(productId: String): Product {
        log.info("find product with db request with req-shield local lock (will take 1 second)")

        delay(500)
        atomicInteger.incrementAndGet()

        return Product(productId, "product_$productId")
    }

    suspend fun getProductNoAnno(productId: String): Product? {
        val result =
            reqShield
                .getAndSetReqShieldData(
                    "productCacheKeyCoroutine_$productId",
                    {
                        delay(500)
                        log.info("find product with db request_$productId")
                        Product(productId, "product_$productId")
                    },
                    60 * 1000,
                ).value

        return result
    }

    @ReqShieldCacheable(
        cacheName = "product",
        key = "global_lock",
        isLocalLock = false,
        decisionForUpdate = 70,
        timeToLiveMillis = 60 * 1000,
    )
    suspend fun getProductForGlobalLock(productId: String): Product {
        log.info("find product with db request with req-shield global lock (will take 1 second)")

        delay(500)
        atomicInteger.incrementAndGet()

        return Product(productId, "product_$productId")
    }

    @ReqShieldCacheEvict(cacheName = "product")
    suspend fun removeProduct(productId: String) {
        log.info("remove product ($productId)")
    }

    suspend fun getRequestCount(): Int = atomicInteger.get()

    suspend fun resetRequestCount() = atomicInteger.set(0)
}
