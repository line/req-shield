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
2. **KeyLock**: Locking mechanism (local or global) to prevent concurrent cache operations
3. **ReqShieldConfiguration**: Configuration object that defines cache functions, locking behavior, and timeouts
4. **ReqShieldData**: Wrapper for cached data with metadata (creation time, TTL)
5. **Spring Aspects**: AOP-based implementations that provide annotation-driven caching

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

### Running Examples
```bash
./gradlew :req-shield-spring-boot3-example:bootRun
./gradlew :req-shield-spring-webflux-example:bootRun
./gradlew :req-shield-spring-webflux-kotlin-coroutine-example:bootRun
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
- `lockTimeoutMillis`: Lock acquisition timeout (default: 3000ms)
- `decisionForUpdate`: Percentage of TTL after which to trigger async cache refresh (default: 80)
- `maxAttemptGetCache`: Max retry attempts when waiting for cache (default: 10)
- `reqShieldWorkMode`: CREATE_AND_UPDATE_CACHE | ONLY_CREATE_CACHE | ONLY_UPDATE_CACHE

### Work Modes
- **CREATE_AND_UPDATE_CACHE**: Full functionality (default)
- **ONLY_CREATE_CACHE**: Never updates existing cache entries
- **ONLY_UPDATE_CACHE**: Never creates new cache entries

## Testing Guidelines

### Test Infrastructure
- Uses JUnit 5 platform
- MockK for Kotlin mocking
- Testcontainers for integration tests (Redis)
- Awaitility for asynchronous testing
- Separate test fixtures in `support` module

### Test Coverage Requirements
- **Minimum test coverage**: 80% must be maintained across all modules
- Coverage reports generated via `./gradlew jacocoTestReport`
- Coverage enforced through Jacoco plugin configuration

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
- **Core modules**: Java 8+, Kotlin 1.8+
- **Spring Boot 3 examples**: Java 17+
- **Spring Boot 2 examples**: Java 8+

### Framework Support
- Spring Boot 2.7+ (Spring Framework 5.3+)
- Spring Boot 3.3+
- Project Reactor 3.4+
- Kotlin Coroutines 1.7+
