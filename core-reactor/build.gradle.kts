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

version = "1.3.1"

plugins {
    kotlin("jvm") version "1.6.21"
}

val reactor: String by project

dependencies {
    api(project(":support"))
    compileOnly("io.projectreactor:reactor-core:$reactor")
    testImplementation("io.projectreactor:reactor-test:$reactor")
    testImplementation("io.lettuce:lettuce-core:6.1.10.RELEASE")
    testImplementation(testFixtures(project(":support")))
}

tasks.compileKotlin {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}
