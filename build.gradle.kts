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
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.ktlint)
    id("jacoco")
    application
    `maven-publish`
    `java-library`
}

allprojects {

    group = "com.linecorp.cse.reqshield"
    version = "1.0.0"

    apply {
        plugin("java-test-fixtures")
        plugin("maven-publish")
        plugin("java-library")
        plugin("jacoco")
    }

    repositories {
        mavenCentral()
    }

    dependencies {
        implementation(rootProject.libs.kotlin.reflect)
        implementation(rootProject.libs.slf4j)
        testImplementation(rootProject.libs.logback)

        testImplementation(rootProject.libs.kotlin.test)
        testImplementation(rootProject.libs.mockk)
        testImplementation(rootProject.libs.awaitility)
    }

    tasks.test {
        useJUnitPlatform()
        finalizedBy(tasks.jacocoTestReport)
    }

    tasks.withType<JacocoReport> {
        reports {
            xml.required.set(true)
        }
    }

    jacoco {
        toolVersion = "0.8.12"
    }
}

val springBoot3ProjectNames =
    listOf(
        "req-shield-spring-boot3-example",
        "req-shield-spring-boot3-webflux-example",
        "req-shield-spring-boot3-webflux-kotlin-coroutine-example",
    )

subprojects {
    apply {
        plugin("org.jlleitschuh.gradle.ktlint")
    }
    java {
        sourceCompatibility =
            when (project.name) {
                in springBoot3ProjectNames -> JavaVersion.VERSION_17
                else -> JavaVersion.VERSION_1_8
            }
        targetCompatibility =
            when (project.name) {
                in springBoot3ProjectNames -> JavaVersion.VERSION_17
                else -> JavaVersion.VERSION_1_8
            }
    }

    afterEvaluate {
        fun getProfile() = properties["PROFILE"] ?: System.getenv()["PROFILE"] ?: "local"

        fun getVersion() = project.version.toString()

        fun getSemanticPostfix() =
            when (getProfile()) {
                "real" -> ""
                else -> "-SNAPSHOT"
            }

        version = "${getVersion()}${getSemanticPostfix()}"

        publishing {
            repositories {
                maven {
                    fun getUrl(): String =
                        if (getProfile() == "real") {
                            "https://oss.sonatype.org/service/local/staging/deploy/maven2/"
                        } else {
                            "https://oss.sonatype.org/content/repositories/snapshots/"
                        }

                    url = uri(getUrl())

                    credentials {
                        username = System.getenv()["NEXUS_USER"]
                        password = System.getenv()["NEXUS_PASS"]
                    }
                }
            }

            publications {
                register("mavenJava", MavenPublication::class) {

                    from(components["java"])

                    pom {
                        name.set("LINE Req-Shield")
                        description.set("LINE Req-Shield")
                        url.set("https://github.com/line/req-shield.git")

                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }

                        scm {
                            url.set("scm:git@github.com:line/req-shield.git")
                            connection.set("scm:git@github.com:line/req-shield.git")
                            developerConnection.set("scm:git@github.com:line/req-shield.git")
                        }
                    }
                }
            }
        }
    }
}
