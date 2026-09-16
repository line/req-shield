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
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.annotation.Qualifier
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
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService

@Aspect
class ReqShieldAspect<T>(
    private val reqShieldCache: ReqShieldCache<T>,
    @Qualifier("reqShieldExecutor") private val executor: ScheduledExecutorService,
) : BeanFactoryAware {
    private lateinit var beanFactory: BeanFactory
    private val spelParser = SpelExpressionParser()
    private val parameterNameDiscoverer = DefaultParameterNameDiscoverer()
    private val defaultKeyGenerator = SingletonSupplier.of<KeyGenerator> { SimpleKeyGenerator() }

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

    internal fun getTargetMethod(joinPoint: ProceedingJoinPoint): Method = (joinPoint.signature as MethodSignature).method

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
                keyGenerator.generate(joinPoint.target, method, joinPoint.args).toString()
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
    }
}
