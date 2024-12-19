plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.5"
}

group = "com.linecorp.cse.reqshield"
version = "1.0.0"

dependencies {
    implementation(project(":core-spring"))

    implementation("org.slf4j:slf4j-api:2.0.13") {
        because("spring-boot3 depends on slf4j-api 2.0.13")
    }

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation(testFixtures(project(":support")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("ch.qos.logback:logback-classic:1.4.14") {
        because("spring-boot3 depends on logback-classic 1.4.14")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
}
