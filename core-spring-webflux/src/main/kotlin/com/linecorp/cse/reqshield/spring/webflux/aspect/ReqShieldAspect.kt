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
import com.linecorp.cse.reqshield.spring.webflux.annotation.NullHandling
import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.webflux.cache.AsyncCache
import com.linecorp.cse.reqshield.spring.webflux.cache.GlobalLockSupport
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.aop.support.AopUtils
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
import org.springframework.util.StringUtils
import org.springframework.util.function.SingletonSupplier
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

@Aspect
open class ReqShieldAspect<T>(
    private val asyncCache: AsyncCache<T>,
) : BeanFactoryAware {
    private lateinit var beanFactory: BeanFactory

    /**
     * Runs the asynchronous cache writes and the cache polling of every ReqShield this aspect creates: the
     * application's bean named `reqShieldScheduler` when there is one, otherwise Reactor's process-wide
     * [Schedulers.boundedElastic], which is shared and therefore never disposed here.
     *
     * The library registers no bean of its own, so an application bean of that name replaces the default instead of
     * clashing with it. Resolved in [setBeanFactory], at startup, so that a bean of that name which is not a
     * `Scheduler` fails the context refresh instead of being ignored.
     */
    private lateinit var scheduler: Scheduler

    private val spelParser = SpelExpressionParser()
    private val parameterNameDiscoverer = DefaultParameterNameDiscoverer()
    private val defaultKeyGenerator = SingletonSupplier.of<KeyGenerator> { SimpleKeyGenerator() }

    /** Global locking is only available when the cache implementation opts in to it. */
    private val lockSupport = asyncCache as? GlobalLockSupport

    private val keyGeneratorMap = ConcurrentHashMap<String, KeyGenerator>()
    private val expressionMap = ConcurrentHashMap<String, Expression>()

    /** A ReqShield is configured by the annotation alone, so one instance per annotated method is enough. */
    internal val reqShieldMap = ConcurrentHashMap<Method, ReqShield<T>>()

    @Around("@annotation(com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheable)")
    fun aroundTargetCacheable(joinPoint: ProceedingJoinPoint): Mono<Any?> {
        val annotation = getCacheableAnnotation(joinPoint)
        val cacheKey = getCacheableCacheKey(joinPoint)
        val reqShield = getOrCreateReqShield(joinPoint)

        // Mono.map rejects a null result, so the null decision is taken on the wrapper instead of on the value.
        val reqShieldDataMono =
            reqShield
                .getAndSetReqShieldData(
                    cacheKey,
                    {
                        joinPoint.proceed() as Mono<T?>
                    },
                    annotation.timeToLiveMillis,
                )

        return when (annotation.nullHandling) {
            NullHandling.EMIT_EMPTY ->
                reqShieldDataMono.flatMap { Mono.justOrEmpty(it.value) }
            NullHandling.ERROR ->
                reqShieldDataMono.flatMap { reqShieldData ->
                    val value = reqShieldData.value
                    if (value == null) {
                        Mono.error(IllegalStateException("ReqShieldCacheable returned null for key=$cacheKey"))
                    } else {
                        Mono.just(value)
                    }
                }
        }
    }

    @Around("@annotation(com.linecorp.cse.reqshield.spring.webflux.annotation.ReqShieldCacheEvict)")
    fun aroundReqShieldCacheEvict(joinPoint: ProceedingJoinPoint): Mono<Any?> {
        val cacheKey = getCacheEvictCacheKey(joinPoint)

        // Same default as Spring's @CacheEvict: a method that fails leaves the cache untouched.
        // An empty completion (for example Mono<Void>) is a success too, so it evicts as well.
        return Mono
            .defer { joinPoint.proceed() as Mono<Any?> }
            // A Mono never emits null, so the non-null assertion below can never fail.
            .flatMap { result -> asyncCache.evict(cacheKey).thenReturn(result!!) }
            .switchIfEmpty(Mono.defer { asyncCache.evict(cacheKey).then(Mono.empty()) })
    }

    private fun getOrCreateReqShield(joinPoint: ProceedingJoinPoint): ReqShield<T> =
        reqShieldMap.computeIfAbsent(getTargetMethod(joinPoint)) {
            createReqShield(joinPoint)
        }

    private fun createReqShield(joinPoint: ProceedingJoinPoint): ReqShield<T> {
        val method = getTargetMethod(joinPoint)
        val annotation = getCacheableAnnotation(joinPoint)

        require(annotation.isLocalLock || lockSupport != null) {
            "isLocalLock = false on ${method.declaringClass.name}.${method.name} " +
                "requires the AsyncCache bean to implement GlobalLockSupport"
        }

        val reqShieldConfiguration =
            ReqShieldConfiguration(
                setCacheFunction = { key, reqShieldData, timeToLiveMillis ->
                    asyncCache.put(key, reqShieldData, timeToLiveMillis)
                },
                getCacheFunction = { key ->
                    asyncCache.get(key)
                },
                globalLockFunction =
                    lockSupport?.let { support ->
                        { lockKey, token, timeToLiveMillis -> support.globalLock(lockKey, token, timeToLiveMillis) }
                    },
                globalUnLockFunction =
                    lockSupport?.let { support ->
                        { lockKey, token -> support.globalUnLock(lockKey, token) }
                    },
                isLocalLock = annotation.isLocalLock,
                lockTimeoutMillis = annotation.lockTimeoutMillis,
                scheduler = scheduler,
                decisionForUpdate = annotation.decisionForUpdate,
                maxAttemptGetCache = annotation.maxAttemptGetCache,
                reqShieldWorkMode = annotation.reqShieldWorkMode,
            )

        return ReqShield(reqShieldConfiguration)
    }

    /**
     * A JDK dynamic proxy reports the interface method, which carries none of the annotations, so the
     * implementation method is resolved from the target class instead.
     */
    internal open fun getTargetMethod(joinPoint: ProceedingJoinPoint): Method =
        AopUtils.getMostSpecificMethod((joinPoint.signature as MethodSignature).method, joinPoint.target?.javaClass)

    internal fun getCacheableAnnotation(joinPoint: ProceedingJoinPoint): ReqShieldCacheable =
        AnnotationUtils.getAnnotation(getTargetMethod(joinPoint), ReqShieldCacheable::class.java)
            ?: throw IllegalArgumentException("ReqShieldCacheable annotation is required")

    internal fun getCacheEvictAnnotation(joinPoint: ProceedingJoinPoint): ReqShieldCacheEvict =
        AnnotationUtils.getAnnotation(getTargetMethod(joinPoint), ReqShieldCacheEvict::class.java)
            ?: throw IllegalArgumentException("ReqShieldCacheEvict annotation is required")

    internal fun getCacheableCacheKey(joinPoint: ProceedingJoinPoint): String {
        val annotation = getCacheableAnnotation(joinPoint)

        return buildCacheKey(annotation.cacheName, annotation.key, annotation.keyGenerator, joinPoint)
    }

    internal fun getCacheEvictCacheKey(joinPoint: ProceedingJoinPoint): String {
        val annotation = getCacheEvictAnnotation(joinPoint)

        return buildCacheKey(annotation.cacheName, annotation.key, annotation.keyGenerator, joinPoint)
    }

    /**
     * Namespaces the resolved key with the cache name so that entries (and the locks derived from
     * them) of different caches cannot collide. Eviction follows the same rule so that it matches.
     */
    private fun buildCacheKey(
        cacheName: String,
        annotationCacheKey: String,
        annotationCacheKeyGenerator: String,
        joinPoint: ProceedingJoinPoint,
    ): String {
        validateCacheKey(annotationCacheKey, annotationCacheKeyGenerator)

        return "$cacheName::${getCacheKeyOrDefault(annotationCacheKey, annotationCacheKeyGenerator, joinPoint)}"
    }

    private fun getCacheKeyOrDefault(
        annotationCacheKey: String,
        annotationCacheKeyGenerator: String,
        joinPoint: ProceedingJoinPoint,
    ): String {
        val method = getTargetMethod(joinPoint)
        val context: EvaluationContext =
            MethodBasedEvaluationContext(joinPoint.target, method, joinPoint.args, parameterNameDiscoverer)

        val key =
            if (StringUtils.hasText(annotationCacheKey)) {
                getOrParseExpression(annotationCacheKey).getValue(context, String::class.java)
            } else {
                val keyGenerator = getOrCreateKeyGenerator(annotationCacheKeyGenerator)
                keyGenerator.generate(joinPoint.target, method, *joinPoint.args).toString()
            }

        require(!key.isNullOrBlank()) {
            "Null/blank key for @ReqShieldCacheable method=${method.declaringClass.name}.${method.name} " +
                "args=${joinPoint.args.joinToString(prefix = "[", postfix = "]") {
                    it?.let {
                            arg ->
                        "${arg::class.simpleName}@${arg.hashCode().toString(16)}"
                    } ?: "null"
                }}"
        }

        return key
    }

    private fun validateCacheKey(
        cacheKey: String,
        cacheKeyGenerator: String,
    ) {
        if (cacheKey.isNotBlank() && cacheKeyGenerator.isNotBlank()) {
            throw IllegalArgumentException(
                "The key and keyGenerator attributes are mutually exclusive: key='$cacheKey', keyGenerator='$cacheKeyGenerator'",
            )
        }
    }

    private fun getOrParseExpression(cacheKeyExpression: String): Expression =
        expressionMap.computeIfAbsent(cacheKeyExpression) {
            spelParser.parseExpression(it)
        }

    private fun getOrCreateKeyGenerator(keyGeneratorBeanName: String?): KeyGenerator {
        if (keyGeneratorBeanName.isNullOrBlank()) {
            return defaultKeyGenerator.obtain()
        }

        return keyGeneratorMap.computeIfAbsent(keyGeneratorBeanName) {
            beanFactory.getBean(it, KeyGenerator::class.java)
        }
    }

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
        scheduler =
            if (beanFactory.containsBean(SCHEDULER_BEAN_NAME)) {
                // Throws BeanNotOfRequiredTypeException for a bean of another type
                beanFactory.getBean(SCHEDULER_BEAN_NAME, Scheduler::class.java)
            } else {
                Schedulers.boundedElastic()
            }
    }

    companion object {
        /** Name of the optional application bean that replaces the shared boundedElastic scheduler. */
        internal const val SCHEDULER_BEAN_NAME = "reqShieldScheduler"
    }
}
