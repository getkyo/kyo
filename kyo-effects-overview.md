# Kyo Effects & Primitives Overview

## Core Type: `A < S`

A Kyo computation producing a value of type `A` with pending effects `S`. Pure values are automatically lifted into `A < S`. Effects are eliminated by handling them (e.g., `Abort.run`, `Env.run`), and the final computation is executed via `KyoApp`.

---

## kyo-data — Pure Data Types

### Maybe[A]
Optional value — `Present[A]` or `Absent`. Allocation-free via opaque types over `Any`.
- `Maybe(v)`, `Maybe.fromOption(opt)`
- `.get`, `.getOrElse`, `.map`, `.flatMap`, `.filter`, `.fold`, `.isEmpty`, `.isDefined`
- `.toOption`, `.toResult`

### Result[E, A]
Three-state computation result: `Success[A]`, `Failure[E]` (expected), or `Panic` (unexpected exception). Combines `Either` + `Try`.
- `Result(expr)`, `Result.succeed(v)`, `Result.fail(e)`, `Result.panic(ex)`
- `.map`, `.flatMap`, `.fold`, `.mapError`, `.flatMapError`
- `.isSuccess`, `.isFailure`, `.isPanic`, `.toEither`, `.toMaybe`, `.toTry`
- `Result.Partial[E, A]` — narrower variant excluding `Panic`

### Chunk[A]
Immutable sequence with O(1) `take`, `drop`, `slice` via structural sharing.
- `Chunk(a, b, c)`, `Chunk.from(seq)`
- `.map`, `.flatMap`, `.filter`, `.take`, `.drop`, `.slice`, `.concat`, `.flatten`
- `.head`, `.last`, `.indexOf`, `.foldLeft`, `.toArray`

### Duration
Time duration as opaque `Long` (nanos).
- `Duration.fromNanos`, `Duration.parse`, `Duration.Zero`, `Duration.Infinity`
- `.toNanos`, `.toMillis`, `.toSeconds`, `.toMinutes`, `.toHours`
- Constructors: `5.seconds`, `100.millis`, `2.hours`, etc.

### Instant
Point in time wrapping `java.time.Instant`.
- `Instant.of(seconds, nanos)`, `Instant.parse(text)`, `Instant.Epoch`
- `.plus(d)`, `.minus(d)`, `.between(other)`, `.isAfter`, `.isBefore`

### Schedule
Composable scheduling policy for retries, delays, periodic tasks.
- `Schedule.fixed(d)`, `.exponentialBackoff(init, factor, max)`, `.immediate`, `.never`
- `.max(other)`, `.min(other)`, `.take(n)`, `.andThen(other)`, `.jitter(factor)`
- `.delay(d)`, `.repeat(n)`, `.next(now)` → `Maybe[(Duration, Schedule)]`

### Text
Efficient string type with O(1) concatenation via rope-like structure (opaque over `String | Op`).
- `Text(s)`, `Text.empty`
- `.length`, `.isEmpty`, `.take`, `.drop`, `.slice`, `.concat` (`++`), `.trim`
- `.indexOf`, `.contains`, `.startsWith`, `.endsWith`, `.split`

### TypeMap[A]
Type-safe heterogeneous map — values keyed by their types.
- `TypeMap.empty`, `TypeMap(a, b, c)`
- `.get[B]`, `.add[B](value)`, `.union(other)`, `.isEmpty`, `.size`

### Record[F]
Type-safe immutable record with intersection-typed fields (`"name" ~ String & "age" ~ Int`).
- `Record("name" ~ "Alice", "age" ~ 30)`, `Record.fromProduct(caseClass)`
- `.selectDynamic` (e.g., `record.name`), `.getField[Name]`, `.&(other)`, `.update(name, value)`
- `.compact`, `.toProduct[T]`

### Dict[K, V]
Immutable dictionary. Flat `Span` for ≤8 entries, `HashMap` for larger.
- `Dict("a" -> 1)`, `Dict.empty`, `Dict.from(map)`
- `.apply(key)`, `.get(key)`, `.contains(key)`, `.update(key, value)`, `.remove(key)`
- `.map`, `.filter`, `.foldLeft`, `.foreach`, `.keys`, `.values`, `.size`

### Tag[A]
Runtime type tag for safe type-level operations. Used throughout Kyo for effect identification.
- `Tag[A]`, `tag.show`, `tag <:< other`, `tag =:= other`

### Frame
Compile-time source location (file, line, method). Used for error reporting.

---

## kyo-kernel — Effect System Foundation

### Kyo (companion object)
Utility functions for sequential operations on collections with effects.
- `Kyo.lift[A](v)`, `Kyo.none`
- `Kyo.foreach`, `Kyo.filter`, `Kyo.collect`, `Kyo.foldLeft`, `Kyo.fill`
- `Kyo.zip`, `Kyo.foreachDiscard`

### Loop
Stack-safe looping construct.
- `Loop(init)(step)`, `Loop.indexed(f)`, `Loop.foreach(seq)(f)`
- Returns `Loop.Outcome` — `Continue(state)` or `Done(result)`

---

## kyo-prelude — Pure Effects

### Abort[E]
Typed error handling — short-circuit with `Failure[E]` or `Panic(Throwable)`.
- `Abort.fail(e)`, `Abort.panic(ex)`, `Abort.when(cond)(e)`, `Abort.catching[E](expr)`
- `Abort.get(either)`, `Abort.get(option)`, `Abort.get(result)`
- `Abort.run[E](v)` → `Result[E, A] < S`
- `Abort.recover[E](handler)(v)`, `Abort.fold[E](onSuccess, onFail)(v)`

### Env[R]
Dependency injection via typed environment.
- `Env.get[R]` — retrieve a dependency
- `Env.run[R](value)(v)` — provide a dependency
- `Env.runLayer(layers*)(v)` — provide dependencies via layers
- `Env.use[R](f)` — use a dependency in a function

### Var[V]
Mutable state threaded through computations.
- `Var.get[V]`, `Var.set[V](value)`, `Var.update[V](f)`
- `Var.run[V](init)(v)` → `A < S`, `Var.runTuple[V](init)(v)` → `(V, A) < S`
- `Var.isolate.update`, `Var.isolate.merge(f)`, `Var.isolate.discard`

### Emit[V]
Accumulating output values (writer effect).
- `Emit.value(v)`, `Emit.valueWhen(cond)(v)`
- `Emit.run[V](v)` → `(Chunk[V], A) < S`
- `Emit.runFold[V](init)(f)(v)`, `Emit.runDiscard`, `Emit.runFirst`
- `Emit.isolate.merge`, `Emit.isolate.discard`

### Choice
Non-deterministic computation — branch into multiple values.
- `Choice.eval(a, b, c)`, `Choice.evalSeq(seq)`, `Choice.drop`, `Choice.dropIf(cond)`
- `Choice.run(v)` → `Chunk[A] < S`

### Check
Assertion/validation effect — collect or abort on failures.
- `Check.require(cond)`, `Check.require(cond, message)`
- `Check.runAbort(v)` → fails on first check failure
- `Check.runChunk(v)` → collects all failures as `(Chunk[CheckFailed], A)`
- `Check.runDiscard(v)` → ignores failures

### Local[A]
Fiber-local (inherited) or noninheritable values.
- `Local.init[A](default)`, `Local.initNoninheritable[A](default)`
- `.get`, `.use(f)`, `.let(value)(v)`, `.update(f)(v)`

### Memo
Memoization effect — caches function results within a computation.
- `Memo(f)` — wraps `A => B < S` into memoized version
- `Memo.run(v)` — execute with memoization cache

### Layer[Out, S]
Dependency graph construction and initialization for `Env`.
- `Layer(kyo)`, `Layer.from[A, B](f)`, `Layer.init[Target](layers*)`
- `.to(other)`, `.and(other)`, `.using(other)`
- `Env.runLayer(layers*)(v)` — auto-wires dependencies

### Stream[V, S]
Lazy, pull-based sequence of values with composable transformations.
- `Stream(values*)`, `Stream.init(chunk)`, `Stream.range(from, to)`
- `.map`, `.flatMap`, `.filter`, `.take`, `.drop`, `.concat`
- `.takeWhile`, `.dropWhile`, `.collect`, `.tap`, `.mapChunk`
- `.run` (to `Chunk`), `.runDiscard`, `.runFold`, `.runForeach`

### Emit + Poll + Pipe + Sink (Streaming Primitives)
Low-level building blocks for Stream:
- **Poll[V]** — pull a value: `Poll.one`, `Poll.values(n)(f)`, `Poll.run(inputs)(v)`, `Poll.fold`
- **Pipe[A, B, S]** — transform a stream: `Pipe.identity`, `Pipe.map`, `Pipe.take`, `Pipe.drop`, `.join(other)`
- **Sink[V, A, S]** — consume a stream: `Sink.collect`, `Sink.discard`, `Sink.count`, `Sink.fold`, `Sink.foreach`, `.drain(stream)`

### Aspect[I, O, S]
AOP-style interception of effectful functions.
- `Aspect.init[I, O, S]`
- `.apply(input)(cont)` — invoke the aspect
- `.let(cut)(v)` — install a cut (interceptor) around a computation
- `.sandbox(v)` — run without any active cuts

### Batch
Automatic batching of individual calls into bulk operations.
- `Batch.source[A, B](f)` — define a batched source from `Seq[A] => (A => B) < S`
- `Batch.sourceMap[A, B](f)`, `Batch.sourceSeq[A, B](f)`
- `Batch.eval[A](seq)` — evaluate a sequence within batch context
- `Batch.run(v)` → `Chunk[A] < S`

---

## kyo-core — Concurrent Effects & Primitives

### Async
Asynchronous computation — fibers, scheduling, concurrency.
- `Async.defer(v)` — suspend a computation
- `Async.sleep(d)`, `Async.delay(d)(v)`, `Async.timeout(d)(v)`
- `Async.race(a, b)`, `Async.gather(tasks)`, `Async.fill(n)(v)`
- `Async.foreach(seq)(f)`, `Async.foreachDiscard(seq)(f)`, `Async.filter(seq)(f)`
- `Async.zip(a, b, ...)` — concurrent zip
- `Async.never` — never completes

### Sync
Pure effect suspension — marks computations that need IO but not fiber scheduling.
- `Sync.defer(v)` — suspend a side-effecting computation
- `Sync.ensure(finalizer)(v)` — run finalizer on completion

### Fiber[A, S]
A lightweight virtual thread executing a computation.
- `Fiber.init(v)` — fork a new fiber (scoped)
- `Fiber.initUnscoped(v)` — fork without scope tracking
- `.get` — await result, `.getResult` — await as `Result`
- `.map(f)`, `.flatMap(f)`, `.interrupt`, `.block(timeout)`

### Scope
Resource lifecycle management — guarantees cleanup.
- `Scope.run(v)` — execute with resource tracking
- `Scope.close(resource)`, `Scope.ensure(finalizer)`

### Channel[A]
Bounded async channel for inter-fiber communication.
- `Channel.init[A](capacity)`
- `.put(v)`, `.take`, `.poll` (non-blocking), `.close`
- `.size`, `.pendingPuts`, `.pendingTakes`, `.empty`, `.full`
- `.stream` — consume as `Stream`

### Queue[A]
Concurrent queue (unbounded, bounded, or dropping).
- `Queue.init[A](capacity)`, `Queue.initUnbounded[A]`, `Queue.initDropping[A](capacity)`
- `.add(v)`, `.poll`, `.peek`, `.size`, `.close`
- `.stream` — consume as `Stream`

### Hub[A]
Pub-sub broadcast channel — each listener gets all messages.
- `Hub.init[A](capacity)`
- `.publish(v)`, `.listen(capacity)` → `Channel[A]`
- `.close`, `.size`

### Signal[A]
Reactive variable that fibers can watch for changes.
- `Signal.init[A](value)`
- `.get`, `.set(v)`, `.update(f)`, `.watch` — await next change

### Latch
One-shot synchronization barrier.
- `Latch.init(count)`
- `.release` — decrement count, `.await` — wait until zero

### Barrier
Cyclic synchronization point for N fibers.
- `Barrier.init(parties)`
- `.await` — wait for all parties to arrive

### Meter
Rate limiting and concurrency control.
- `Meter.initRateLimiter(rate, period)`, `Meter.initConcurrencyLimiter(max)`
- `Meter.initMutex` — single-permit concurrency limiter
- `.run(v)` — execute within meter constraints, `.tryRun(v)`
- `.available`, `.close`

### Atomic[A]
Lock-free atomic reference.
- `Atomic.init[A](value)`
- `.get`, `.set(v)`, `.cas(expected, newValue)`, `.update(f)`, `.getAndSet(v)`
- `AtomicInt.init`, `AtomicLong.init`, `AtomicBoolean.init`

### Adder
Lock-free concurrent accumulator (striped for high throughput).
- `Adder.initLong`, `Adder.initDouble`
- `.add(v)`, `.increment`, `.decrement`, `.get`, `.reset`

### Clock
Time operations.
- `Clock.now` → `Instant < Sync`
- `Clock.live`, `Clock.withTimeShift(shift)(v)`, `Clock.withTimeControl(v)`

### Console
Standard I/O.
- `Console.readLine`, `Console.print(v)`, `Console.println(v)`, `Console.printErr(v)`
- `Console.live`, `Console.let(impl)(v)`

### Log
Structured logging.
- `Log.trace(msg)`, `Log.debug(msg)`, `Log.info(msg)`, `Log.warn(msg)`, `Log.error(msg)`
- Each accepts optional `Throwable` parameter

### Random
Random number generation.
- `Random.nextInt`, `Random.nextInt(bound)`, `Random.nextLong`, `Random.nextDouble`
- `Random.nextBoolean`, `Random.nextFloat`, `Random.nextGaussian`
- `Random.shuffle(seq)`, `Random.unsafe` — get `Random.Unsafe`
- `Random.live`, `Random.let(impl)(v)`

### System
System property and environment variable access.
- `System.property(name)`, `System.env(name)`
- `System.lineSeparator`, `System.userName`, `System.operatingSystem`
- `System.live`, `System.let(impl)(v)`

### Retry
Retry with configurable schedule.
- `Retry[E](schedule)(v)` — retry on `Abort[E]` failures using schedule

### Timeout
Timeout marker effect (eliminated by `Async.timeout`).

### Admission
Probabilistic load shedding.
- `Admission.reject(v)` — reject with probability `v`
- `Admission.allowAll(v)`, `Admission.rejectAll(v)`

### Access
Read/write access control markers for shared state.

### Stat (Counter, Histogram, Gauge)
Metrics collection.
- `Stat.initCounter(name)`, `Stat.initHistogram(name)`, `Stat.initGauge(name)(v)`
- Counter: `.inc`, `.add(v)`, `.get`
- Histogram: `.observe(v)`, `.count`, `.valueAtPercentile(p)`
- Gauge: `.collect`

### KyoApp
Application entry point — handles `Async & Scope & Abort[Throwable]`.
- `KyoApp { run(computation) }`
- `KyoApp.runAndBlock(timeout)(v)` — blocking execution

---

## kyo-direct — Direct Syntax

### direct { ... }
Scala CPS-based direct syntax for writing effectful code imperatively.
- `direct { val x = myEffect.now; x + 1 }` — `.now` sequences effects
- `.later` — advanced API for controlled composition

---

## kyo-combinators — Extension Methods

Convenience extension methods on `A < S` for common effect handling patterns:
- **Abort**: `.result`, `.resultPartial`, `.someOrFail(e)`, `.noneOrFail(e)`
- **Async**: `.fork`, `.forkUsing(f)`, `.andThen(other)`
- **Emit**: `.emitValue`, `.emitAll`
- **Env**: `.provide(dep)`, `.provideAs[R](dep)`
- **Choice**: `.chooseFrom`
- **Stream**: `.sink(s)`, `.pipe(p)`

---

## kyo-stm — Software Transactional Memory

### STM
Optimistic concurrency via transactions — atomic, isolated, composable.
- `STM.run(v)`, `STM.run(retrySchedule)(v)` — execute a transaction
- `STM.retry`, `STM.retryIf(cond)` — manually retry

### TRef[A]
Transactional reference.
- `TRef.init(value)`
- `.get`, `.set(v)`, `.update(f)`, `.use(f)` — all within `STM`

### TMap[K, V]
Transactional map — nested `TRef` structure for fine-grained concurrency.
- `TMap.init(entries*)`, `TMap.init(map)`
- `.get(key)`, `.put(key, value)`, `.remove(key)`, `.contains(key)`
- `.updateWith(key)(f)`, `.size`, `.isEmpty`, `.clear`

### TChunk[A]
Transactional sequence.
- `TChunk.init(values*)`, `TChunk.init(chunk)`
- `.get(i)`, `.append(v)`, `.take(n)`, `.drop(n)`, `.filter(p)`
- `.head`, `.last`, `.size`, `.isEmpty`

### TTable[F]
Transactional table — typed record storage with auto-generated IDs.
- `TTable.init[F]`
- `.insert(record)` → `Id`, `.get(id)`, `.update(id, record)`, `.upsert(id, record)`
- `.remove(id)`, `.size`, `.snapshot`

---

## kyo-cache — Caching

### Cache
Caffeine-backed memoization with isolated keyspaces.
- `Cache.init(maxSize, expireAfterWrite, ...)` via `Cache.Builder`
- `.memo(f)`, `.memo2(f)`, `.memo3(f)`, `.memo4(f)` — memoize functions

---

## kyo-actor — Actor Model

### Actor[E, A, B]
Message-based concurrency with mailbox, sequential processing, parent-child hierarchies.
- `Actor.run(capacity)(handler)` — spawn an actor
- `.subject` — get `Subject[A]` for sending messages
- `.fiber` — underlying fiber, `.await` — await completion, `.close` — graceful shutdown

### Subject[A]
Message-sending interface.
- `.send(msg)` — reliable send (may block), `.trySend(msg)` — non-blocking
- `.ask(f)` — request-response pattern

### Actor utilities
- `Actor.self[A]` — get own subject within handler
- `Actor.receiveAll(f)`, `Actor.receiveLoop(f)`, `Actor.receiveMax(n)(f)`

---

## kyo-offheap — Off-Heap Memory

### Memory[A]
Type-safe off-heap memory with Arena-scoped lifecycle.
- `Memory.init[A](size)`, `Memory.initWith[A](size)(f)`
- `.get(i)`, `.set(i, v)`, `.fill(v)`, `.fold(zero)(f)`, `.findIndex(p)`
- `.view(from, len)`, `.copy(from, len)`, `.copyTo(target, ...)`

### Arena
Resource scope for off-heap allocations — auto-frees on exit.
- `Arena.run(v)` — execute with arena-managed memory

---

## kyo-parse — Parser Combinators

### Parse[In]
Composable text parsing effect.
- `Parse.run(input)(parser)` — execute parser
- `Parse.readOne(f)`, `Parse.readWhile(f)`, `Parse.read(f)` — basic readers
- `Parse.literal(v)`, `Parse.any`, `Parse.anyIn(values)`, `Parse.anyNotIn(values)`
- `Parse.firstOf(parsers)` — ordered alternatives
- `Parse.inOrder(a, b, ...)` — sequential composition
- `Parse.repeat(parser)`, `Parse.attempt(parser)`, `Parse.peek(parser)`
- `Parse.spaced(parser)`, `Parse.fail(msg)`, `Parse.position`, `Parse.rewind(pos)`

---

## kyo-playwright — Browser Automation

### Browser
Playwright-based browser automation effect.
- `Browser.run(v)`, `Browser.run(timeout)(v)`, `Browser.run(page, timeout)(v)`
- `Browser.goto(url)`, `Browser.back`, `Browser.forward`, `Browser.reload`
- `Browser.click(selector)`, `Browser.doubleClick(selector)`, `Browser.hover(selector)`
- `Browser.fill(selector, value)`, `Browser.type_(selector, text)`
- `Browser.screenshot`, `Browser.content`, `Browser.title`

---

## kyo-http — HTTP Client & Server *(PR [#1479](https://github.com/getkyo/kyo/pull/1479))*

Cross-platform (JVM/JS/Native) HTTP/1.1 with type-safe routes, JSON support, streaming, and OpenAPI.

### HttpClient
HTTP client with connection pooling, retries, redirects.
- `HttpClient.getText(url)`, `HttpClient.getJson[A](url)`, `HttpClient.getBinary(url)`
- `HttpClient.postJson[A](url, body)`, `HttpClient.putJson[A](url, body)`
- `HttpClient.getSseJson[A](url)`, `HttpClient.getNdJson[A](url)` — streaming
- `HttpClient.withConfig(f)(v)` — configure base URL, timeouts, retries
- `HttpClient.init(backend, maxConns, idleTimeout)` — custom instance

### HttpServer
HTTP server with route-based dispatch.
- `HttpServer.run(port)(handlers*)` — start server
- `HttpServer.run(config)(handlers*)` — start with config

### HttpHandler
Route definition + request handling.
- `HttpHandler.getText(method, path)(f)`, `HttpHandler.getJson[A](method, path)(f)`
- `HttpHandler.postJson[A, B](method, path)(f)` — typed request/response

### HttpRoute
Type-safe route descriptors with Record-typed fields.
- `HttpRoute.get(path)`, `HttpRoute.post(path)`, etc.
- `.query[A](name)`, `.header[A](name)`, `.body[A]`
- `.handle(f)` — attach handler

### HttpFilter
Composable middleware.
- `HttpFilter(f)`, `HttpFilter.identity`
- `.andThen(other)`, `.apply(handler)`

### Json
JSON codec derivation.
- `derives Json` on case classes/sealed traits
- `Json.encode[A](v)`, `Json.decode[A](text)`

### Other HTTP types
- **HttpRequest** — method, URL, headers, body
- **HttpResponse** — status, headers, body
- **HttpHeaders** — typed header access
- **HttpStatus** — status codes with helpers (`.isSuccess`, `.isServerError`, etc.)
- **HttpUrl** — parsed URL with query params
- **HttpCookie** — cookie parsing/building
- **HttpSseEvent[A]** — server-sent event wrapper

---

## Integration Modules

| Module | Description |
|---|---|
| **kyo-cats** | Interop with Cats Effect (`IO ↔ Kyo`) |
| **kyo-zio** | Interop with ZIO (`ZIO ↔ Kyo`) |
| **kyo-reactive-streams** | Reactive Streams (`Publisher/Subscriber ↔ Stream`) |
| **kyo-caliban** | GraphQL via Caliban |
| **kyo-sttp** | HTTP client via sttp *(planned for removal)* |
| **kyo-tapir** | HTTP server via tapir *(planned for removal)* |
| **kyo-logging-slf4j** | Log backend via SLF4J |
| **kyo-logging-jpl** | Log backend via Java Platform Logging |
| **kyo-stats-otel** | Metrics export via OpenTelemetry |
| **kyo-stats-registry** | In-process metrics registry |
| **kyo-scheduler-cats** | Cats Effect scheduler integration |
| **kyo-scheduler-zio** | ZIO scheduler integration |
| **kyo-scheduler-pekko** | Pekko scheduler integration |
| **kyo-scheduler-finagle** | Finagle scheduler integration |
