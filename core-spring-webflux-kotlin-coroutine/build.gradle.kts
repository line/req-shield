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
}

dependencies {
    api(project(":core-kotlin-coroutine"))
    compileOnly(rootProject.libs.spring.context)
    compileOnly(rootProject.libs.aspectj)
    compileOnly(rootProject.libs.kotlin.coroutine)
    compileOnly(rootProject.libs.kotlin.coroutine.jvm)
    compileOnly(rootProject.libs.kotlin.coroutine.jdk8)
    compileOnly(rootProject.libs.kotlin.coroutine.reactor)

    testImplementation(testFixtures(project(":support")))
    testImplementation(project(":core-kotlin-coroutine"))
    testImplementation(platform(rootProject.libs.junit.bom))
    testImplementation(rootProject.libs.junit)
    testImplementation(rootProject.libs.kotlin.coroutine.test)
    testImplementation(rootProject.libs.kotlin.coroutine.jvm)
    testImplementation(rootProject.libs.spring.context)
    testImplementation(rootProject.libs.aspectj)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}
