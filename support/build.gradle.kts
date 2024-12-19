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

version = "1.0.0"

plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

val testContainerVer: String by project
val spring: String by project
val springBoot: String by project

dependencies {
    testFixturesImplementation("org.testcontainers:testcontainers:$testContainerVer")
    testFixturesImplementation("org.testcontainers:junit-jupiter:$testContainerVer")
    testFixturesImplementation("org.springframework:spring-context:$spring")
    testFixturesImplementation("org.springframework:spring-test:$spring")
    testFixturesImplementation("org.springframework.boot:spring-boot-test:$springBoot")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}
