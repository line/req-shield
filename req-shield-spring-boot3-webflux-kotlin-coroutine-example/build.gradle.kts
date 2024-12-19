plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot3)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.linecorp.cse.reqshield"
version = "1.0.0"

dependencies {
    implementation(project(":core-spring-webflux-kotlin-coroutine"))

    implementation(rootProject.libs.slf4j.spring.boot3) {
        because("spring-boot3 depends on slf4j-api 2.0.13")
    }
    implementation(rootProject.libs.spring.boot.starter.webflux)
    implementation(rootProject.libs.spring.boot.starter.cache)
    implementation(rootProject.libs.spring.boot.starter.data.redis)
    implementation(rootProject.libs.spring.boot.starter.data.redis.reactive)
    implementation(rootProject.libs.spring.boot.starter.aop)
    implementation(rootProject.libs.reactor.kotlin.extensions)
    implementation(rootProject.libs.jackson.module.kotlin)
    implementation(rootProject.libs.kotlin.stdlib.jdk8)
    implementation(rootProject.libs.kotlin.coroutine)
    implementation(rootProject.libs.kotlin.coroutine.jvm)
    implementation(rootProject.libs.kotlin.coroutine.jdk8)
    implementation(rootProject.libs.kotlin.coroutine.reactor)
    implementation(rootProject.libs.kotlin.coroutine.reactive)
    annotationProcessor(rootProject.libs.spring.boot.configuration.processor)

    testImplementation(testFixtures(project(":support")))
    testImplementation(rootProject.libs.spring.boot.starter.test)
    testImplementation(rootProject.libs.kotlin.coroutine.test)
    testImplementation(rootProject.libs.logback.spring.boot3) {
        because("spring-boot3 depends on logback-classic 1.4.14")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
}
