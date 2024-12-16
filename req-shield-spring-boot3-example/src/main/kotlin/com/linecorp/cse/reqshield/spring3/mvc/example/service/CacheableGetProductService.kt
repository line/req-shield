package com.linecorp.cse.reqshield.spring3.mvc.example.service

import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.spring3.mvc.example.dto.Product
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(CacheableGetProductService::class.java)

@Service
class CacheableGetProductService(
    private val reqShieldCache: ReqShieldCache<Product>,
) {
    @Cacheable(cacheNames = ["product"])
    fun getProduct(productId: String): Product {
        log.info("get product with 3s delay (Simulate db request) / productId : $productId")

        Thread.sleep(3000)

        return Product(productId, "product_$productId")
    }

    @ReqShieldCacheEvict(cacheName = "product")
    fun removeProduct(productId: String) {
        log.info("remove product ($productId)")
    }
}
