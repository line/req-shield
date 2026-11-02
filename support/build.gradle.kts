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
    `java-test-fixtures`
}

dependencies {
    testFixturesImplementation(rootProject.libs.junit)
    // Expose Testcontainers to consumers of test fixtures because RedisContainer
    // leaks GenericContainer type in its API (instance property)
    testFixturesApi(rootProject.libs.testcontainers)
    testFixturesApi(rootProject.libs.junit.jupiter.testcontainers)
    testFixturesImplementation(rootProject.libs.spring.context)
    testFixturesImplementation(rootProject.libs.spring.test)
    testFixturesImplementation(rootProject.libs.spring.boot.test)

    constraints {
        // Testcontainers 1.21.4 (the latest 1.x) brings commons-compress 1.24.0 (CVE-2024-25710, CVE-2024-26308).
        testFixturesApi(rootProject.libs.commons.compress)
    }
}
