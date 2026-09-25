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
import com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.cache.GlobalLockSupport
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.DisposableBean
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.cache.interceptor.SimpleKeyGenerator
import org.springframework.context.expression.MethodBasedEvaluationContext
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.expression.EvaluationContext
import org.springframework.expression.Expression
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.util.ClassUtils
import org.springframework.util.StringUtils
import org.springframework.util.function.SingletonSupplier
import reactor.core.publisher.Mono
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation

@Aspect
open class ReqShieldAspect<T>(
    private val asyncCache: AsyncCache<T>,
) : BeanFactoryAware,
    DisposableBean {
    private lateinit var beanFactory: BeanFactory

    /** Set only when no `reqShieldCoroutineScope` bean exists, so [destroy] never cancels an application's scope. */
    internal var ownedScope: CoroutineScope? = null
        private set

    /**
     * Runs the fire-and-forget cache writes of every ReqShield this aspect creates: the application's bean named
     * `reqShieldCoroutineScope` when there is one, otherwise a scope owned by this aspect.
     *
     * The library registers no bean of its own, so an application bean of that name replaces the default instead of
     * clashing with it. Resolved in [setBeanFactory], at startup, so that a bean of that name which is not a
     * `CoroutineScope` fails the context refresh instead of being ignored.
     */
    private lateinit var scope: CoroutineScope

    /**
     * Spring 6.1+ drops the trailing Continuation of a suspend function itself, in both SimpleKeyGenerator
     * and MethodBasedEvaluationContext, so the aspect must pass the raw arguments there and filter them itself
     * only on older versions. Detected by a class added in 6.1 instead of SpringVersion, which is null when the
     * jar manifest lacks Implementation-Version (e.g. shaded jars): guessing wrong there would drop the real
     * last argument from the cache key and make different calls share one entry.
     */
    private val springDropsContinuationArgument =
        ClassUtils.isPresent("org.springframework.aop.framework.CoroutinesUtils", ReqShieldAspect::class.java.classLoader)

    private val spelParser = SpelExpressionParser()
    private val parameterNameDiscoverer = DefaultParameterNameDiscoverer()
    private var defaultKeyGenerator = SingletonSupplier.of<KeyGenerator> { SimpleKeyGenerator() }

    /** Global locking is opt-in: only `isLocalLock = false` needs the cache to support it. */
    private val lockSupport = asyncCache as? GlobalLockSupport

    private val keyGeneratorMap = ConcurrentHashMap<String, KeyGenerator>()

    /** Parsing a SpEL expression is expensive, so each annotation key is parsed only once. */
    private val expressionMap = ConcurrentHashMap<String, Expression>()

    /** One ReqShield per annotated method: the cache key varies per call, the configuration does not. */
    internal val reqShieldMap = ConcurrentHashMap<Method, ReqShield<T>>()

    @Around("@annotation(com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheable)")
    fun aroundReqShieldCacheable(joinPoint: ProceedingJoinPoint): Any? {
        requireSuspendTarget(joinPoint, "ReqShieldCacheable")

        return joinPoint.runCoroutine {
            val annotation = getCacheableAnnotation(joinPoint)
            val reqShield = getOrCreateReqShield(joinPoint)
            val cacheKey = getCacheableCacheKey(joinPoint)

            reqShield
                .getAndSetReqShieldData(
                    cacheKey,
                    {
                        joinPoint.proceedCoroutine().let { rtn ->
                            // A suspend function may still declare Mono as its return type.
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
    }

    @Around("@annotation(com.linecorp.cse.reqshield.spring.webflux.kotlin.coroutine.annotation.ReqShieldCacheEvict)")
    fun aroundReqShieldCacheEvict(joinPoint: ProceedingJoinPoint): Any? {
        requireSuspendTarget(joinPoint, "ReqShieldCacheEvict")

        return joinPoint.runCoroutine {
            val cacheKey = getCacheEvictCacheKey(joinPoint)
            // Evict after the method succeeded, like Spring's @CacheEvict default: a failing method
            // leaves the cache untouched, and a failing eviction is reported to the caller.
            val result =
                joinPoint.proceedCoroutine().let { rtn ->
                    // Spring 6.1+ adapts a suspend target to a cold Mono inside AOP.
                    if (rtn is Mono<*>) {
                        rtn.awaitSingleOrNull()
                    } else {
                        rtn
                    }
                }
            asyncCache.evict(cacheKey)
            result
        }
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

    fun getCacheEvictAnnotation(joinPoint: ProceedingJoinPoint): ReqShieldCacheEvict =
        AnnotationUtils.getAnnotation(getTargetMethod(joinPoint), ReqShieldCacheEvict::class.java)
            ?: throw IllegalArgumentException("ReqShieldCacheEvict annotation is required")

    internal fun getCacheableCacheKey(joinPoint: ProceedingJoinPoint): String =
        getCacheableAnnotation(joinPoint).let {
            namespacedCacheKey(it.cacheName, it.key, it.keyGenerator, joinPoint)
        }

    internal fun getCacheEvictCacheKey(joinPoint: ProceedingJoinPoint): String =
        getCacheEvictAnnotation(joinPoint).let {
            namespacedCacheKey(it.cacheName, it.key, it.keyGenerator, joinPoint)
        }

    /**
     * The cache name is part of the key so that two annotations resolving the same key in different
     * caches cannot share a cache entry - nor, since ReqShield locks on this key, a lock.
     */
    private fun namespacedCacheKey(
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
        val args =
            if (springDropsContinuationArgument) {
                joinPoint.args
            } else {
                joinPoint.args.filter { it !is Continuation<*> }.toTypedArray()
            }

        val context: EvaluationContext =
            MethodBasedEvaluationContext(joinPoint.target, method, args, parameterNameDiscoverer)

        val key =
            if (StringUtils.hasText(annotationCacheKey)) {
                val expression = expressionMap.computeIfAbsent(annotationCacheKey) { spelParser.parseExpression(it) }
                expression.getValue(context, String::class.java)
            } else {
                val keyGenerator = getOrCreateKeyGenerator(annotationCacheKeyGenerator)
                keyGenerator.generate(joinPoint.target, method, *args).toString()
            }

        require(!key.isNullOrBlank()) {
            "Null/blank key for method=${method.declaringClass.name}.${method.name} " +
                "args=${args.joinToString(prefix = "[", postfix = "]") {
                    it?.let {
                            arg ->
                        "${arg::class.simpleName}@${arg.hashCode().toString(16)}"
                    } ?: "null"
                }}"
        }

        return key
    }

    /**
     * [runCoroutine] hands the block to the join point's own continuation, so a non-suspend target
     * would fail later with an opaque cast error. Reject it here instead.
     */
    private fun requireSuspendTarget(
        joinPoint: ProceedingJoinPoint,
        annotationName: String,
    ) {
        require(joinPoint.args.lastOrNull() is Continuation<*>) {
            val method = getTargetMethod(joinPoint)
            "@$annotationName in the coroutine module requires a suspend function: " +
                "${method.declaringClass.name}.${method.name}"
        }
    }

    private fun getOrCreateReqShield(joinPoint: ProceedingJoinPoint): ReqShield<T> =
        reqShieldMap.computeIfAbsent(getTargetMethod(joinPoint)) {
            createReqShield(joinPoint)
        }

    private fun createReqShield(joinPoint: ProceedingJoinPoint): ReqShield<T> {
        val annotation = getCacheableAnnotation(joinPoint)
        val method = getTargetMethod(joinPoint)

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
                globalLockFunction = lockSupport?.let { support -> { key, token, ttl -> support.globalLock(key, token, ttl) } },
                globalUnLockFunction = lockSupport?.let { support -> { key, token -> support.globalUnLock(key, token) } },
                isLocalLock = annotation.isLocalLock,
                lockTimeoutMillis = annotation.lockTimeoutMillis,
                decisionForUpdate = annotation.decisionForUpdate,
                maxAttemptGetCache = annotation.maxAttemptGetCache,
                reqShieldWorkMode = annotation.reqShieldWorkMode,
                scope = scope,
            )

        return ReqShield(reqShieldConfiguration)
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
        scope =
            if (beanFactory.containsBean(SCOPE_BEAN_NAME)) {
                // Throws BeanNotOfRequiredTypeException for a bean of another type
                beanFactory.getBean(SCOPE_BEAN_NAME, CoroutineScope::class.java)
            } else {
                createOwnedScope().also { ownedScope = it }
            }
    }

    /** Closing the application context cancels the owned scope and with it every pending cache write. */
    override fun destroy() {
        ownedScope?.cancel()
    }

    /**
     * SupervisorJob keeps one failed write from cancelling the others, and the exception handler is the last-resort
     * backstop for anything the write path did not already log. The name tells its writes apart from those of the
     * core default scope, which also runs on Dispatchers.IO.
     */
    private fun createOwnedScope(): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName(OWNED_SCOPE_NAME) +
                CoroutineExceptionHandler { _, e ->
                    log.error("[Req-Shield] background task failed", e)
                },
        )

    companion object {
        private val log = LoggerFactory.getLogger(ReqShieldAspect::class.java)

        /** Name of the optional application bean that replaces the scope owned by the aspect. */
        internal const val SCOPE_BEAN_NAME = "reqShieldCoroutineScope"

        internal const val OWNED_SCOPE_NAME = "req-shield-aspect"
    }
}
