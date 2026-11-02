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

dependencies {
    implementation(project(":core-spring"))

    implementation(rootProject.libs.spring.boot.starter.webmvc)
    implementation(rootProject.libs.spring.boot.starter.cache)
    implementation(rootProject.libs.spring.boot.starter.data.redis)
    implementation(rootProject.libs.spring.boot.starter.aspectj)
    implementation(rootProject.libs.jackson.module.kotlin)
    // Spring Boot 4 brings Jackson 3; RedisConfiguration still builds its Jackson 2 ObjectMapper with these modules.
    implementation(rootProject.libs.jackson.datatype.jsr310)
    implementation(rootProject.libs.jackson.datatype.jdk8)

    testImplementation(testFixtures(project(":support")))
    testImplementation(rootProject.libs.spring.boot.starter.test)
}
