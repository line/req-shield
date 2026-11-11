Visit [the github wiki](https://github.com/line/req-shield/wiki/What-is-Req-Shield) for more information.

# Req-Shield

A lib that regulates the cache-based requests an application receives in terms of request-collapsing.

## Requirements

- Java 17 or later if you are a user.
- Kotlin 1.9 or later if you are a user.
- (If link this lib with Spring) Spring Boot 3.5 (Spring Framework 6.2) or later if you are a user.
- (If you use the Reactor modules) Project Reactor 3.7 or later; (if you use the coroutine modules) kotlinx.coroutines 1.8 or later.

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

### Kotlin with Reactor 3.8 (Spring Boot 4)

- Reactor 3.8 declares `Mono`'s type parameter non-null with JSpecify annotations, and the Kotlin compiler reports
  violations as errors by default. The Reactor-based API still uses nullable type arguments such as
  `Mono<ReqShieldData<T>?>` (`AsyncCache.get`, `ReqShieldConfiguration.getCacheFunction`), so Kotlin code that
  implements or calls it fails to compile on Reactor 3.8.
- Until the API changes, compile such Kotlin code with `-Xjspecify-annotations=warn`, as
  `req-shield-spring-boot4-webflux-example` does. Java callers and the coroutine modules are not affected.

### WebFlux null handling

- `@ReqShieldCacheable(nullHandling = ...)` controls how `null` values are emitted in WebFlux:
    - `EMIT_EMPTY` (default): map `null` to `Mono.empty()`.
    - `ERROR`: throw an `IllegalStateException` if a `null` value is produced.

### Cache key layout

- The Spring adapters store every entry under `"{cacheName}::{key}"` (the same convention as Spring's `RedisCacheManager`).
  `@ReqShieldCacheEvict` applies the same rule, so an evict with the same `cacheName` and the same key source always
  targets the entry written by `@ReqShieldCacheable`.
- Lock keys are derived from that namespaced key and prefixed with `reqshield:lock:`, so two caches that happen to use the
  same raw key never share a lock or an entry.
- If you call `ReqShield` directly (core / core-reactor / core-kotlin-coroutine), the key you pass is used as is.
- `key` comes from one of three sources:
    - **Method arguments** (neither `key` nor `keyGenerator` is set), in the layout of Spring's `SimpleKeyGenerator`:
      no argument gives `SimpleKey []`, one argument gives its `toString()`, several give `SimpleKey [a, b]`.
    - **A SpEL `key`.** A text is used as is. An array, a collection or a map (for example `key = "{#tenant, #id}"`)
      is written by content, as `[tenant, id]`. Any other value is converted by Spring (an enum by its name), or is
      its `toString()` when Spring has no converter for it (a data class, for example).
    - **A `keyGenerator` bean.** The `toString()` of its result is used as is, so a custom generator has to keep
      different arguments apart itself. In the coroutine adapter the generator also receives the `Continuation`.
- Where a value is written by content or inside `SimpleKey [...]`, its text is escaped so that it cannot run into the
  next value: `,` `[` `]` `{` `}` `=` and the backslash get a backslash in front, and an empty text is written as `\e`.
  A single argument escapes only the backslash. `null` is always written as `\0`. For example, `("a, b", "c")` gives
  `SimpleKey [a\, b, c]`, and a data class argument next to another argument gives `SimpleKey [t, Filter(a\=1\, b\=2)]`.
- Two argument lists of a method then get different keys, as long as the `toString()` of each value tells its own
  values apart. A class whose `toString()` joins free text does not: `Pair("a, b", "c")` and `Pair("a", "b, c")` are both
  `(a, b, c)`. Give such a method a `key`. The same holds for a parameter typed `Any` that takes the text `[a, b]` in
  one call and a list in another.
- A key object whose class does not override `toString()` fails the call with an `IllegalArgumentException`, because
  its text would be only the class name and the hash code. This applies to a method argument on the default path, to
  the result of a SpEL `key`, and to the result of a `keyGenerator` bean. The key is built before the method is called,
  so the annotated method does not run - neither a `@ReqShieldCacheable` method nor a `@ReqShieldCacheEvict` one.
  Only the class of the value itself is checked: an object that prints such an object inside its own `toString()`
  (a `Pair` of one) passes, and its entry is then never hit.
- A collection or a map that contains itself is rejected the same way.

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
- **Known limitation:** a value that is computed before an eviction can be written after it. A request returns its
  data first and writes the cache asynchronously, and a background refresh can be in flight, so an entry evicted in
  that window comes back with the old value until its TTL ends. The eviction and the write can run on different
  nodes, so the library cannot order them. If stale data after an update is not acceptable, use a short TTL or put a
  version of the data in the cache key.

### Background refresh and the caller's context

- Once an entry is older than `decisionForUpdate` percent of its TTL, the next read returns it and refreshes it in the
  background. The refresh calls the supplier after the caller already has its data.
- `core-reactor` and the WebFlux adapter subscribe the refresh with the caller's Reactor `Context`, so a supplier that
  reads it (for example `ReactiveSecurityContextHolder`) works in a refresh as it does in a request. A reactive
  transaction of the caller is part of that `Context` too; a supplier that must not run in it should not be refreshed
  from inside the transaction.
- `core-kotlin-coroutine` and the coroutine adapter launch the refresh in the configured scope with the caller's
  coroutine context elements, except its `Job`, dispatcher and exception handler: the refresh is not cancelled with
  the request and does not run on its thread. For any other element the scope's own one takes precedence.
- `core` and the blocking Spring adapter run the refresh on the executor thread. `ThreadLocal` state of the caller
  (`SecurityContextHolder`, `RequestContextHolder`, MDC, a transaction) is **not** available there. Pass what the
  method needs as arguments, or supply a `reqShieldExecutor` that propagates the context your application needs.

### Waiting for another request (lock not acquired)

- A request that loses the lock polls the cache every 50 ms, up to `maxAttemptGetCache` times (default 60).
- A cache read failure while polling is logged and counted as a failed attempt. Three consecutive failures are treated as a
  cache outage and the request falls back to calling the supplier itself right away.
- When the attempts are exhausted the request calls the supplier once. A supplier failure is propagated as a
  `ClientException(SUPPLIER_ERROR)` with the original exception as `cause`; it is never turned into a cached `null`.
- In `core-kotlin-coroutine`, a `CancellationException` thrown by a client function while the calling coroutine is
  still active (a `withTimeout` inside the supplier, for example) is a failure of that function. It is reported as
  `ClientException` like any other failure. Only the cancellation of the calling coroutine itself propagates unwrapped.

### Thread pools and schedulers

- `core` accepts any `java.util.concurrent.Executor` for its background cache writes; only `execute` is called and the
  library never shuts a caller-supplied pool down. The default is a shared daemon pool. The Spring adapter runs the
  writes on a daemon pool of its own that is shut down with the context, and registers no `Executor` bean, so Spring
  Boot keeps its `applicationTaskExecutor`. To use your own pool, define an `Executor` bean named `reqShieldExecutor`;
  its lifecycle stays yours, and like any `Executor` bean it makes Spring Boot skip `applicationTaskExecutor`.
- `core-reactor` accepts a `Scheduler` (default `boundedElastic`). The Spring WebFlux adapter uses the shared
  `boundedElastic` too and never disposes it; to use your own, define a `Scheduler` bean named `reqShieldScheduler`.
- `core-kotlin-coroutine` accepts a `CoroutineScope` for background cache writes (default: a shared supervisor scope on
  `Dispatchers.IO`). The coroutine Spring adapter runs them on a supervisor scope of its own on `Dispatchers.IO` and
  cancels it on context shutdown; to use your own, define a `CoroutineScope` bean named `reqShieldCoroutineScope`.
- The Spring adapters register none of these beans themselves, so an application bean of the same name replaces the
  default instead of clashing with it, and its lifecycle stays with the application. A bean of one of these names
  that is not of the expected type fails the application startup instead of being ignored.

### WebFlux adapter

- `@ReqShieldCacheable` / `@ReqShieldCacheEvict` from `core-spring-webflux` require methods that return `Mono`. The
  aspect hands back a lazy `Mono`, and other return types are not rejected up front:
    - On a `void` (`Unit`) method Spring discards that `Mono`, so the method body never runs and nothing is evicted,
      without any error. Return `Mono<Void>` from eviction methods instead.
    - Any other return type fails with a `ClassCastException` at the call site.
- Use `core-spring` for blocking methods and `core-spring-webflux-kotlin-coroutine` for `suspend` functions.

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
