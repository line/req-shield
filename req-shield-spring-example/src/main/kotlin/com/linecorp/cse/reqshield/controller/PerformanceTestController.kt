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

package com.linecorp.cse.reqshield.controller

import com.linecorp.cse.reqshield.service.CacheableGetProductService
import com.linecorp.cse.reqshield.service.ReqShieldGetProductService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/performance")
class PerformanceTestController(
    val reqShieldGetProductService: ReqShieldGetProductService,
    val cacheableGetProductService: CacheableGetProductService,
) {
    @GetMapping("/req-shield/get-product")
    fun reqShieldGetProduct(
        @RequestParam productId: String,
    ) = reqShieldGetProductService.getProduct(productId)

    @GetMapping("/req-shield/no-anno/get-product")
    fun reqShieldGetProductNoAnno(
        @RequestParam productId: String,
    ) = reqShieldGetProductService.getProductNoAnnotation(productId)

    @GetMapping("/req-shield/get-product/global-lock")
    fun reqShieldGetProductForGlobalLock(
        @RequestParam productId: String,
    ) = reqShieldGetProductService.getProductForGlobalLock(productId)

    @GetMapping("/cacheable/get-product")
    fun cacheableGetProduct(
        @RequestParam productId: String,
    ) = cacheableGetProductService.getProduct(productId)
}
