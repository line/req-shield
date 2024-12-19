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
}

val spring: String by project
val aspectj: String by project

dependencies {
    api(project(":core"))
    compileOnly("org.springframework:spring-context:$spring")
    compileOnly("org.aspectj:aspectjweaver:$aspectj")

    testImplementation(testFixtures(project(":support")))
    testImplementation(project(":core"))
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework:spring-context:$spring")
    testImplementation("org.aspectj:aspectjweaver:$aspectj")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}
