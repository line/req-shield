# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Req-Shield is a Kotlin library that provides request-collapsing functionality for cache-based applications. It prevents the thundering herd problem by ensuring only one request for the same cache key is processed at a time, while subsequent concurrent requests wait for the result.

## Core Architecture

The library is organized into several core modules:

- **core**: Base implementation with synchronous operations
- **core-reactor**: Reactive implementation using Project Reactor
- **core-kotlin-coroutine**: Coroutine-based implementation
- **core-spring**: Spring integration with traditional caching
- **core-spring-webflux**: Spring WebFlux integration
- **core-spring-webflux-kotlin-coroutine**: Spring WebFlux + Kotlin Coroutines integration
- **support**: Shared utilities, models, and constants

### Key Components

1. **ReqShield**: Main orchestrator that manages cache operations and request collapsing
2. **KeyLock**: Locking mechanism (local or global) to prevent concurrent cache operations. `tryLock` returns an ownership token and `unLock` only releases when the token matches, so a slow holder cannot release someone else's lock
3. **ReqShieldConfiguration**: Configuration object that defines cache functions, locking behavior, and timeouts
4. **ReqShieldData**: Wrapper for cached data with metadata (creation time, TTL)
5. **Spring Aspects**: AOP-based implementations that provide annotation-driven caching. One `ReqShield` instance per annotated method; cache and lock keys are namespaced as `"{cacheName}::{key}"`; eviction happens after the method returns successfully. Without `key`/`keyGenerator` the aspect builds the key from the arguments itself (`cacheKeyOfArguments` in `support`: `SimpleKey` layout with escaped separators), and a key object without its own `toString()` fails the call before the method runs. A background refresh carries the caller's Reactor `Context` / coroutine context elements; in `core` it runs on the executor thread without the caller's `ThreadLocal` state
6. **GlobalLockSupport**: Optional interface a cache bean implements to enable `isLocalLock = false` (Redis `SET NX PX` + compare-and-delete). Without it, `isLocalLock = false` fails fast

### Design Patterns

- **Template Method**: Core ReqShield logic is template-based with pluggable cache and lock functions
- **Strategy Pattern**: Different locking strategies (local vs global) and work modes
- **Aspect-Oriented Programming**: Spring modules use AOP for transparent caching
- **Factory Pattern**: Configuration objects create appropriate lock implementations

## Development Commands

### Building the Project
```bash
./gradlew build                    # Build all modules
./gradlew :core:build             # Build specific module
./gradlew clean build             # Clean and build
```

### Running Tests
```bash
./gradlew test                    # Run all tests
./gradlew :core:test              # Run tests for specific module
./gradlew test --tests "*ReqShieldTest*"  # Run specific test pattern
```

### Code Quality
```bash
./gradlew ktlintCheck             # Check Kotlin code style
./gradlew ktlintFormat            # Format Kotlin code
./gradlew jacocoTestReport        # Generate test coverage report
```

### Releasing
- `build.gradle.kts` holds the version of the next release. Do not raise it by hand before a release.
- Start the `Publish to Maven Central` workflow (`.github/workflows/publish.yml`) by hand on `main`.
- `dev` publishes the snapshot of the version in `build.gradle.kts` (`1.4.0-SNAPSHOT` for `1.4.0`).
- `release` publishes the version in `build.gradle.kts` (`1.4.0`), pushes the tag `v1.4.0` on the released commit, and
  creates the GitHub release with generated notes. Its last step raises the minor version (`1.5.0`) and pushes that
  commit. For a patch or a major release, set the version by hand before the run.
- `release` pushes with the token of a GitHub App (secrets `RELEASE_APP_ID`, `RELEASE_APP_PRIVATE_KEY`). The app
  must be allowed to bypass the pull request rule of `main`.
- `.github/scripts/read-version.sh` reads the version and `bump-minor-version.sh` raises it;
  `bump-minor-version.test.sh` tests both.

### Running Examples
```bash
./gradlew :req-shield-spring-boot4-example:bootRun                       # Spring Boot 4.1
./gradlew :req-shield-spring-boot4-webflux-example:bootRun
./gradlew :req-shield-spring-boot4-webflux-kotlin-coroutine-example:bootRun
```

### Running Benchmarks
```bash
./gradlew :req-shield-benchmark-example:jmhJar      # JMH benchmarks of the library (not published); see README "Benchmarks"
java -jar req-shield-benchmark-example/build/libs/req-shield-benchmark-example-*-jmh.jar -l
```

## Module Structure

### Core Modules
Each core module follows the same package structure:
- `com.linecorp.cse.reqshield.{variant}/` - Main classes (ReqShield, KeyLock implementations)
- `com.linecorp.cse.reqshield.{variant}/config/` - Configuration classes

### Spring Integration Modules
Spring modules add:
- `annotation/` - Cache annotations (@ReqShieldCacheable, @ReqShieldCacheEvict)
- `aspect/` - AOP implementation for intercepting annotated methods
- `cache/` - Cache interface implementations
- `config/` - Auto-configuration for Spring Boot

### Support Module
Contains shared:
- `constant/` - Configuration constants and defaults
- `exception/` - Custom exceptions and error codes
- `model/` - Data models (ReqShieldData)
- `utils/` - Utility functions for cache decisions

## Key Configuration Options

### ReqShieldConfiguration Parameters
- `isLocalLock`: Use local vs distributed locking (default: true)
- `globalLockFunction` / `globalUnLockFunction`: `(lockKey, token, ttlMillis) -> Boolean` / `(lockKey, token) -> Boolean`, required when `isLocalLock = false`
- `executor` (core) / `scheduler` (reactor) / `scope` (coroutine): where background cache writes run; defaults are shared. The Spring adapters register none of them as beans (an `Executor` bean would make Spring Boot drop its `applicationTaskExecutor`, and any library bean would clash with an application bean of the same name): each aspect uses an application bean named `reqShieldExecutor` / `reqShieldScheduler` / `reqShieldCoroutineScope` if one exists (a bean of that name with another type fails the context refresh), otherwise its own default (an aspect-owned pool / the shared `boundedElastic` / an aspect-owned scope), and only shuts down what it owns
- `lockTimeoutMillis`: Lock acquisition timeout (default: 3000ms)
- `decisionForUpdate`: Percentage of TTL after which to trigger async cache refresh (default: 80)
- `maxAttemptGetCache`: Max retry attempts when waiting for cache (default: 60 reads for the wait for a holder and 60 for the wait for a refresh, at most 50ms apart; a waiter in the JVM of the lock holder reads the cache as soon as the holder releases its lock, retries the creation lock when the holder wrote no entry, and a cache miss during a refresh of the same JVM waits for that refresh). Three consecutive cache-read failures while waiting fall back to the supplier immediately; supplier failures propagate as `ClientException(SUPPLIER_ERROR)`
- `reqShieldWorkMode`: CREATE_AND_UPDATE_CACHE | ONLY_CREATE_CACHE | ONLY_UPDATE_CACHE

### Work Modes
- **CREATE_AND_UPDATE_CACHE**: Full functionality (default)
- **ONLY_CREATE_CACHE**: The configured lock applies only when a cache entry is being created. A refresh of an existing entry takes a local lock of the JVM instead (never the global lock), so one instance runs one refresh of a key at a time
- **ONLY_UPDATE_CACHE**: Request collapsing (lock) applies only when an existing entry is being refreshed; on a cache miss every request calls the supplier and writes the cache without a lock

## Testing Guidelines

### Test Infrastructure
- Uses JUnit Jupiter 6 on the JUnit Platform (the version of the Spring Boot 4.0 BOM; Spring Framework 7's `SpringExtension` fails on JUnit 5.9)
- MockK for Kotlin mocking
- Testcontainers for integration tests (Redis); set `TEST_REDIS_HOST`/`TEST_REDIS_PORT` to use an external Redis instead (CI does this)
- Awaitility for asynchronous testing
- Separate test fixtures in `support` module

### Test Coverage Requirements
- **Minimum test coverage**: 80% line coverage for every library module (example modules are exempt)
- Coverage reports generated via `./gradlew jacocoTestReport`
- Enforced by `jacocoTestCoverageVerification`, which runs as part of `./gradlew check` / `./gradlew build`

### Code Quality Requirements
- **Lint validation**: All code must pass ktlint checks before completion
- Run `./gradlew ktlintCheck` to validate code style
- Fix any lint issues with `./gradlew ktlintFormat`
- Lint validation is mandatory for all code changes

### Test Categories
- **Unit Tests**: Test individual components in isolation
- **Integration Tests**: Test Spring integration with real Redis containers
- **Base Test Classes**: Located in `support/src/testFixtures/` for reuse across modules

## Version Compatibility

### Java/Kotlin Compatibility
- **All modules**: Java 21+ (bytecode target 21); library consumers need Kotlin 2.2+
- **Example applications**: `req-shield-spring-boot4-*` run on Spring Boot 4.1. Spring Boot 3 is not supported.

### Kotlin Toolchain
- The build uses the Kotlin 2.4 compiler (K2), set by `kotlin` in `libs.versions.toml`.
- Library modules compile with `languageVersion`/`apiVersion` 2.2 and publish `kotlin-stdlib`/`kotlin-reflect` at
  `kotlinApi` (2.2.21) instead of the compiler's version (`kotlin.stdlib.default.dependency=false`). This keeps the
  published metadata and stdlib readable by consumers on Kotlin 2.2+, the default Kotlin of Spring Boot 4.0 (the
  supported minimum).
  Raising either value raises the minimum Kotlin version of consumers. Kotlin 2.4 deprecated language version 2.0
  and asked for 2.2; 2.2 compiles without that warning.
- The Reactor modules (`reactorApiProjects` in the root build) compile with `-Xjspecify-annotations=warn`: Reactor
  3.8 declares `Mono`'s type parameter non-null, and the Reactor API still uses `Mono<ReqShieldData<T>?>`.
- Example applications are not published and use the compiler's stdlib.
- The stdlib is an `api` dependency of the library modules, so it stays in the POM's compile scope for Java callers.

### Framework Support
- Spring Boot 4.0+ (Spring Framework 7.0+). Spring Boot 3 is not supported.
- Project Reactor 3.8+
- Kotlin Coroutines 1.10+
- The library compiles and runs its tests against these minimums (`spring`, `springBoot`, `reactor`,
  `kotlinCoroutine` in `libs.versions.toml`). The boot4 examples run it on Spring Boot 4.1 (`springBootExample`).
