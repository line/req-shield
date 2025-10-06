**req-shield has not yet been published to the maven repository, please be patient.**

---

Visit [the github wiki](https://github.com/line/req-shield/wiki/What-is-Req-Shield) for more information.

# Req-Shield

A lib that regulates the cache-based requests an application receives in terms of request-collapsing.

## Requirements

- Java 8 or later if you are a user.
- Kotlin 1.8 or later if you are a user.
- (If link this lib with Spring) Spring Boot 2.7 (Spring Framework 5.3) or later if you are a user.

## Usage
- Choose from the following based on your platform (There is a detailed explanation in the wiki.)
`implementation("com.linecorp.cse.reqshield:core:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-reactor:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-kotlin-coroutine:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring-webflux:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring-webflux-kotlin-coroutine:{version}")`<br>

## Testing & Integration Tips

### Integration tests with Redis (Testcontainers)

- Redis-backed integration tests using Testcontainers always run as part of module test tasks.
- Requirements:
  - A working local Docker daemon with network access to pull `redis:6.2.7-alpine` on first run.
  - Sufficient permissions to start containers from tests.
- If you need to temporarily bypass Redis ITs locally (e.g., no Docker), run specific unit-test-only tasks or exclude the example modules when invoking Gradle.

### WebFlux null handling

- `@ReqShieldCacheable(nullHandling = ...)` controls how `null` values are emitted in WebFlux:
  - `EMIT_EMPTY` (default): map `null` to `Mono.empty()`.
  - `ERROR`: throw an `IllegalStateException` if a `null` value is produced.

### Global lock guidance

- When `isLocalLock = false`, you must provide real global lock/unlock implementations.
- Recommended approach with Redis:
  - Lock: `SETNX lock:{key} 1` + `PEXPIRE lock:{key} {ttlMillis}`
  - Unlock: `DEL lock:{key}`
- The provided defaults return `true` and are only suitable for local/dev usage.

### Reactor Scheduler tuning

- Reactor-based modules accept a `Scheduler` (e.g., `boundedElastic`) through configuration.
- Spring WebFlux adapter exposes a `reqShieldScheduler` bean you can override for tuning thread usage.

### Kotlin Coroutine Parallelism Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `reqshield.blocking.parallelism` | `availableProcessors * 2` (clamped 4-256) | Controls parallelism for blocking calls in the coroutine aspect |

**Note**: This feature uses `Dispatchers.IO.limitedParallelism()` which is marked as `@ExperimentalCoroutinesApi`.
The API may change in future Kotlin Coroutines versions.

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to
change.
Please see [CONTRIBUTING.md](CONTRIBUTING.md) for contributing to Req-Shield.

## LICENSE

Apache License 2.0

## How to reach us

- File an issue in [the issue tracker](https://github.com/line/req-shield/issues) to report a bug or suggest an
  idea.
