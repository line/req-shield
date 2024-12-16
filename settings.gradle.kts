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

pluginManagement {
    repositories {
        gradlePluginPortal()
    }

    plugins {
        kotlin("jvm") version "1.6.21"
        application
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}

rootProject.name = "req-shield"
include("core")
include("core-reactor")
include("core-kotlin-coroutine")
include("core-spring")
include("req-shield-spring-example")
include("req-shield-spring-boot3-example")
include("core-spring-webflux")
include("req-shield-spring-webflux-example")
include("req-shield-spring-boot3-webflux-example")
include("core-spring-webflux-kotlin-coroutine")
include("req-shield-spring-webflux-kotlin-coroutine-example")
include("req-shield-spring-boot3-webflux-kotlin-coroutine-example")
include("support")
