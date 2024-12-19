package com.linecorp.cse.reqshield.spring3.webflux.kotlin.coroutine.example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SpringWebfluxCoroutineApplication

fun main(args: Array<String>) {
    runApplication<SpringWebfluxCoroutineApplication>(*args)
}