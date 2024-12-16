package com.linecorp.cse.reqshield.spring3.webflux.example.service

import com.linecorp.cse.reqshield.reactor.ReqShield
import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring3.webflux.example.dto.Product
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

private val log = LoggerFactory.getLogger(SampleService::class.java)

@Service
class SampleService(
    val reqShield: ReqShield<Product>,
) {
    private val atomicInteger: AtomicInteger = AtomicInteger(0)

    @ReqShieldCacheable(cacheName = "product", decisionForUpdate = 80, timeToLiveMillis = 60 * 1000)
    fun getProduct(productId: String): Mono<Product> =
        Mono
            .delay(Duration.ofMillis(500))
            .then(
                Mono
                    .just(Product(productId, "product_$productId"))
                    .doOnNext {
                        log.info("find product with db request - req-shield local lock (will take 1 second)")
                    }.doFinally { atomicInteger.incrementAndGet() },
            )

    fun getProductNoAnno(productId: String): Mono<Product> =
        reqShield
            .getAndSetReqShieldData(
                "productCacheKeyWebFlux_$productId",
                {
                    Mono
                        .delay(Duration.ofMillis(500))
                        .then(
                            Mono
                                .just(Product(productId, "product_$productId"))
                                .doOnNext {
                                    log.info("find product with db request (will take 1 second)")
                                }.doFinally { atomicInteger.incrementAndGet() },
                        )
                },
                60 * 1000,
            ).mapNotNull { it.value }

    @ReqShieldCacheable(cacheName = "product", isLocalLock = false, decisionForUpdate = 80)
    fun getProductForGlobalLock(productId: String): Mono<Product> =
        Mono
            .delay(Duration.ofMillis(500))
            .then(
                Mono
                    .just(Product(productId, "product_$productId"))
                    .doOnNext {
                        log.info("find product with db request - req-shield global lock (will take 1 second)")
                    }.doFinally { atomicInteger.incrementAndGet() },
            )

    @ReqShieldCacheEvict(cacheName = "product")
    fun removeProduct(productId: String): Mono<Boolean> {
        log.info("remove product ($productId)")
        return Mono.fromCallable { true }
    }

    fun getRequestCount(): Int = atomicInteger.get()

    fun resetRequestCount() = atomicInteger.set(0)
}
