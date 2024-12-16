package com.linecorp.cse.reqshield.spring3.mvc.example.service

import com.linecorp.cse.reqshield.ReqShield
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring3.mvc.example.dto.Product
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(ReqShieldGetProductService::class.java)

@Service
class ReqShieldGetProductService(
    val reqShield: ReqShield<Product>,
) {
    @ReqShieldCacheable(cacheName = "product", decisionForUpdate = 80, timeToLiveMillis = 60 * 1000)
    fun getProduct(productId: String): Product {
        log.info("get product with 3s delay (Simulate db request) - with local lock / productId : $productId")

        Thread.sleep(500)

        return Product(productId, "product_$productId")
    }

    fun getProductNoAnnotation(productId: String): Product? {
        val returnValue =
            reqShield
                .getAndSetReqShieldData(
                    "productCacheKey",
                    {
                        Thread.sleep(500)
                        log.info("get product with 3s delay (Simulate db request) / productId : $productId")
                        Product(productId, "product_$productId")
                    },
                    60 * 1000,
                ).value

        return returnValue
    }

    @ReqShieldCacheable(
        cacheName = "product",
        isLocalLock = false,
        decisionForUpdate = 80,
        timeToLiveMillis = 60 * 1000,
    )
    fun getProductForGlobalLock(productId: String): Product {
        log.info("get product with 3s delay (Simulate db request) - with global lock  / productId : $productId")

        Thread.sleep(500)

        return Product(productId, "product_$productId")
    }

    @ReqShieldCacheEvict(cacheName = "product")
    fun removeProduct(productId: String) {
        log.info("remove product ($productId)")
    }
}
