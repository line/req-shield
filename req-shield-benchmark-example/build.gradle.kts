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

plugins {
    alias(libs.plugins.kotlin.jvm)
    // No version: the root build already has this plugin on its classpath, with the Kotlin Spring plugin
    id("org.jetbrains.kotlin.plugin.allopen")
    alias(libs.plugins.jmh)
}

// JMH generates subclasses of the state classes, and Kotlin classes are final by default.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

dependencies {
    jmh(project(":core"))
    jmh(project(":core-reactor"))
    jmh(project(":core-kotlin-coroutine"))
    jmh(project(":core-spring"))
    jmh(project(":core-spring-webflux"))
    jmh(project(":core-spring-webflux-kotlin-coroutine"))
    // The library modules compile against these without exporting them
    jmh(rootProject.libs.reactor)
    jmh(rootProject.libs.kotlin.coroutine)
    jmh(rootProject.libs.kotlin.coroutine.reactor)
    jmh(rootProject.libs.kotlin.coroutine.reactive)
    // The Redis client of the scenario benchmarks with -p store=redis
    jmh(rootProject.libs.lettuce)
    jmh(rootProject.libs.spring.context)
    jmh(rootProject.libs.aspectj)
    // Spring reads the parameter names of Kotlin methods for SpEL keys through kotlin-reflect
    jmh(kotlin("reflect"))
    // The library logs through slf4j. A no-op backend keeps log I/O out of the measurements.
    jmh(rootProject.libs.slf4j.nop)
}

jmh {
    jmhVersion.set(
        rootProject.libs.versions.jmh
            .get(),
    )
}
