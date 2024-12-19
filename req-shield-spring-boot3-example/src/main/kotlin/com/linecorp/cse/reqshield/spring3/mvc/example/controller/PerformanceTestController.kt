package com.linecorp.cse.reqshield.spring3.mvc.example.controller

import com.linecorp.cse.reqshield.spring3.mvc.example.service.CacheableGetProductService
import com.linecorp.cse.reqshield.spring3.mvc.example.service.ReqShieldGetProductService
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
