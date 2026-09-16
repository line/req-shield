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
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

val snapshotBuild = providers.gradleProperty("snapshotBuild").getOrElse("true").toBoolean()

allprojects {

    group = "com.linecorp.cse.reqshield"
    version = "1.0.0${if (snapshotBuild) "-SNAPSHOT" else ""}"

    apply {
        plugin("java-test-fixtures")
        plugin("maven-publish")
        plugin("java-library")
        plugin("jacoco")
        plugin("signing")
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

    // Enforce the minimum line coverage documented in CLAUDE.md for library modules.
    // Example applications (req-shield-*-example) are demos and are not held to the threshold.
    if (project != rootProject && !project.name.endsWith("-example")) {
        tasks.withType<JacocoCoverageVerification> {
            dependsOn(tasks.test)
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = "0.80".toBigDecimal()
                    }
                }
            }
        }
        tasks.named("check") {
            dependsOn(tasks.withType<JacocoCoverageVerification>())
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
        withJavadocJar()
        withSourcesJar()

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
        if (project.name.endsWith("-example")) return@afterEvaluate

        publishing {
            publications {
                register("mavenJava", MavenPublication::class) {

                    from(components["java"])

                    pom {
                        name.set("LINE Req-Shield")
                        description.set("LINE Req-Shield")
                        url.set("https://github.com/line/req-shield.git")

                        developers {
                            developer {
                                name.set("LINE Corporation")
                                organization.set("LY Corporation")
                                organizationUrl.set("https://www.lycorp.co.jp/en/")
                            }
                        }

                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }

                        scm {
                            url.set("https://github.com/line/req-shield")
                            connection.set("scm:git:https://github.com/line/req-shield.git")
                            developerConnection.set("scm:git:ssh://git@github.com/line/req-shield.git")
                        }
                    }
                }
            }
        }

        val signingKeyId = providers.gradleProperty("signingKeyId").orNull
        val signingKey = providers.gradleProperty("signingKey").orNull
        val signingPassword = providers.gradleProperty("signingPassword").orNull
        if (signingKey != null) {
            signing {
                useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
                sign(publishing.publications["mavenJava"])
            }
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}
