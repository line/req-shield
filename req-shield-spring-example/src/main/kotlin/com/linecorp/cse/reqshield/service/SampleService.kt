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

package com.linecorp.cse.reqshield.service

import com.linecorp.cse.reqshield.config.ReqShieldWorkMode
import com.linecorp.cse.reqshield.dto.Product
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable
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

    @ReqShieldCacheable(
        cacheName = "productOnlyUpdateCache",
        decisionForUpdate = 90,
        timeToLiveMillis = 60 * 1000,
        reqShieldWorkMode = ReqShieldWorkMode.ONLY_UPDATE_CACHE,
    )
    fun getProductOnlyUpdateCache(productId: String): Product {
        log.info("find product with db request - req-shield local lock only update cache (will take 1 second)")
        Thread.sleep(500)

        atomicInteger.incrementAndGet()
        return Product(productId, "product_$productId")
    }

    @ReqShieldCacheable(cacheName = "product", isLocalLock = false, decisionForUpdate = 90)
    fun getProductForGlobalLock(productId: String): Product {
        log.info("find product with db request - req-shield global lock (will take 1 second)")
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
