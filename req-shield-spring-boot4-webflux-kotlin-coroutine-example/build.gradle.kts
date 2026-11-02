/*
 *  Copyright 2026 LY Corporation
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

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot4)
    alias(libs.plugins.spring.dependency.management)
}

// Keep the Testcontainers version that the shared test fixtures (:support) expose. Spring Boot 4 manages
// Testcontainers 2.x, whose artifact IDs differ from the 1.x ones the fixtures use.
extra["testcontainers.version"] = libs.versions.testcontainers.get()

group = "com.linecorp.cse.reqshield"
version = "1.0.0"

dependencies {
    implementation(project(":core-spring-webflux-kotlin-coroutine"))

    implementation(rootProject.libs.spring.boot.starter.webflux)
    implementation(rootProject.libs.spring.boot.starter.cache)
    implementation(rootProject.libs.spring.boot.starter.data.redis)
    implementation(rootProject.libs.spring.boot.starter.data.redis.reactive)
    implementation(rootProject.libs.spring.boot.starter.aspectj)
    implementation(rootProject.libs.reactor.kotlin.extensions)
    implementation(rootProject.libs.jackson.module.kotlin)
    // Spring Boot 4 brings Jackson 3; RedisConfiguration still builds its Jackson 2 ObjectMapper with these modules.
    implementation(rootProject.libs.jackson.datatype.jsr310)
    implementation(rootProject.libs.jackson.datatype.jdk8)
    implementation(rootProject.libs.kotlin.stdlib.jdk8)
    implementation(rootProject.libs.kotlin.coroutine)
    implementation(rootProject.libs.kotlin.coroutine.jvm)
    implementation(rootProject.libs.kotlin.coroutine.jdk8)
    implementation(rootProject.libs.kotlin.coroutine.reactor)
    implementation(rootProject.libs.kotlin.coroutine.reactive)
    annotationProcessor(rootProject.libs.spring.boot.configuration.processor)

    testImplementation(testFixtures(project(":support")))
    testImplementation(rootProject.libs.spring.boot.starter.test)
    testImplementation(rootProject.libs.kotlin.coroutine.test)
}
