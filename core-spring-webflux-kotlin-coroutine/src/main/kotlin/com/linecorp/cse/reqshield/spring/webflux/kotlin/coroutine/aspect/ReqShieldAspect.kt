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
import com.linecorp.cse.reqshield.support.utils.cacheKeyOfArguments
import com.linecorp.cse.reqshield.support.utils.cacheKeyOfValue
import com.linecorp.cse.reqshield.support.utils.requireOwnToString
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
import org.springframework.context.expression.MethodBasedEvaluationContext
import org.springframework.core.DefaultParameterNameDiscoverer
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.core.convert.TypeDescriptor
import org.springframework.expression.EvaluationContext
import org.springframework.expression.Expression
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.util.StringUtils
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

    private val spelParser = SpelExpressionParser()
    private val parameterNameDiscoverer = DefaultParameterNameDiscoverer()

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
        // Spring 6.1+ (the supported minimum is 6.2) drops the trailing Continuation of a suspend function itself
        // in MethodBasedEvaluationContext, so the raw arguments are passed on. The default key is built here, so the
        // Continuation is dropped here. A keyGenerator bean still receives the raw arguments, Continuation included.
        val args = joinPoint.args

        val context: EvaluationContext =
            MethodBasedEvaluationContext(joinPoint.target, method, args, parameterNameDiscoverer)

        val key =
            if (StringUtils.hasText(annotationCacheKey)) {
                val expression = expressionMap.computeIfAbsent(annotationCacheKey) { spelParser.parseExpression(it) }
                evaluateKeyExpression(expression, context)
            } else if (StringUtils.hasText(annotationCacheKeyGenerator)) {
                generateKey(annotationCacheKeyGenerator, joinPoint, method)
            } else {
                cacheKeyOfArguments(joinPoint.coroutineArgs)
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

    /**
     * Evaluates the SpEL key. A text is used as is, and an array, a collection or a map is written by content. Any
     * other result becomes text the way Spring converts it (an enum by its name), or by its `toString()` when Spring
     * has no converter for it. An object whose class does not override `toString()` is rejected first: its text would
     * be the class name and the hash code.
     */
    private fun evaluateKeyExpression(
        expression: Expression,
        context: EvaluationContext,
    ): String? {
        val value = expression.getValue(context) ?: return null
        if (value is String) return value
        // Spring would join the elements with a bare comma, so an element that holds one would shift into the next
        if (value.javaClass.isArray || value is Collection<*> || value is Map<*, *>) return cacheKeyOfValue(value)
        requireOwnToString(value)

        val valueType = TypeDescriptor.forObject(value)
        val textType = TypeDescriptor.valueOf(String::class.java)
        // Spring has no converter to String for a class without a String constructor or factory, a data class
        // for example. Such a value is its own toString(), which the check above has just required.
        if (!context.typeConverter.canConvert(valueType, textType)) return value.toString()

        return context.typeConverter.convertValue(value, valueType, textType) as String?
    }

    /** The named [KeyGenerator] bean decides the key; its result must describe itself, like any other key object. */
    private fun generateKey(
        keyGeneratorBeanName: String,
        joinPoint: ProceedingJoinPoint,
        method: Method,
    ): String =
        keyGeneratorMap
            .computeIfAbsent(keyGeneratorBeanName) { beanFactory.getBean(it, KeyGenerator::class.java) }
            .generate(joinPoint.target, method, *joinPoint.args)
            .also { requireOwnToString(it) }
            .toString()

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
