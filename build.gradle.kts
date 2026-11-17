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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = "1.3.0"

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

val baseVersion = version.toString()
val snapshotBuild = providers.gradleProperty("snapshotBuild").getOrElse("true").toBoolean()

allprojects {

    group = "com.linecorp.cse.reqshield"
    version = "$baseVersion${if (snapshotBuild) "-SNAPSHOT" else ""}"

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
        if (project.name.endsWith("-example")) {
            // Example applications are not published; they run on the runtime of the compiler that builds them.
            implementation(
                kotlin(
                    "stdlib",
                    rootProject.libs.versions.kotlin
                        .get(),
                ),
            )
            implementation(
                kotlin(
                    "reflect",
                    rootProject.libs.versions.kotlin
                        .get(),
                ),
            )
        } else {
            // `api` keeps the stdlib in the published POM's compile scope (as the Kotlin plugin's default did),
            // because the public API exposes Kotlin types such as function types to Java callers.
            "api"(rootProject.libs.kotlin)
            // The test-fixtures source set does not inherit `api`, so it needs the stdlib too.
            "testFixturesApi"(rootProject.libs.kotlin)
            implementation(rootProject.libs.kotlin.reflect)
            // Example applications get slf4j and logback from their Spring Boot starters instead.
            implementation(rootProject.libs.slf4j)
            testImplementation(rootProject.libs.logback)
            // Lettuce, the Redis client of the tests, brings a Netty version with known vulnerabilities.
            testImplementation(platform(rootProject.libs.netty.bom))
        }

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

    // Tool classpaths (ktlint, and the Kotlin plugin's internal Bouncy Castle) are resolved per project.
    // Registered after evaluation so that these rules run after Spring's dependency management rule.
    afterEvaluate {
        configurations.matching { it.name.startsWith("ktlint") }.configureEach {
            resolutionStrategy.eachDependency {
                when (requested.group) {
                    // Spring Boot's dependency management (example modules) aligns every org.jetbrains.kotlin
                    // artifact to the project's Kotlin version, including the compiler that ktlint embeds,
                    // and ktlint fails with that compiler. Keep the versions ktlint asks for.
                    "org.jetbrains.kotlin" -> {
                        useVersion(requested.version!!)
                    }

                    // ktlint 1.0.1 brings logback 1.3.5, which has known vulnerabilities.
                    "ch.qos.logback" -> {
                        useVersion(
                            rootProject.libs.versions.logback
                                .get(),
                        )
                    }
                }
            }
        }
        configurations.matching { it.name == "kotlinBouncyCastleConfiguration" }.configureEach {
            resolutionStrategy.eachDependency {
                // The Kotlin Gradle plugin 2.4.21 brings bcprov 1.84, which has known vulnerabilities.
                if (requested.group == "org.bouncycastle") {
                    useVersion(
                        rootProject.libs.versions.bouncycastle
                            .get(),
                    )
                }
            }
        }
    }
}

// Modules that implement or call the Reactor API of the library (see the compiler options below).
val reactorApiProjects =
    setOf(
        "core-reactor",
        "core-spring-webflux",
        "req-shield-spring-boot4-webflux-example",
        "req-shield-benchmark-example",
    )

subprojects {
    apply {
        plugin("org.jlleitschuh.gradle.ktlint")
    }

    java {
        withJavadocJar()
        withSourcesJar()

        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xjsr305=strict")
            // Reactor 3.8 declares Mono's type parameter non-null with JSpecify annotations, which the Kotlin
            // compiler enforces as errors by default. The Reactor API of the library still uses nullable type
            // arguments such as Mono<ReqShieldData<T>?>, so the modules that implement or call it report them
            // as warnings.
            if (project.name in reactorApiProjects) {
                freeCompilerArgs.add("-Xjspecify-annotations=warn")
            }
            jvmTarget.set(JvmTarget.JVM_21)
            // Library modules are compiled by the K2 compiler but emit Kotlin 2.2 metadata and use only the
            // 2.2 standard library API, so that consumers on older Kotlin compilers can still read them.
            // Example applications are not published and use the compiler defaults.
            if (!project.name.endsWith("-example")) {
                languageVersion.set(KotlinVersion.KOTLIN_2_2)
                apiVersion.set(KotlinVersion.KOTLIN_2_2)
            }
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
