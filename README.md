Visit [the github wiki](https://github.com/line/req-shield/wiki/What-is-Req-Shield) for more information.

# Req-Shield

A lib that regulates the cache-based requests an application receives in terms of request-collapsing.

## Requirements

- Java 8 or later if you are a user.
- Kotlin 1.8 or later if you are a user.
- (If link this lib with Spring) Spring Boot 2.7 (Spring Framework 5.3) or later if you are a user.

## Usage

Choose a module based on your platform (there is a detailed explanation in the wiki):

`implementation("com.linecorp.cse.reqshield:core:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-reactor:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-kotlin-coroutine:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring-webflux:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring-webflux-kotlin-coroutine:{version}")`<br>

### Release versions

Release versions are available from Maven Central. Add Maven Central and use a release version such as `1.0.0`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.linecorp.cse.reqshield:core:1.0.0")
}
```

### Dev snapshot versions

Dev versions are published as Maven snapshots and require the Maven Central Snapshot repository. Use a snapshot version
such as `1.0.0-SNAPSHOT`:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "MavenCentralSnapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent {
            snapshotsOnly()
        }
    }
}

dependencies {
    implementation("com.linecorp.cse.reqshield:core:1.0.0-SNAPSHOT")
}
```

Snapshot versions can be updated without changing the version string. If Gradle continues to use a cached snapshot,
refresh dependencies with `./gradlew build --refresh-dependencies`.

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

### Cache key layout

- The Spring adapters store every entry under `"{cacheName}::{key}"` (the same convention as Spring's `RedisCacheManager`),
  where `key` is the SpEL result or the `KeyGenerator` output. `@ReqShieldCacheEvict` applies the same rule, so an evict
  with the same `cacheName` and `key` always targets the entry written by `@ReqShieldCacheable`.
- Lock keys are derived from that namespaced key and prefixed with `reqshield:lock:`, so two caches that happen to use the
  same raw key never share a lock or an entry.
- If you call `ReqShield` directly (core / core-reactor / core-kotlin-coroutine), the key you pass is used as is.

### Global lock guidance

- When `isLocalLock = false`, your cache bean must also implement the module's `GlobalLockSupport` interface
  (`com.linecorp.cse.reqshield.spring.cache.GlobalLockSupport`, `...spring.webflux.cache.GlobalLockSupport`,
  `...spring.webflux.kotlin.coroutine.cache.GlobalLockSupport`). If it does not, the first call to the annotated method
  fails with an `IllegalArgumentException` instead of silently running without request collapsing.
- Every lock acquisition carries an ownership token. Only the holder that acquired the lock can release it, so a slow
  holder whose lock already expired can no longer release the lock of the next holder.
- Recommended Redis implementation:
    - Lock: `SET {lockKey} {token} NX PX {ttlMillis}` (atomic; never `SETNX` followed by a separate `PEXPIRE`)
    - Unlock: compare-and-delete in a Lua script, e.g.
      `if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end`
- The example modules contain working implementations for `RedisTemplate`, `ReactiveRedisTemplate` and the coroutine
  extensions.

### Local lock map size

- The local lock (`isLocalLock = true`, the default) keeps one entry per `(cache key, lock type)` it is currently
  locking, in a map shared by the whole JVM. A cache key that is both created and refreshed therefore uses up to two
  entries.
- An entry is dropped as soon as the lock is released; an entry whose holder never released it is dropped by the
  cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much.
  The map's size therefore tracks how many keys are being locked **at the same time**, not how many distinct keys the
  application sees. A service with 50 ms of supplier latency at 10k rps holds roughly 500 locks at once.
- The map is uncapped by default. To bound it, set `req-shield.lock.max-entries`; `0` (the default) means uncapped.
  Size it against concurrent lock ownership, not key cardinality.

  ```yaml
  # Spring modules: read from the Environment, so application.yml works
  req-shield:
    lock:
      max-entries: 2000
  ```

  ```bash
  # core / core-reactor / core-kotlin-coroutine used directly: system property
  java -Dreq-shield.lock.max-entries=2000 -jar app.jar
  ```

  The Spring modules read the same key from the `Environment`, so a `-D` override still outranks the yml entry there,
  and `REQ_SHIELD_LOCK_MAX_ENTRIES` works as an environment variable.
- Once the map is full, a request for a **new** key is handed a permit that no map entry backs. It then runs exactly as
  if it had taken the lock - it calls the supplier and writes the cache - so what the cap costs is request collapsing
  for that key, and nothing else: no added latency, and the cache still gets populated. Keys whose entry is already in
  the map are not subject to the cap at all.
- The cap is a soft one. The size check is an estimate and is not atomic with the insertion it guards, so concurrent
  callers can push the map slightly past the configured number.
- The first refusal and every 1000th after it are logged at WARN.
- A value that cannot be read as a non-negative number is logged at WARN and ignored, leaving the current cap
  unchanged. This is deliberate in both directions: a class initializer that throws would poison the library for the
  whole JVM, and an application context should not fail to start over a tuning knob typo. Setting the cap
  programmatically (`LocalLockLimit.maxEntries = -1`) still fails fast, because there the stack trace is actionable.

### Cache eviction semantics

- `@ReqShieldCacheEvict` evicts **after** the annotated method completes successfully (Spring's `@CacheEvict` default).
  If the method throws, nothing is evicted; if the eviction itself fails, that failure propagates to the caller.
- In WebFlux the eviction also runs when the method completes empty (for example a `Mono<Void>` handler).

### Waiting for another request (lock not acquired)

- A request that loses the lock polls the cache every 50 ms, up to `maxAttemptGetCache` times (default 60).
- A cache read failure while polling is logged and counted as a failed attempt. Three consecutive failures are treated as a
  cache outage and the request falls back to calling the supplier itself right away.
- When the attempts are exhausted the request calls the supplier once. A supplier failure is propagated as a
  `ClientException(SUPPLIER_ERROR)` with the original exception as `cause`; it is never turned into a cached `null`.

### Thread pools and schedulers

- `core` accepts any `java.util.concurrent.Executor` for its background cache writes; only `execute` is called and the
  library never shuts a caller-supplied pool down. The default is a shared daemon pool; the Spring adapter exposes it as
  the `reqShieldExecutor` bean (an `ExecutorService` the context shuts down), which you can override.
- `core-reactor` accepts a `Scheduler` (default `boundedElastic`). The Spring WebFlux adapter exposes it as the
  `reqShieldScheduler` bean.
- `core-kotlin-coroutine` accepts a `CoroutineScope` for background cache writes (default: a shared supervisor scope on
  `Dispatchers.IO`). The coroutine Spring adapter exposes it as the `reqShieldCoroutineScope` bean and cancels it on
  context shutdown.

### Kotlin coroutine adapter

- `@ReqShieldCacheable` / `@ReqShieldCacheEvict` from `core-spring-webflux-kotlin-coroutine` require `suspend`
  functions. Annotating a regular function fails fast with an `IllegalArgumentException`; use `core-spring` or
  `core-spring-webflux` for blocking or `Mono`-returning methods.

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to
change.
Please see [CONTRIBUTING.md](CONTRIBUTING.md) for contributing to Req-Shield.

## LICENSE

Apache License 2.0

## How to reach us

- File an issue in [the issue tracker](https://github.com/line/req-shield/issues) to report a bug or suggest an
  idea.
