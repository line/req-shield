package com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example

import com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example.service.SampleService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SampleController(val sampleService: SampleService) {
    @GetMapping("/test")
    suspend fun test(productId: String) = sampleService.getProduct(productId)

    @GetMapping("/no-anno/test")
    suspend fun testNoAnno(productId: String) = sampleService.getProductNoAnno(productId)
}
