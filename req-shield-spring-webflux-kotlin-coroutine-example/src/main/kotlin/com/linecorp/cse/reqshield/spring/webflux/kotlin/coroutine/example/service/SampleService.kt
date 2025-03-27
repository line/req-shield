/*
 *  Copyright 2024 LY Corporation
 *
 *  LY Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.example.service

import com.linecorp.cse.reqshield.kotlin.coroutine.ReqShield
import com.linecorp.cse.reqshield.kotlin.coroutine.config.ReqShieldWorkMode
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.example.dto.Product
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

    @ReqShieldCacheable(cacheName = "product", key = "'product-' + #productId", decisionForUpdate = 80, timeToLiveMillis = 60 * 1000)
    suspend fun getProduct(productId: String): Product {
        log.info("find product with db request with req-shield local lock (will take 1 second)")

        delay(500)
        atomicInteger.incrementAndGet()

        return Product(productId, "product_$productId")
    }

    @ReqShieldCacheable(
        cacheName = "productOnlyUpdateCache",
        decisionForUpdate = 80,
        timeToLiveMillis = 60 * 1000,
        reqShieldWorkMode = ReqShieldWorkMode.ONLY_UPDATE_CACHE,
    )
    suspend fun getProductOnlyUpdateCache(productId: String): Product {
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
        key = "'product-' + #productId",
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

    @ReqShieldCacheEvict(cacheName = "product", key = "'product-' + #productId")
    suspend fun removeProduct(productId: String) {
        log.info("remove product ($productId)")
    }

    suspend fun getRequestCount(): Int = atomicInteger.get()

    suspend fun resetRequestCount() = atomicInteger.set(0)
}
