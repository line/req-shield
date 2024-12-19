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

import com.linecorp.cse.reqshield.ReqShield
import com.linecorp.cse.reqshield.dto.Product
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(ReqShieldGetProductService::class.java)

@Service
class ReqShieldGetProductService(
    val reqShield: ReqShield<Product>,
) {
    @ReqShieldCacheable(cacheName = "product", decisionForUpdate = 80, timeToLiveMillis = 60 * 1000)
    fun getProduct(productId: String): Product {
        log.info("get product with 3s delay (Simulate db request) - with local lock  / productId : $productId")

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
