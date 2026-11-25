Visit [the github wiki](https://github.com/line/req-shield/wiki/What-is-Req-Shield) for more information.

# Req-Shield

A lib that regulates the cache-based requests an application receives in terms of request-collapsing.

## Requirements

- Java 21 or later if you are a user.
- Kotlin 2.2 or later if you are a user.
- (If link this lib with Spring) Spring Boot 4.0 (Spring Framework 7.0) or later if you are a user. Spring Boot 3 is not supported.
- (If you use the Reactor modules) Project Reactor 3.8 or later; (if you use the coroutine modules) kotlinx.coroutines 1.10 or later.

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

### Kotlin with the Reactor modules

- Reactor 3.8 (the supported minimum) declares `Mono`'s type parameter non-null with JSpecify annotations, and the
  Kotlin compiler reports violations as errors by default. The Reactor-based API still uses nullable type arguments
  such as `Mono<ReqShieldData<T>?>` (`AsyncCache.get`, `ReqShieldConfiguration.getCacheFunction`), so Kotlin code
  that implements or calls it fails to compile without a compiler option.
- Until the API changes, compile such Kotlin code with `-Xjspecify-annotations=warn`, as the library's own Reactor
  modules and `req-shield-spring-boot4-webflux-example` do (see `reactorApiProjects` in the root build).
  Java callers and the coroutine modules are not affected.

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
- Use the same key source on the `@ReqShieldCacheable` method and the `@ReqShieldCacheEvict` method of an entry,
  because the sources do not write every value the same way. For one text argument `id`, the method-argument key and
  `key = "#id"` differ in two cases. The method-argument key writes a backslash twice (`tenant\alice` becomes
  `tenant\\alice`), and SpEL keeps the text as is. The method-argument key writes `null` as `\0`, and a SpEL key that
  is `null` fails the call. If the read uses the method arguments and the evict uses `key = "#id"`, the evict misses
  the entry of an `id` that holds a backslash, and the read keeps returning the old value. Set the same `key` on both methods:
  ```kotlin
  @ReqShieldCacheable(cacheName = "product", key = "#id")
  fun getProduct(id: String): Product

  @ReqShieldCacheEvict(cacheName = "product", key = "#id")
  fun removeProduct(id: String)
  ```
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
- In `core-reactor`, `core-kotlin-coroutine` and their Spring adapters, a request that is cancelled during its lock
  call calls the unlock function with the token of that call. The store may have taken the lock before the
  cancellation, and the token would otherwise be lost and the lock would stay until its timeout ends. So the unlock
  function can get a token that never took the lock: the compare-and-delete above then deletes nothing. If the unlock
  command reaches the store before the lock command, the lock still stays until its timeout ends.
- The example modules contain working implementations for `RedisTemplate`, `ReactiveRedisTemplate` and the coroutine
  extensions.
- A global lock also uses the local lock map of the JVM (see "Local lock map size"). Its entries have the cache key
  with the prefix `creation-front:` (the request that asks the lock store for a creation lock) or `refresh-attempt:`
  (the last request that asked for a refresh lock), and the number of the `ReqShield` instance in front of that.
  `req-shield.lock.max-entries` applies to them. Once the map is full, each request for a new key asks the lock store
  itself, as in versions before this change.
- These entries belong to one `ReqShield` instance. Two instances that use the same key with a global lock or a
  custom `KeyLock` never wait for each other inside the JVM: only their lock decides. The same holds for the refresh
  guard of `ONLY_CREATE_CACHE`. Instances with the local lock share the lock of a key, as before, because the local
  lock map is one map for the JVM.
- **One instance with a store for each request.** If the cache functions or the lock functions of one `ReqShield`
  (or of one cache bean) choose their store for each request - from the Reactor `Context`, the coroutine context
  or a `ThreadLocal`, one store for each tenant for example - name that store:
    - Core modules: set `storeScopeFunction` in `ReqShieldConfiguration`. It gets the Reactor `ContextView`
      (`core-reactor`) or the `CoroutineContext` (`core-kotlin-coroutine`) of the caller, and runs on the caller's
      thread in `core`.
    - Spring adapters: implement `StoreScopeSupport` on the cache bean, next to `GlobalLockSupport`.
    - Give the same text for requests that use the same store, and another text for another store. The function
      runs at most once for a request, not at all for a request that finds a fresh entry, and must not block.
  The locks and signals inside the JVM then have that text in their key, so requests of two stores never wait for
  each other there, and requests of one store still collapse. **Without it**, a request of one store can wait for
  a creation or a refresh that a request of another store runs for the same key. For a creation with a global
  lock it waits until its reads are used up (about 3 s with the defaults) and then gets its value from the
  supplier, without a cache entry. Version 1.2.0 had no such wait with a global lock, so set the function when you
  upgrade such a setup. With the local lock, the requests of all stores share the lock of a key, as in 1.2.0.

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
  that window comes back with the old value until its TTL ends. A request that finds the entry gone while a refresh
  of that key runs in the same JVM waits for that refresh, so it also gets the value that was computed before the
  eviction. The eviction and the write can run on different nodes, so the library cannot order them. If stale data after an update is not acceptable, use a short TTL or put a
  version of the data in the cache key.

### Lock timeouts and work modes

- If a supplier call takes longer than `lockTimeoutMillis`, the lock can pass to another request. Before such a late
  write, the cache is read one more time. If another request wrote the entry in that time, the late write is skipped:
  after a lock timeout the first write stays. In the usual case, one slow call that a later and faster call overtook,
  the entry that stays is the newer one. If two calls both outlast the lock timeout, the entry of the call that ended
  first stays, also when that call started first, because an entry does not hold the start time of its supplier call.
  A supplier that is faster than the lock timeout causes no extra read. Keep `lockTimeoutMillis` longer than the
  slowest supplier call.
- `reqShieldWorkMode` sets where requests are collapsed:
    - `CREATE_AND_UPDATE_CACHE` (default): on the creation and on the refresh of an entry, with the configured lock.
    - `ONLY_CREATE_CACHE`: the configured lock is used for the creation only. A refresh takes a local lock of this
      JVM, so one instance runs one refresh of a key at a time and the global lock gets no call for a refresh.
      Instances do not see each other's refreshes in this mode.
    - `ONLY_UPDATE_CACHE`: the configured lock is used for the refresh only. On a cache miss each request calls the
      supplier.

### Background refresh and the caller's context

- Once an entry is older than `decisionForUpdate` percent of its TTL, a read returns it and starts a refresh in the
  background, unless another request already holds the update lock of that key. The refresh calls the supplier on its
  own and can still be running after the caller has its data.
- Choose the TTL and `decisionForUpdate` so that a supplier call ends inside the refresh window. The window is the
  last part of the TTL: `timeToLiveMillis × (100 − decisionForUpdate) / 100`, which is 20 % of the TTL with the
  default of 80. A refresh that ends inside the window is not seen by any request: each read gets the old entry at
  once and the new entry after the write. If the supplier takes longer than the window, the entry expires before
  its refresh ends, and the requests for that key then wait for the refresh. If the supplier takes about as long as
  the TTL, the requests wait for most of each TTL: use a longer TTL, or a smaller `decisionForUpdate`.
- `core-reactor` and the WebFlux adapter subscribe the refresh with the caller's Reactor `Context`, so a supplier that
  reads it (for example `ReactiveSecurityContextHolder`) works in a refresh as it does in a request. A reactive
  transaction of the caller is part of that `Context` too; a supplier that must not run in it should not be refreshed
  from inside the transaction.
- In `core-reactor` and the WebFlux adapter, the cache functions and the lock functions of a global lock see the
  caller's Reactor `Context` too, on a cache miss and in a refresh. This includes the cache write and the lock
  release, which run in the background after the caller has its data. Functions that choose the cache or the lock
  store from the `Context` (one store for each tenant, for example) therefore write and release in the store that
  the read and the lock used. Such a setup must also name the store of a request: see "One instance with a store
  for each request" in "Global lock guidance".
- `core-kotlin-coroutine` and the coroutine adapter launch the refresh in the configured scope with the caller's
  coroutine context elements, except its `Job`, dispatcher and exception handler: the refresh is not cancelled with
  the request and does not inherit its dispatcher. For any other element the scope's own one takes precedence. A
  transaction of the caller that lives in its coroutine context (the Reactor `Context` of a reactive transaction) is
  handed over too; a supplier that must not run in it should not be refreshed from inside the transaction.
- `core` and the blocking Spring adapter run the refresh on the executor thread. `ThreadLocal` state of the caller
  (`SecurityContextHolder`, `RequestContextHolder`, MDC, a transaction) is **not** available there. Pass what the
  method needs as arguments, or supply a `reqShieldExecutor` that propagates the context your application needs.

### Waiting for another request (lock not acquired)

- A request that loses the lock reads the cache up to `maxAttemptGetCache` times (default 60), with at most 50 ms
  between two reads.
- If the request that holds the lock runs in the same JVM, the waiting request reads the cache as soon as that request
  gave the lock back: after its cache write, or after its supplier failed. It does not wait for the rest of the 50 ms.
  If the lock is a custom `KeyLock` that another component holds, the waiting request reads the cache every 50 ms.
- With a global lock (`isLocalLock = false`), one request of a JVM asks the lock store for the creation lock of a
  key. The other requests of that JVM for the key wait for that request and do not call the lock store. So a cache
  miss of 100 concurrent requests costs one lock call and one unlock call, not 101 lock calls.
- If a request of another JVM holds the global lock, that one request reads the cache for its JVM: 5, 15, 35 and
  75 ms after its first read, then every 50 ms. When it finds the entry, the other requests of its JVM read the cache
  at once. An entry is found at most one of these waits after it was written, so at most 50 ms after the write, as
  with the fixed interval. The reads fall at other times than before, so an entry of a slow supplier can be found
  up to 25 ms earlier or later than with the fixed interval. If the holder in the other JVM fails and writes no entry, the requests still wait until their reads
  are used up (about 3 s with the defaults), as before.
- If the holder gave the lock back and the cache still has no entry (the supplier of the holder failed, for example),
  the waiting request tries to get the lock itself. One waiting request becomes the next holder and calls the
  supplier; the others wait for it. So after a failed holder the supplier is called by one request at a time, not by
  all waiting requests at once.
- A request that finds no entry while a refresh of that key runs in the same JVM waits for that refresh in the same
  way, and does not call the supplier a second time. This happens when an entry expires before its refresh ends. If
  the refresh ends without an entry, the request goes on as on any cache miss. The request waits for a refresh only
  while the refresh can hold its lock: after `lockTimeoutMillis` it goes on as on any cache miss too, and with a
  global lock the next read of the old entry asks the lock store for a refresh again.
- The wait for a refresh has the limit of `maxAttemptGetCache` reads. All waits of one request for a holder share
  one such limit, so trying the lock again does not extend the wait.
- A cache read failure while waiting is logged and counted as a read. Three consecutive failures are treated as a
  cache outage and the request falls back to calling the supplier itself right away. A read that fails right after
  the holder gave the lock back is treated the same way: the request does not take the lock, which would only read
  the failing cache again.
- When its reads are used up the request calls the supplier once, without a lock. A supplier failure is propagated as
  a `ClientException(SUPPLIER_ERROR)` with the original exception as `cause`; it is never turned into a cached `null`.
- In `core-kotlin-coroutine`, a `CancellationException` thrown by the supplier or by a cache get/set function while
  the calling coroutine is still active (a `withTimeout` inside the supplier, for example) is a failure of that
  function. It is reported as `ClientException` like any other failure. Only the cancellation of the calling coroutine
  itself propagates unwrapped. A failing lock function is not wrapped: on a cache miss its exception reaches the
  caller as thrown (a `CancellationException` included), on a refresh it only skips that refresh, and a failed unlock
  is logged.

### Thread pools and schedulers

- `core` accepts any `java.util.concurrent.Executor` for its background cache writes; only `execute` is called and the
  library never shuts a caller-supplied pool down. The default is a shared daemon pool: a `ForkJoinPool` in FIFO mode
  with 2 threads for each CPU. The Spring adapter runs the
  writes on a daemon pool of its own, of the same kind, that is shut down with the context, and registers no `Executor` bean, so Spring
  Boot keeps its `applicationTaskExecutor`. To use your own pool, define an `Executor` bean named `reqShieldExecutor`;
  its lifecycle stays yours, and like any `Executor` bean it makes Spring Boot skip `applicationTaskExecutor`.
- `core-reactor` accepts a `Scheduler` (default `boundedElastic`). The Spring WebFlux adapter uses the shared
  `boundedElastic` too and never disposes it; to use your own, define a `Scheduler` bean named `reqShieldScheduler`.
  With the local lock, a read of an entry that is due for a refresh asks for the update lock on its own thread, and
  only the read that gets the lock hands the refresh to the scheduler. A global lock or a custom `KeyLock` is called
  on the scheduler.
- `core-kotlin-coroutine` accepts a `CoroutineScope` for background cache writes (default: a shared supervisor scope on
  `Dispatchers.IO`). The coroutine Spring adapter runs them on a supervisor scope of its own on `Dispatchers.IO` and
  cancels it on context shutdown; to use your own, define a `CoroutineScope` bean named `reqShieldCoroutineScope`.
  If the scope cannot start a background cache write or refresh (its dispatcher throws), that task is skipped with a
  warning and its lock is released; the caller still gets its data, as in `core` when the executor rejects the task.
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

## Benchmarks

The `req-shield-benchmark-example` module holds JMH benchmarks of the library. It is not published.

```bash
./gradlew :req-shield-benchmark-example:jmhJar
java -jar req-shield-benchmark-example/build/libs/req-shield-benchmark-example-*-jmh.jar -l        # list the benchmarks
java -jar req-shield-benchmark-example/build/libs/req-shield-benchmark-example-*-jmh.jar 'HitBenchmark' -t 8 -prof gc
```

- `HitBenchmark`, `MissBenchmark`, `StaleHitBenchmark`, `LockBenchmark`: the cost of one call on each path of `core`,
  against a plain cache-aside call. `Reactor*` and `Coroutine*` do the same for `core-reactor` and
  `core-kotlin-coroutine`, and `*AspectHitBenchmark` for the three Spring aspects.
- `StampedeBenchmark`, `ReadAfterMissBenchmark`, `ExpiryLoadBenchmark`, `SpringAspectStampedeBenchmark`: one invocation is
  one scenario run. The JMH score is the time of the run. The supplier call count and the latency of each request go
  to a JSON-lines file: `build/jmh-events.jsonl` in the working directory, or the path in `-Dbench.events`
  (pass it with `-jvmArgsAppend`).
- Choose the module and the side with parameters, for example
  `-p module=core,reactor,coroutine -p arm=cacheAside,reqShield -p supplierMillis=10,100`.
- The scenario benchmarks also take `-p lock=global` (lock functions on the store, not the lock map of the JVM) and
  `-p store=redis` (a Redis server as the cache and the lock store, at the URL in `-Dbench.redis`, default
  `redis://localhost:6379`). `RemoteHolderBenchmark` shows a waiter whose lock holder is in another JVM, and
  `ReactiveAspectStampedeBenchmark` a stampede through the WebFlux and the coroutine aspect.
- The cache of the benchmarks is a map in the JVM. A cache behind a network call takes far longer per call, so read a
  difference of some nanoseconds as an absolute cost, not as a ratio.

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to
change.
Please see [CONTRIBUTING.md](CONTRIBUTING.md) for contributing to Req-Shield.

## LICENSE

Apache License 2.0

## How to reach us

- File an issue in [the issue tracker](https://github.com/line/req-shield/issues) to report a bug or suggest an
  idea.
