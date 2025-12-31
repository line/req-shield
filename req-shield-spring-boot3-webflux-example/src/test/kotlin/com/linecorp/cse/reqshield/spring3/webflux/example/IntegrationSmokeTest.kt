package com.linecorp.cse.reqshield.spring3.webflux.example

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit.jupiter.SpringExtension

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(SpringExtension::class)
class IntegrationSmokeTest {
    @Test
    fun contextLoads() {
        // just ensure context starts with Testcontainers
    }
}
