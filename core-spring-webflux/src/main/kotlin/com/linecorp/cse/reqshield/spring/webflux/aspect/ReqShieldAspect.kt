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

package com.linecorp.cse.reqshield.spring.webflux.aspect

import com.linecorp.cse.reqshield.reactor.ReqShield
import com.linecorp.cse.reqshield.reactor.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.cache.interceptor.SimpleKeyGenerator
import org.springframework.context.expression.MethodBasedEvaluationContext
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.expression.EvaluationContext
import org.springframework.expression.Expression
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils
import org.springframework.util.function.SingletonSupplier
import reactor.core.publisher.Mono
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

@Aspect
@Component
class ReqShieldAspect<T>(
    private val asyncCache: AsyncCache<T>,
) : BeanFactoryAware {
    private lateinit var beanFactory: BeanFactory
    private val spelParser = SpelExpressionParser()
    private var defaultKeyGenerator = SingletonSupplier.of<KeyGenerator> { SimpleKeyGenerator() }

    private val keyGeneratorMap = ConcurrentHashMap<String, KeyGenerator>()
    internal val reqShieldMap = ConcurrentHashMap<String, ReqShield<T>>()

    @Around("@annotation(com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheable)")
    fun aroundTargetCacheable(joinPoint: ProceedingJoinPoint): Mono<Any?> {
        val annotation = getCacheableAnnotation(joinPoint)
        val reqShield = getOrCreateReqShield(joinPoint)
        val cacheKey = getCacheableCacheKey(joinPoint)

        return reqShield
            .getAndSetReqShieldData(
                cacheKey,
                {
                    joinPoint.proceed() as Mono<T?>
                },
                annotation.timeToLiveMillis,
            ).mapNotNull { it.value }
    }

    @Around("@annotation(com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheEvict)")
    fun aroundReqShieldCacheEvict(joinPoint: ProceedingJoinPoint): Mono<Any?> {
        val cacheKey = getCacheEvictCacheKey(joinPoint)

        return Mono
            .defer {
                asyncCache.evict(cacheKey)
            }.flatMap {
                joinPoint.proceed() as Mono<out Any?>
            }
    }

    internal fun getCacheableAnnotation(joinPoint: ProceedingJoinPoint): ReqShieldCacheable =
        AnnotationUtils.getAnnotation(getTargetMethod(joinPoint), ReqShieldCacheable::class.java)
            ?: throw IllegalArgumentException("ReqShieldCacheable annotation is required")

    internal fun getCacheableCacheKey(joinPoint: ProceedingJoinPoint): String {
        val annotation = getCacheableAnnotation(joinPoint)
        validateCacheKey(annotation.key, annotation.keyGenerator)

        return getCacheKeyOrDefault(annotation.key, annotation.keyGenerator, joinPoint)
    }

    internal fun getCacheEvictAnnotation(joinPoint: ProceedingJoinPoint): ReqShieldCacheEvict =
        AnnotationUtils.getAnnotation(getTargetMethod(joinPoint), ReqShieldCacheEvict::class.java)
            ?: throw IllegalArgumentException("ReqShieldCacheEvict annotation is required")

    internal fun getCacheEvictCacheKey(joinPoint: ProceedingJoinPoint): String {
        val annotation = getCacheEvictAnnotation(joinPoint)
        validateCacheKey(annotation.key, annotation.keyGenerator)

        return getCacheKeyOrDefault(annotation.key, annotation.keyGenerator, joinPoint)
    }

    internal fun getTargetMethod(joinPoint: ProceedingJoinPoint): Method = (joinPoint.signature as MethodSignature).method

    private fun getCacheKeyOrDefault(
        annotationCacheKey: String,
        annotationCacheKeyGenerator: String,
        joinPoint: ProceedingJoinPoint,
    ): String {
        val method = getTargetMethod(joinPoint)
        val context: EvaluationContext =
            MethodBasedEvaluationContext(joinPoint.target, method, joinPoint.args, DefaultParameterNameDiscoverer())

        val key =
            if (StringUtils.hasText(annotationCacheKey)) {
                val expression: Expression = spelParser.parseExpression(annotationCacheKey)
                expression.getValue(context, String::class.java)
            } else {
                val keyGenerator = getOrCreateKeyGenerator(annotationCacheKeyGenerator)
                keyGenerator.generate(joinPoint.target, method, joinPoint.args).toString()
            }

        require(!key.isNullOrBlank()) { "Null key returned for cache method : $method" }

        return key
    }

    private fun getOrCreateReqShield(joinPoint: ProceedingJoinPoint): ReqShield<T> =
        reqShieldMap.computeIfAbsent(generateReqShieldKey(joinPoint)) {
            createReqShield(joinPoint)
        }

    private fun createReqShield(joinPoint: ProceedingJoinPoint): ReqShield<T> {
        val annotation = getCacheableAnnotation(joinPoint)

        val reqShieldConfiguration =
            ReqShieldConfiguration(
                setCacheFunction = { key, reqShieldData, timeToLiveMillis ->
                    asyncCache.put(key, reqShieldData, timeToLiveMillis)
                },
                getCacheFunction = { key ->
                    asyncCache.get(key)
                },
                globalLockFunction = { key, timeToLiveMillis ->
                    asyncCache.globalLock(key, timeToLiveMillis)
                },
                globalUnLockFunction = { key ->
                    asyncCache.globalUnLock(key)
                },
                isLocalLock = annotation.isLocalLock,
                lockTimeoutMillis = annotation.lockTimeoutMillis,
                decisionForUpdate = annotation.decisionForUpdate,
                maxAttemptGetCache = annotation.maxAttemptGetCache,
                reqShieldWorkMode = annotation.reqShieldWorkMode,
            )

        return ReqShield(reqShieldConfiguration)
    }

    private fun validateCacheKey(
        cacheKey: String,
        cacheKeyGenerator: String,
    ) {
        if (cacheKey.isNotBlank() && cacheKeyGenerator.isNotBlank()) {
            throw IllegalArgumentException("The key and keyGenerator attributes are mutually exclusive.")
        }
    }

    private fun getOrCreateKeyGenerator(keyGeneratorBeanName: String?): KeyGenerator {
        if (keyGeneratorBeanName.isNullOrBlank()) {
            return defaultKeyGenerator.obtain()
        }

        return keyGeneratorMap.computeIfAbsent(keyGeneratorBeanName) {
            beanFactory.getBean(it, KeyGenerator::class.java)
        }
    }

    private fun generateReqShieldKey(joinPoint: ProceedingJoinPoint): String =
        "${getCacheableAnnotation(joinPoint).cacheName}-${getCacheableCacheKey(joinPoint)}"

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }
}
