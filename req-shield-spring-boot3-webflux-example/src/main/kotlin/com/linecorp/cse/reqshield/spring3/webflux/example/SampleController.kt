package com.linecorp.cse.reqshield.spring3.webflux.example

import com.linecorp.cse.reqshield.spring3.webflux.example.service.SampleService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class SampleController(val sampleService: SampleService) {
    @GetMapping("/test")
    fun test(
        @RequestParam productId: String,
    ) = sampleService.getProduct(productId)

    @GetMapping("/no-anno/test")
    fun testNoAnno(
        @RequestParam productId: String,
    ) = sampleService.getProductNoAnno(productId)
}
