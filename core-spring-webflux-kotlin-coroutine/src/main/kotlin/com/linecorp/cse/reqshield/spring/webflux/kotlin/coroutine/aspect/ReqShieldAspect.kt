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
import org.springframework.cache.interceptor.SimpleKeyGenerator
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils
import reactor.core.publisher.Mono
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation

@Aspect
@Component
class ReqShieldAspect<T>(
    private val asyncCache: AsyncCache<T>,
) {
    private val keyGenerator = SimpleKeyGenerator()
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

    fun getCacheEvictAnnotation(joinPoint: ProceedingJoinPoint): ReqShieldCacheEvict =
        AnnotationUtils.getAnnotation(getTargetMethod(joinPoint), ReqShieldCacheEvict::class.java)

    internal fun getCacheableCacheKey(joinPoint: ProceedingJoinPoint): String {
        val annotation = getCacheableAnnotation(joinPoint)
        return getCacheKeyOrDefault(annotation.cacheName, annotation.key, joinPoint)
    }

    internal fun getCacheEvictCacheKey(joinPoint: ProceedingJoinPoint): String {
        val annotation = getCacheEvictAnnotation(joinPoint)
        return getCacheKeyOrDefault(annotation.cacheName, annotation.key, joinPoint)
    }

    private fun getCacheKeyOrDefault(
        annotationCacheName: String,
        annotationCacheKey: String,
        joinPoint: ProceedingJoinPoint,
    ): String {
        return annotationCacheKey.ifBlank {
            run {
                val params =
                    StringUtils.arrayToDelimitedString(
                        joinPoint.args
                            .filter { it !is Continuation<*> }
                            .toTypedArray(),
                        "_",
                    )
                return "$annotationCacheName-[$annotationCacheKey$params]"
            }
        }
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
            )

        return ReqShield(reqShieldConfiguration)
    }

    private fun generateReqShieldKey(joinPoint: ProceedingJoinPoint): String =
        "${getCacheableAnnotation(joinPoint).cacheName}-${getCacheableAnnotation(joinPoint).key}"
}
