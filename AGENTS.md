# Repository Guidelines

## Project Structure & Module Organization
- Core libraries live in module directories such as `core`, `core-reactor`, and `core-kotlin-coroutine`; each follows the Gradle layout `src/main` and `src/test`.
- Spring adapters sit under `core-spring*` modules, while runnable samples are in `req-shield-*example` projects.
- Shared utilities and constants are centralized in `support`. Generated build outputs stay under each module's `build/` folder.

## Build, Test, and Development Commands
- `./gradlew build` compiles all modules, runs unit tests, and assembles artifacts; pass `--parallel` for faster local feedback.
- `./gradlew test` executes Kotlin/JVM unit tests across every enabled module.
- `./gradlew ktlintCheck` enforces the project's formatting contract before you open a PR.
- Use `./gradlew :req-shield-spring-boot3-example:bootRun` (or another sample module) to manually exercise integration paths.

## Coding Style & Naming Conventions
- Kotlin sources use 4-space indentation, `UpperCamelCase` for types, and `lowerCamelCase` for functions and properties.
- Keep package names lowercase and aligned with module boundaries (e.g., `com.linecorp.reqshield.core`).
- Always add the Apache 2.0 copyright header shown in `CONTRIBUTING.md` to new files.
- Prefer early-return patterns and meaningful exception messages; align with the `ErrorCode` enums already defined.

## Testing Guidelines
- Write tests with JUnit 5 (`org.junit.jupiter`) and place them under `src/test/kotlin` mirroring the `src/main` package.
- Use descriptive method names such as `shouldCollapseConcurrentRequests()` and cover both success and failure paths.
- When adding integration behaviour, extend the corresponding example module and run `./gradlew test` before submission.

## Commit & Pull Request Guidelines
- Follow the repository's history of concise, imperative commits (e.g., `Add cache invalidation helper`).
- Reference related issues in the body, summarise motivation, modifications, and results, and include screenshots/logs for behaviour changes.
- Verify CLS (Contributor License Agreement) status, ensure CI passes locally, and request review from a maintainer familiar with your module.
