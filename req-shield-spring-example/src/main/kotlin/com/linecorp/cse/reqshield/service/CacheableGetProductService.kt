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

import com.linecorp.cse.reqshield.dto.Product
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
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
