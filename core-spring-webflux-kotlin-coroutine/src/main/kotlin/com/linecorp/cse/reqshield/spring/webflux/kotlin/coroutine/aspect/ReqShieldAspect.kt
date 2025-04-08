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

package com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.aspect

import com.linecorp.cse.reqshield.kotlin.coroutine.ReqShield
import com.linecorp.cse.reqshield.kotlin.coroutine.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.AsyncCache
import kotlinx.coroutines.reactor.awaitSingleOrNull
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
import org.springframework.core.SpringVersion
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
import kotlin.coroutines.Continuation

@Aspect
@Component
class ReqShieldAspect<T>(
    private val asyncCache: AsyncCache<T>,
) : BeanFactoryAware {
    private lateinit var beanFactory: BeanFactory
    private val springVersion = SpringVersion.getVersion()
    private val spelParser = SpelExpressionParser()
    private var defaultKeyGenerator = SingletonSupplier.of<KeyGenerator> { SimpleKeyGenerator() }

    private val keyGeneratorMap = ConcurrentHashMap<String, KeyGenerator>()
    internal val reqShieldMap = ConcurrentHashMap<String, ReqShield<T>>()

    @Around("execution(@com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.* * *(.., kotlin.coroutines.Continuation))")
    fun aroundTargetCacheable(joinPoint: ProceedingJoinPoint): Any? {
        return joinPoint.runCoroutine {
            getTargetMethod(joinPoint).annotations.forEach { annotation ->
                when (annotation) {
                    is ReqShieldCacheable -> {
                        val reqShield = getOrCreateReqShield(joinPoint)
                        val cacheKey = getCacheableCacheKey(joinPoint)

                        return@runCoroutine reqShield
                            .getAndSetReqShieldData(
                                cacheKey,
                                {
                                    joinPoint.proceedCoroutine().let { rtn ->
                                        if (rtn is Mono<*>) {
                                            rtn.awaitSingleOrNull()?.let { it as T }
                                        } else {
                                            rtn?.let { it as T }
                                        }
                                    }
                                },
                                annotation.timeToLiveMillis,
                            ).value
                    }

                    is ReqShieldCacheEvict -> {
                        val cacheKey = getCacheEvictCacheKey(joinPoint)
                        return@runCoroutine asyncCache.evict(cacheKey)
                    }
                }
            }
        }
    }

    internal fun getTargetMethod(joinPoint: ProceedingJoinPoint): Method = (joinPoint.signature as MethodSignature).method

    internal fun getCacheableAnnotation(joinPoint: ProceedingJoinPoint): ReqShieldCacheable =
        AnnotationUtils.getAnnotation(getTargetMethod(joinPoint), ReqShieldCacheable::class.java)
            ?: throw IllegalArgumentException("ReqShieldCacheable annotation is required")

    fun getCacheEvictAnnotation(joinPoint: ProceedingJoinPoint): ReqShieldCacheEvict =
        AnnotationUtils.getAnnotation(getTargetMethod(joinPoint), ReqShieldCacheEvict::class.java)
            ?: throw IllegalArgumentException("ReqShieldCacheEvict annotation is required")

    internal fun getCacheableCacheKey(joinPoint: ProceedingJoinPoint): String {
        val annotation = getCacheableAnnotation(joinPoint)
        validateCacheKey(annotation.key, annotation.keyGenerator)

        return getCacheKeyOrDefault(annotation.key, annotation.keyGenerator, joinPoint)
    }

    internal fun getCacheEvictCacheKey(joinPoint: ProceedingJoinPoint): String {
        val annotation = getCacheEvictAnnotation(joinPoint)
        validateCacheKey(annotation.key, annotation.keyGenerator)

        return getCacheKeyOrDefault(annotation.key, annotation.keyGenerator, joinPoint)
    }

    private fun getCacheKeyOrDefault(
        annotationCacheKey: String,
        annotationCacheKeyGenerator: String,
        joinPoint: ProceedingJoinPoint,
    ): String {
        val method = getTargetMethod(joinPoint)
        val args =
            if (isCoroutineSupportedSpringVersion()) {
                joinPoint.args
            } else {
                joinPoint.args.filter { it !is Continuation<*> }.toTypedArray()
            }

        val context: EvaluationContext =
            MethodBasedEvaluationContext(joinPoint.target, method, args, DefaultParameterNameDiscoverer())

        val key =
            if (StringUtils.hasText(annotationCacheKey)) {
                val expression: Expression = spelParser.parseExpression(annotationCacheKey)
                expression.getValue(context, String::class.java)
            } else {
                val keyGenerator = getOrCreateKeyGenerator(annotationCacheKeyGenerator)
                keyGenerator.generate(joinPoint.target, method, args).toString()
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

    private fun isCoroutineSupportedSpringVersion(): Boolean {
        val version = springVersion ?: return false
        val parts = version.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false

        return major > 6 || (major == 6 && minor >= 1)
    }

    private fun generateReqShieldKey(joinPoint: ProceedingJoinPoint): String =
        "${getCacheableAnnotation(joinPoint).cacheName}-${getCacheableCacheKey(joinPoint)}"

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }
}
