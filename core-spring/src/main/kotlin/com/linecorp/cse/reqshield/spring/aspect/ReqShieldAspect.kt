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
import com.linecorp.cse.reqshield.spring.cache.ReqShieldCache
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.stereotype.Component
import org.springframework.util.StringUtils
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

@Aspect
@Component
class ReqShieldAspect<T>(
    private val reqShieldCache: ReqShieldCache<T>,
) {
    internal val reqShieldMap = ConcurrentHashMap<String, ReqShield<T>>()

    @Around("@annotation(com.linecorp.cse.reqshield.spring.annotation.ReqShieldCacheable)")
    fun aroundReqShieldCacheable(joinPoint: ProceedingJoinPoint): Any? {
        val annotation = getCacheableAnnotation(joinPoint)
        val reqShield = getOrCreateReqShield(joinPoint)
        val cacheKey = getCacheableCacheKey(joinPoint)

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

        reqShieldCache.evict(cacheKey)

        return joinPoint.proceed()
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
                    reqShieldCache.put(key, reqShieldData, timeToLiveMillis)
                    true
                },
                getCacheFunction = { key ->
                    reqShieldCache.get(key)
                },
                globalLockFunction = { key, timeToLiveMillis ->
                    reqShieldCache.globalLock(key, timeToLiveMillis)
                },
                globalUnLockFunction = { key ->
                    reqShieldCache.globalUnLock(key)
                },
                isLocalLock = annotation.isLocalLock,
                lockTimeoutMillis = annotation.lockTimeoutMillis,
                decisionForUpdate = annotation.decisionForUpdate,
            )

        return ReqShield(reqShieldConfiguration)
    }

    internal fun getTargetMethod(joinPoint: ProceedingJoinPoint): Method = (joinPoint.signature as MethodSignature).method

    internal fun getCacheableAnnotation(joinPoint: ProceedingJoinPoint): ReqShieldCacheable =
        AnnotationUtils.getAnnotation(getTargetMethod(joinPoint), ReqShieldCacheable::class.java)
            ?: throw IllegalArgumentException("ReqShieldCacheable annotation is required")

    internal fun getCacheEvictAnnotation(joinPoint: ProceedingJoinPoint): ReqShieldCacheEvict =
        AnnotationUtils.getAnnotation(getTargetMethod(joinPoint), ReqShieldCacheEvict::class.java)
            ?: throw IllegalArgumentException("ReqShieldCacheable annotation is required")

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
                val params = StringUtils.arrayToDelimitedString(joinPoint.args, "_")

                return "$annotationCacheName-[$annotationCacheKey$params]"
            }
        }
    }

    private fun generateReqShieldKey(joinPoint: ProceedingJoinPoint): String =
        "${getCacheableAnnotation(joinPoint).cacheName}-${getCacheableAnnotation(joinPoint).key}"
}
