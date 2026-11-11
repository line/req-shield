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

package com.linecorp.cse.reqshield.spring.aspect

import com.linecorp.cse.reqshield.ReqShield
import com.linecorp.cse.reqshield.config.ReqShieldConfiguration
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheEvict
import com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable
import com.linecorp.cse.reqshield.spring.cache.GlobalLockSupport
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import com.linecorp.cse.reqshield.support.utils.cacheKeyOfArguments
import com.linecorp.cse.reqshield.support.utils.cacheKeyOfValue
import com.linecorp.cse.reqshield.support.utils.requireOwnToString
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
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
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@Aspect
class ReqShieldAspect<T>(
    private val reqShieldCache: ReqShieldCache<T>,
) : BeanFactoryAware,
    DisposableBean {
    private lateinit var beanFactory: BeanFactory

    /** Set only when no `reqShieldExecutor` bean exists, so [destroy] never shuts down an application's pool. */
    internal var ownedExecutor: ExecutorService? = null
        private set

    /**
     * Runs the background refreshes of every ReqShield this aspect creates: the application's bean named
     * `reqShieldExecutor` when there is one, otherwise a pool owned by this aspect.
     *
     * The library deliberately registers no `Executor` bean of its own. Spring Boot backs off its
     * `applicationTaskExecutor` when another `Executor` bean exists, so a library bean could silently change
     * the executor behind `@Async` in applications that merely have req-shield on the classpath.
     * Resolved in [setBeanFactory], at startup, so that a bean of that name which is not an `Executor` fails the
     * context refresh instead of being ignored.
     */
    private lateinit var executor: Executor

    private val spelParser = SpelExpressionParser()
    private val parameterNameDiscoverer = DefaultParameterNameDiscoverer()

    /** Global locking is only available when the cache implementation opts in to it. */
    private val lockSupport = reqShieldCache as? GlobalLockSupport

    private val keyGeneratorMap = ConcurrentHashMap<String, KeyGenerator>()
    private val expressionMap = ConcurrentHashMap<String, Expression>()

    /** A ReqShield is configured by the annotation alone, so one instance per annotated method is enough. */
    internal val reqShieldMap = ConcurrentHashMap<Method, ReqShield<T>>()

    @Around("@annotation(com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable)")
    fun aroundReqShieldCacheable(joinPoint: ProceedingJoinPoint): Any? {
        val annotation = getCacheableAnnotation(joinPoint)
        val cacheKey = getCacheableCacheKey(joinPoint)
        val reqShield = getOrCreateReqShield(joinPoint)

        return reqShield
            .getAndSetReqShieldData(
                cacheKey,
                { joinPoint.proceed() as? T },
                annotation.timeToLiveMillis,
            ).value
    }

    @Around("@annotation(com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheEvict)")
    fun aroundReqShieldCacheEvict(joinPoint: ProceedingJoinPoint): Any? {
        val cacheKey = getCacheEvictCacheKey(joinPoint)

        // Same default as Spring's @CacheEvict: a method that fails leaves the cache untouched
        val result = joinPoint.proceed()
        reqShieldCache.evict(cacheKey)

        return result
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
                "requires the ReqShieldCache bean to implement GlobalLockSupport"
        }

        val reqShieldConfiguration =
            ReqShieldConfiguration(
                setCacheFunction = { key, reqShieldData, timeToLiveMillis ->
                    reqShieldCache.put(key, reqShieldData, timeToLiveMillis)
                    true
                },
                getCacheFunction = { key ->
                    reqShieldCache.get(key)
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
                executor = executor,
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
    internal fun getTargetMethod(joinPoint: ProceedingJoinPoint): Method =
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
                evaluateKeyExpression(getOrParseExpression(annotationCacheKey), context)
            } else if (StringUtils.hasText(annotationCacheKeyGenerator)) {
                generateKey(annotationCacheKeyGenerator, joinPoint, method)
            } else {
                cacheKeyOfArguments(joinPoint.args)
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
        executor =
            if (beanFactory.containsBean(EXECUTOR_BEAN_NAME)) {
                // Throws BeanNotOfRequiredTypeException for a bean of another type
                beanFactory.getBean(EXECUTOR_BEAN_NAME, Executor::class.java)
            } else {
                createOwnedExecutor().also { ownedExecutor = it }
            }
    }

    override fun destroy() {
        ownedExecutor?.shutdown()
    }

    private fun createOwnedExecutor(): ExecutorService {
        val threadCounter = AtomicLong(0)

        // Daemon threads, so a pending refresh can never block JVM shutdown.
        // Named apart from the core default pool so a thread dump shows which one ran a refresh.
        return Executors.newScheduledThreadPool(
            maxOf(2, Runtime.getRuntime().availableProcessors() * 2),
        ) { runnable ->
            Thread(runnable, "req-shield-aspect-executor-${threadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }

    companion object {
        /** Name of the optional application bean that replaces the pool owned by the aspect. */
        internal const val EXECUTOR_BEAN_NAME = "reqShieldExecutor"
    }
}
