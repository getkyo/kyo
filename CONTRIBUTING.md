# Contributing to Kyo

Thank you for considering contributing to Kyo! We welcome all contributions — bug reports, feature requests, documentation improvements, and code.

Kyo optimizes for **clarity, performance, and consistency**. The codebase avoids abstraction for its own sake — every pattern exists because it solves a concrete problem. Code should read like it was written by one person, even across dozens of contributors.

This guide covers everything from environment setup to coding style. When in doubt, read existing code in `kyo-kernel`, `kyo-prelude`, and `kyo-core` — consistency with the codebase beats any rule written here.

---

## Getting Started

### Prerequisites

- **Java 21**
- **Scala**
- **Node**
- **sbt** (Scala Build Tool)
- **Git**

### Setting Up Your Environment

1. **Fork the Repository** — click **Fork** on the kyo GitHub page.

2. **Clone Your Fork**
   ```sh
   git clone https://github.com/your-username/kyo.git
   cd kyo
   ```

3. **Set Up Upstream Remote**
   ```sh
   git remote add upstream https://github.com/getkyo/kyo.git
   ```

### Configuring Java Options

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
```

If your system has more RAM (e.g., 16GB), increase heap sizes: `-Xms4G -Xmx8G`. Add these to `.bashrc` or `.zshrc` for persistence.

### Building and Testing

```sh
sbt '+kyoJVM/test'            # JVM tests
sbt '+kyoJS/test'             # JS tests
sbt '+kyoNative/test'         # Native tests
sbt 'scalafmtCheckAll'        # Format check
```

### Where to Add Code

| Subproject        | Use for                                                   |
| ----------------- | --------------------------------------------------------- |
| `kyo-data`        | Data structures (`Chunk`, `Maybe`, `Result`, etc.)        |
| `kyo-prelude`     | Effect types without `Sync` (`Abort`, `Env`, `Var`, etc.) |
| `kyo-core`        | Methods requiring `Sync`, `Async`, or `Scope`             |
| `kyo-combinators` | Extensions or composition helpers                         |

Add corresponding tests in the same subproject. Example: a new `Stream.fromSomething` method goes in `kyo-core/.../StreamCoreExtensions.scala` if it uses `Sync`, or `kyo-prelude/.../Stream.scala` if it doesn't.

---

## Submitting Your Contribution

### Opening a Pull Request

1. Create a branch: `git checkout -b feature-branch`
2. Make your changes, ensure tests pass
3. Commit: `git commit -m "Describe your changes"`
4. Push: `git push origin feature-branch`
5. Open a PR on GitHub with a clear title and description

### Proposing a New Method

If you want to contribute a new method like `S.newMethod` or `s.newMethod`, feel free to:

- Open an issue
- Discuss on Discord: [https://discord.gg/afch62vKaW](https://discord.gg/afch62vKaW)
- Share design examples: use cases, equivalents in ZIO or Cats Effect, motivating patterns

### Bounties

Check available bounties in **Issues** with the `💎 Bounty` label ([link](https://github.com/getkyo/kyo/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22%F0%9F%92%8E%20Bounty%22)). Comment on the issue to express interest, follow this guide, and submit a PR.

### LLM Use

We encourage contributors to leverage LLMs responsibly:
- Do **not** submit low-effort, AI-generated code without review
- If you use AI assistance, ensure the submission is well-tested and meets our standards
- Automated PRs without human oversight may be closed

---

## Core Principles

These are the axioms. Everything else in this guide derives from them.

1. **Source files are documentation.** Every `.scala` file is meant to be read top-to-bottom. Method ordering, scaladocs, section separators, and the flow from public API down to internals all serve readability. A contributor opening a file for the first time should understand the type's purpose and usage patterns just by reading the file — before touching any external docs. Treat the ordering and structure of a file as carefully as you treat the implementation.

2. **Most-used first.** Within each section, prioritize what users reach for most. Factory methods before configuration, `run` before `runWith`, simple overloads before complex ones. Discoverability beats alphabetical order.

3. **Action verbs, not theory.** `foreach` not `traverse`, `run` not `unsafeRunSync`. Accessible naming lowers the barrier to entry and keeps the API approachable for developers who don't have category theory backgrounds.

3. **Performance is a feature.** Avoid allocations, avoid unnecessary suspensions, use `inline` and opaque types. Zero-cost abstractions aren't optional — they're the reason Kyo can be both safe and fast.

4. **Composition over inheritance.** Delegate, don't extend. No `protected`, no deep hierarchies. Build complex behavior by combining simple pieces.

5. **Type safety first, escape hatches as last resort.** Write type-safe code by default. `asInstanceOf` and `@unchecked` are acceptable only when they're strictly necessary inside opaque type boundaries or kernel internals where the type system can't express a known invariant — never as a convenience shortcut. Never use `@uncheckedVariance`. `Frame`, `Tag`, and `AllowUnsafe` guard the public surface where users interact.

6. **Symmetry across related types.** Paired or complementary types should share the same structural patterns (factory methods, config, lifecycle) and naming conventions. Keep names consistent — if one type uses `close`, the paired type should too. Maintain the three-variant lifecycle shape, the `Config` case class with fluent setters and `val default`, the `init`/`initUnscoped` factory split, and shared data types consistent across the pair. When users learn one side, the other should feel familiar.

7. **Explain the surprising, skip the obvious.** A comment on a race condition is essential. A comment on `get` returning a value is noise.

---

## Everyday Code Rules

### Naming

| Don't write | Write instead | Why |
|---|---|---|
| `traverse` | `foreach` | Describes the action, not the structure |
| `sequence` | `collectAll` | Says what it does |
| `pure` / `succeed` | `Kyo.lift` or rely on implicit lifting | No ceremony for the common case |
| `void` / `as(())` | `.unit` | Direct |
| `*>` / `>>` | `.andThen` | Readable without memorizing operators |
| `replicateM` | `fill` | Plain English |
| `filterA` | `filter` | Same name as stdlib |
| `foldM` | `foldLeft` | Same name as stdlib |
| `bracket` | `acquireRelease` | Says what it does |
| `provide` / `provideLayer` | `Env.run` / `Env.runLayer` | Consistent `run` pattern |

**No symbolic operators** in `kyo-data`, `kyo-prelude`, or `kyo-core`. Use named methods (`.andThen`, `.unit`, `.map`). Symbolic operators like `*>`, `<*>`, `<&>` live exclusively in `kyo-combinators` for users who prefer that style.

Effect operations follow consistent naming:
- **`run`** eliminates an effect: `Abort.run`, `Var.run`, `Emit.run`, `Choice.run`, `Env.run`. Common handler variants beyond `run`:
  - `runWith(v)(continue)` — canonical handler with continuation
  - `runTuple` — returns `(State, A)` tuple
  - `runDiscard` — discards emitted/intermediate values
  - `runFirst` — handles only the first occurrence
  - `runPartial` / `runPartialOrThrow` — handles a subset of a union error type
  - `recover` / `recoverError` — recovers from errors with a fallback
  - `catching` — catches exceptions and converts to effect errors
  - `fold` / `foldError` — maps all result cases to a single return type
- **`get`** / **`use`** access a value: `Env.get` / `Env.use`, `Var.get` / `Var.use`
- **`fail`** / **`panic`** introduce errors: `Abort.fail`, `Abort.panic`
- **`init`** / **`initWith`** / **`use`** / **`initUnscoped`** / **`initUnscopedWith`** create resources with increasing lifecycle management. Choose `init` (Scope-managed) by default. See "Resource Factory Convention" in the Unsafe Boundary section for the full delegation chain
- **`fooPure`** suffix for pure (non-effectful) variants: `mapPure`, `filterPure`, `collectPure`, `contramapPure`. The pure version avoids suspension overhead. Used consistently across `Stream`, `Pipe`, and `Sink`.
- **`fooDiscard`** drops the return value: `offer` returns `Boolean`, `offerDiscard` returns `Unit`. Same for `complete`/`completeDiscard`, `interrupt`/`interruptDiscard`, etc.
- **`offer`** / **`poll`** are synchronous (immediate return); **`put`** / **`take`** are async (may suspend). The async version tries the sync version first and only suspends on failure.
- **`noop`** / **`Noop`** for degenerate cases (formally, the identity implementation): `Latch(0)` returns a pre-completed noop, `Meter.Noop` is a no-op meter that passes through without rate-limiting. This is both an optimization (avoids allocating real state when nothing will happen) and a naming convention for when you need an identity/pass-through implementation of a type.

### Pending Type (`A < S`)

- [ ] Use **`.map`**, never `.flatMap` — they are identical on the pending type. `flatMap` exists only for for-comprehension syntax. The codebase has zero explicit `.flatMap` calls on pending types.
- [ ] Use **`.andThen(next)`** to discard a result and sequence, not `.map(_ => next)`.
- [ ] Use **`.unit`** to discard a result to `Unit < S`.
- [ ] **Prefer `.map` chains** over for-comprehensions — but use a for-comprehension when it genuinely helps readability (e.g., many dependent steps).
- [ ] Use **`.handle(Abort.run, Env.run(x))`** for left-to-right handler pipelines instead of nested `Env.run(x)(Abort.run(computation))`.
- [ ] **Prefer `Abort.recover`** over `Abort.run` + `Result` pattern matching. Use `.handle(Abort.recover[E](onFail))` or `.handle(Abort.recover[E](onFail, onPanic))` instead of `Abort.run[E](computation).map { case Result.Success(...) => ... }`.

### Performance

- [ ] **Classes must be `final`** unless `sealed` (closed hierarchy) or `abstract` (anonymous instances needed). `final` enables JVM devirtualization and inlining.
- [ ] **Provide pure-function variants** when a transformation doesn't need effect suspension. Example: `Stream.mapPure` alongside `Stream.map` — the pure version avoids suspension overhead entirely.
- [ ] **Single-element optimization** before Loop. Every collection operation checks:
  ```scala
  source match
      case Nil          => Chunk.empty           // empty: return immediately
      case head :: Nil  => f(head).map(Chunk(_)) // single: avoid Loop setup
      case list         => Loop.indexed(...)      // general case
  ```
- [ ] **Use opaque types** — never wrap when you can alias. All core types (`<`, `Queue`, `Channel`, `Fiber`, `Context`) are opaque for zero runtime cost.
- [ ] **Use `inline` strategically** — inline the creation path, not the handling path. The goal is zero overhead where effects are born, while keeping handlers as normal methods to avoid code bloat. See the "Inline Guidelines" section below for the full decision framework.
- [ ] **Avoid impure language features** — `var`, `while`, `return`, mutable collections, and `null` are strongly discouraged. Use `@tailrec` recursive functions instead of `var`+`while` loops, `val` instead of `var`, immutable collections, and `Maybe`/`Option` instead of `null`. These impure features may be used only when strictly necessary for performance (e.g., hot-path parsers, opaque type internals over arrays). Pure progress should be tail-recursive; allocate continuations (`KyoContinue`) only when effects force suspension.
- [ ] **Bit-pack atomically-updated composite state** to avoid wrapper allocations. Always include a layout comment:
  ```scala
  // Bit allocation:
  // Bits 0-15 (16 bits): depth (0-65535)
  // Bit 16 (1 bit): hasInterceptor flag
  // Bits 17-63 (47 bits): threadId
  ```
- [ ] **Avoid the erased tag pattern** — existing usages are tech debt from when `Tag` had limitations with variant effects. `Tag` no longer has this limitation, so do not introduce new instances:
  ```scala
  // tech debt — do not copy
  private inline def erasedTag[E]: Tag[Abort[E]] = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]
  ```

### Zero-Cost Type Design

Kyo achieves zero-cost abstractions through opaque types. When designing a new type, choose the strategy that eliminates allocation on the hot path:

| Strategy | Example | Wraps | When to use |
|---|---|---|---|
| Opaque over primitive | `Duration = Long` | Raw primitive | Numeric quantities (time, size, count) |
| Opaque over JVM type | `Instant = JInstant` | Existing class | Wrapping a well-tested JVM type with a safer/simpler API |
| Opaque over union | `Maybe[A] = Absent \| Present[A]` | Union of subtypes | Discriminated types where the success path avoids boxing |
| Opaque over array | `Span[A] = Array[? <: A]` | Mutable array | Immutable view of array data without copying |
| Opaque with lazy ops | `Text = String \| Op` | String or deferred operation | When operations can be deferred until materialization |
| Opaque over Unsafe | `Channel[A] = Channel.Unsafe[A]` | Unsafe implementation | Concurrent types with safe/unsafe tiers (see Unsafe Boundary) |

**Structuring an opaque type:**
- Define the opaque type and its companion in the same file
- Expose the safe API via **extension methods** in the companion — not methods on a class
- Factory methods in the companion validate input: `Maybe(null)` returns `Absent`, `Duration.fromNanos` clamps negatives
- Internal code accesses the underlying value via pattern matching on union members or direct use within the opaque boundary
- Avoid exposing the underlying representation; if escape hatches are needed, use `private[kyo]`

**Given instances for new types** — provide as applicable:
- `CanEqual` — required if the type supports `==`/`!=` (strict equality is enabled project-wide)
- `Render` — for human-readable display
- `Ordering` — if the type is naturally sortable
- `Flat` — if the type can appear as a value in `A < S` (most types need this)
- `Tag` — automatically derived; only customize if the type has special encoding

**Sealed trait vs opaque type:**
- Use **opaque type** when you want zero-cost wrapping of an existing representation
- Use **sealed trait/enum** when you need pattern matching on cases or case class features (structural equality, copy)
- Both can coexist: `Result` is an opaque union whose members (`Success`, `Failure`, `Panic`) are sealed subtypes

### Inline Guidelines

`inline` is powerful but creates code bloat when overused. The codebase follows a deliberate strategy: **inline the creation path (where effects are born), not the handling path (where effects are processed)**.

**DO inline:**

| Category | Examples | Why |
|---|---|---|
| Effect suspend/create calls | `Abort.fail`, `Var.set`, `Emit.value`, `Choice.evalSeq` | Direct calls to `ArrowEffect.suspend`/`suspendWith` — must be zero-cost |
| Thin wrappers and redirects | `Var.get` → `use(identity)`, `Maybe.isDefined` → `!isEmpty` | Compiler folds them away entirely |
| Kernel framework entry points | `ArrowEffect.handle`, `ArrowEffect.suspend` | Backbone of the effect system; enables compile-time specialization |
| Simple predicates and branches | `Maybe.getOrElse`, `Maybe.filter`, `Abort.when` | Lets the compiler optimize branches at each call site |
| `private[kyo]` hot-path helpers | `Var.runWith`, internal handler implementations | Optimizes internal glue without affecting public API size |

**DO NOT inline:**

| Category | Examples | Why |
|---|---|---|
| Public effect handlers/runners | `Abort.run`, `Var.run`, `Emit.runFold`, `Choice.run` | These call back into the kernel; inlining would duplicate complex handler logic at every call site |
| Complex pattern matching | `Maybe.get` (throws on Absent) | Significant code in the match — duplicating it is wasteful |
| Collection operations | `Maybe.toList`, `Maybe.zip`, `Maybe.iterator` | No performance benefit to inlining; these already allocate |
| Methods with exception handling | Operations that construct and throw exceptions | Exception metadata shouldn't be duplicated |

**The pattern in practice** — look at any effect like `Abort`:
- `Abort.fail`, `Abort.panic`, `Abort.get`, `Abort.when` → **inline** (creation)
- `Abort.run`, `Abort.runWith`, `Abort.recover`, `Abort.fold` → **not inline** (handling)

Similarly for data types like `Maybe`:
- `Maybe.map`, `Maybe.flatMap`, `Maybe.filter`, `Maybe.getOrElse` → **inline** (simple branches)
- `Maybe.get`, `Maybe.zip`, `Maybe.contains`, `Maybe.flatten` → **not inline** (complex logic)

**Inline to avoid function dispatch** — On the JVM, every lambda becomes a class at runtime via `LambdaMetaFactory`. When a method takes a function parameter and is `inline`, the compiler can inline the lambda body directly, eliminating the anonymous class allocation and virtual dispatch entirely. This is why methods like `Maybe.map`, `Var.use`, and `ArrowEffect.suspendWith` take `inline` function parameters. When inlining a function parameter causes the compiler to warn about unused anonymous classes, use `@nowarn` — the class replacement is intentional.

When in doubt, don't inline. The cost of unnecessary inlining (code bloat, slower compilation) is higher than the cost of a method call on a non-hot path.

### Scala Conventions

- [ ] **`discard(expr)`** to suppress unused value warnings — never `val _ = expr`:
  ```scala
  discard(self.lower.interrupt(error))     // correct
  val _ = self.lower.interrupt(error)      // wrong
  ```
- [ ] **`CanEqual` for all comparable types** — the codebase has strict equality enabled. All data types that need `==`/`!=` must have a `CanEqual` instance. Use `derives CanEqual` on case classes and enums. Skip types whose fields aren't meaningfully comparable (e.g., types containing function fields or `Schema` instances).
- [ ] **Explicit types only on public API surfaces.** Public methods must have explicit return types including the full effect type. Everywhere else — private methods, local vals, type parameters on method calls — prefer letting the compiler infer. This reduces clutter and also validates that inference works well for callers (if the compiler can't infer it internally, callers will struggle too):
  ```scala
  def offer(v: A)(using Frame): Boolean < (Sync & Abort[Closed])   // public — explicit
  private def helper(v: A) = ...                                    // private — inferred
  val result = someCall()                                           // val — inferred
  val chunk = Chunk.from(values)                                    // not Chunk.from[A](values)
  ```
- [ ] **No `protected`** — use `private[kyo]` or `private[kernel]` for internal visibility.
- [ ] **All public APIs in the `kyo` package** — no subpackages. Internal code uses `kyo.kernel`, `kyo.kernel.internal`, etc., but everything user-facing lives directly in `kyo`.
- [ ] **Avoid unsafe casts** — write type-safe code by default. `asInstanceOf` and `@unchecked` are acceptable only when strictly necessary inside opaque type boundaries or kernel internals. Never use them as convenience shortcuts. Never use `@uncheckedVariance`.
- [ ] **Imports**: specific over wildcard, internal wildcards OK (`import kyo.kernel.internal.*`), grouped by origin.
- [ ] **Keep `S` open**: use `A < (S & SomeEffect)` instead of `A < SomeEffect` to support effect composition if appropriate.
- [ ] **Prefer `call-by-name`** (`body: => A < S`) for deferred evaluation when lifting to `Sync`. This works because `Sync` is the final effect to be handled, allowing proper suspension of side effects. This does not apply to other effects.

### Types

When a Kyo primitive exists for a concept, use it instead of the stdlib equivalent.

**kyo-data** — foundational value types:

| Kyo primitive | Replaces | Notes |
|---|---|---|
| `Maybe[A]` | `Option[A]` | `Option` only as conversion input (e.g., `Abort.get(opt: Option[A])`). When stdlib methods return `Option` (e.g., `collectFirst`), convert to `Maybe` as soon as possible via `Maybe.fromOption` |
| `Result[E, A]` | `Either`, `Try` | Three-way: `Success`/`Failure`/`Panic` — never raw `Either` or `Try` in effect signatures |
| `Chunk[A]` | `Seq`, `List`, `Vector` | Use internally; accept generic collections in public APIs (see below) |
| `Duration` | `java.time.Duration`, `scala.concurrent.duration.Duration` | Opaque `Long`-based, zero-allocation |
| `Instant` | `java.time.Instant` | Kyo's own wrapper with consistent API |
| `Text` | `String` (for building) | Deferred concatenation — use when assembling strings incrementally |
| `Span[A]` | `IArray[A]`, `ArraySeq[A]` | Immutable array wrapper, avoids boxing, O(1) indexing |
| `Schedule` | Custom retry/timing logic | Composable scheduling policies |
| `TypeMap[A]` | Heterogeneous maps | Type-safe map keyed by type |

**kyo-prelude** — streaming and dependency types:

| Kyo primitive | Purpose | Notes |
|---|---|---|
| `Stream[V, S]` | Lazy effectful sequences | Chunked push/pull hybrid; prefer over manual `Emit` for sequences |
| `Pipe[A, B, S]` | Stream transformation | Composable `Stream[A] => Stream[B]` |
| `Sink[V, A, S]` | Stream consumption | Composable `Stream[V] => A` |
| `Layer[Out, S]` | Dependency injection | Composable; use `Layer.init` for compile-time wiring |

**kyo-core** — effects:

| Kyo primitive | Purpose | Notes |
|---|---|---|
| `Sync` | Effect suspension | Marks side-effecting code; use `Sync.defer { ... }` |
| `Async` | Concurrency | Main effect for concurrent programming; prefer over direct `Fiber` use |
| `Scope` | Resource management | `acquireRelease`, `ensure`; structured cleanup via `Scope.run` |
| `Clock` | Time operations | `now`, `sleep`, `deadline`; `withTimeControl` for testing |
| `Log` | Logging | `trace`, `debug`, `info`, `warn`, `error` with level control |
| `Console` | Console I/O | `readLine`, `print`, `printLine`, `printErr` |
| `Random` | Random generation | Seeded or context-bound; use for testability |
| `System` | Environment/properties | Type-safe access with custom `Parser`s |
| `Retry` | Retry with policy | Takes a `Schedule` from kyo-data |
| `Admission` | Load shedding | Reject work under pressure; pairs with `Rejected` error |

**kyo-core** — concurrency primitives:

| Kyo primitive | Purpose | Notes |
|---|---|---|
| `Fiber[A]` | Async computation handle | Low-level; prefer `Async` API in application code |
| `Channel[A]` | Async message passing | Bounded, backpressured; use over raw queues for async communication |
| `Queue[A]` | Concurrent collection | Synchronous ops, bounded/unbounded; use `Channel` when async backpressure is needed |
| `Hub[A]` | Broadcast messaging | Fan-out to multiple listeners; built on `Channel` + `Fiber` |
| `Signal[A]` | Reactive value | Current + change notification; use for observable state |
| `AtomicInt`, `AtomicLong`, `AtomicBoolean`, `AtomicRef[A]` | Atomic operations | Thread-safe single-value containers |
| `LongAdder` | High-contention counter | Use over `AtomicLong` for write-heavy workloads |
| `Meter` | Concurrency control | Semaphore, mutex, rate limiter — composable via `pipeline` |
| `Latch` | One-shot barrier | Use for coordination points |
| `Barrier` | Reusable barrier | Use for phased coordination |
| `Access` | Queue access pattern | `MPMC`, `MPSC`, `SPMC`, `SPSC` — more restrictive = faster |

**kyo-core** — error types:

| Kyo primitive | Purpose | Notes |
|---|---|---|
| `Closed` | Resource closed | Standard error for closeable resources; carries creation `Frame` |
| `Timeout` | Operation timed out | Used by `Async.timeout` and related APIs |
| `Interrupted` | Fiber interrupted | Used for cancellation; extends `Panic` semantics |
| `Rejected` | Admission rejected | Load shedding signal from `Admission` |

- [ ] **Generic collections** in public APIs, **`Chunk`** internally:
  ```scala
  def foreach[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](
      source: CC[A]
  )(f: Safepoint ?=> A => B < S)(using Frame, Safepoint): CC[B] < S =
      Kyo.foreach(Chunk.from(source))(f).map(source.iterableFactory.from(_))
  ```

---

## Method Signatures

### Parameter List Shape

Data parameters, then accumulator/config, then effect-producing function, then context:

```scala
def foldLeft[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](
    source: CC[A]          // data
)(
    acc: B                 // accumulator
)(
    f: Safepoint ?=> (B, A) => B < S  // effect-producing function
)(using Frame, Safepoint): B < S      // context
```

Effect-producing function parameters use `Safepoint ?=>` prefix to allow framework injection for loop control.

### `(using Frame)` as Type Parameter Separator

Scala 3 doesn't support `def foo[A][B, C]`. The workaround is to use `(using Frame)` between type parameter clauses. The first clause holds the type parameter the user specifies explicitly; the second holds types inferred from value arguments:

```scala
//                      user specifies E     inferred from args
//                      vvvvvvvvvvvvvvvv     vvvvvvvvvvvvvvvvvvvv
inline def get[E](using inline frame: Frame)[A](either: Either[E, A]): A < Abort[E]

inline def runWith[E](using Frame)[A, S, ER, B, S2](
    v: => A < (Abort[E | ER] & S)
)(continue: Result[E, A] => B < S2)(...): B < (S & reduce.SReduced & S2)

def apply[E: ConcreteTag](using Frame)[A, S](
    v: => A < (Abort[E] & S)
): A < (Async & Abort[E] & S)
```

Pattern: `[UserSpecified](using Frame)[InferredFromArgs]`

### `using` Clause Ordering

Inline methods — `Tag` before `Frame`:
```scala
inline def get[V](using inline tag: Tag[Var[V]], inline frame: Frame): V < Var[V]
```

Non-inline methods — `Frame` before type-level evidence:
```scala
def run[E](...)(using frame: Frame, ct: ConcreteTag[E], reduce: Reducible[Abort[ER]]): ...
```

`AllowUnsafe` always last:
```scala
def init(parallelism: Int)(using frame: Frame, allow: AllowUnsafe): ...
```

### Frame and Tag

- **`Frame`** on every method that suspends or handles effects. Never on pure data accessors (`capacity`, `size`). Always `inline` on inline methods for zero-cost source location capture.
- **`Tag`** when runtime effect dispatch is needed — parametric effects like `Var[V]`, `Emit[V]`, `Env[R]` require tags because the handler must identify which effect to match at runtime.

### Overload Organization

Simple variants delegate to the canonical implementation — never duplicate logic:

```scala
// Canonical — does the actual work
private[kyo] inline def runWith[V, A, S, B, S2](state: V)(v: A < (Var[V] & S))(
    inline f: (V, A) => B < S2
): B < (S & S2) = ArrowEffect.handleLoop(...)

// Variants — project the result differently
def run[V, A, S](state: V)(v: A < (Var[V] & S)): A < S =
    runWith(state)(v)((_, result) => result)

def runTuple[V, A, S](state: V)(v: A < (Var[V] & S)): (V, A) < S =
    runWith(state)(v)((state, result) => (state, result))
```

For overloads by arity: variadic delegates to `Seq`, `Seq` delegates to `Seq`+config:
```scala
def race(first, rest*) = race(first +: rest)
def gather(iterable) = gather(iterable.size)(iterable)
```

Ordered by increasing arity/complexity within each group.

---

## Documentation

### Type-Level Scaladoc Checklist

Every public type needs a scaladoc (8-35 lines) that covers:

- [ ] **Opening sentence** — what the type *is*, brief and definitional
- [ ] **Conceptual "why"** — 1-3 paragraphs explaining the mental model and design rationale
- [ ] **Feature/capability bullets** — key operations and behaviors
- [ ] **Gotcha callouts** — `WARNING:`, `IMPORTANT:`, or `Note:` for surprising behavior
- [ ] **`@tparam`** tags for all type parameters
- [ ] **`@see`** references — 3-6 links per type, grouped by topic (creation, handling, related types)
- [ ] **No code examples** unless demonstrating composition patterns or system property syntax (rare)

WARNING/IMPORTANT/Note decision:
- **`WARNING:`** — risk of data loss, memory exhaustion, or incorrect behavior if misused
- **`IMPORTANT:`** — subtle semantic distinction that affects correctness
- **`Note:`** — behavioral clarification or platform difference

### Method-Level Scaladoc Checklist

- [ ] Brief description of what it does (1-3 lines)
- [ ] `@param` / `@return` only when the name and type don't tell the full story
- [ ] Skip entirely for trivially obvious methods (`capacity: Int`, `size: Int`)

### Inline Comments

Methods typically have short comments to aid understanding when they're more complex. Comments appear in these situations:

1. **Method-level clarity** — a brief comment on what a non-trivial method does, especially when the name alone isn't enough
2. **Phase markers in multi-step methods** — brief labels marking logical sections within a method body (`// extract path params`, `// combine inputs`, `// map errors`). Help readers scan without reading every line. One line per phase, not a paragraph. Comments must add understanding — don't restate what the function name already says (`// process completed transfers` before `processCompletedTransfers()` is noise).
3. **Navigational comments in large methods (30+ lines)** — longer methods benefit from slightly more comments even when individual lines are self-evident, because readers lose context over many lines. These act as signposts helping someone skim the method's structure without reading every line.
4. **Race conditions / concurrency hazards** — explain the interleaving
5. **Bit-packing / encoding schemes** — diagram the layout
6. **Known limitations / TODOs** — describe what's missing and why
7. **Non-obvious algorithmic choices** — explain *why*, not *what*

Quality bar: every comment should pass the test "would removing this make the code harder to understand?" If the answer is no, delete it.

---

## File Organization

A source file should read like a guided tour of the type. A contributor opening it for the first time learns — in order — what the type is, how to create it, how to use it, and only then how it works internally. Scaladocs set the context, method ordering tells the story, and section separators mark the chapters.

### File Template

```scala
package kyo                           // or kyo.kernel, kyo.kernel.internal

import kyo.specific.imports           // specific, minimal
import scala.annotation.tailrec       // stdlib next

/** Type-level scaladoc.
  *
  * Conceptual explanation.
  *
  * @tparam A description
  * @see [[kyo.Related]]
  */
sealed trait MyEffect[A] extends ArrowEffect[...] // or opaque type, final class

object MyEffect:

    // givens, type aliases, private helpers

    // --- Public API (frequency-of-use order) ---

    // Suspend/create methods
    inline def create[A](...)(using ...): A < MyEffect[A] = ...

    // Query/access methods
    inline def get[A](...)(using ...): A < MyEffect[A] = ...
    inline def use[A](...)(using ...): B < (MyEffect[A] & S) = ...

    // Handler methods
    def run[A](...)(using ...): Result < S = runWith(...)(identity)
    private[kyo] def runWith[A](...)(f: ...)(using ...): B < S = ...

    // Factory methods (for resource types)
    def init[A](...)(using Frame): MyType[A] < (Sync & Scope) = ...
    def initWith[A](...)(f: ...)(using Frame): B < (Sync & Scope & S) = ...
    def initUnscoped[A](...)(using Frame): MyType[A] < Sync = ...

    // --- Internal ---

    private[kyo] def internal(...) = ...

    // --- Nested Types ---

    object Unsafe:
        ...

end MyEffect
```

### Readability Ordering

The file template above reflects a deliberate top-to-bottom reading order:

1. **Type definition + scaladoc** — the reader learns what the type is and why it exists
2. **Givens and type aliases** — supporting infrastructure needed to understand the API
3. **Public API** — grouped by usage pattern (create → query → handle → factories), most-used first within each group
4. **Internal methods** — implementation details the reader doesn't need unless contributing
5. **Nested types** — `Unsafe`, `Config`, auxiliary case classes

Within each group, simple overloads come before complex ones. The canonical implementation sits next to its variants so the reader sees the delegation at a glance. Scaladocs on each method flow naturally from one to the next — each building on context established by the previous.

This ordering matters because it determines how quickly a contributor can orient themselves. A well-ordered file answers "what does this do?" and "how do I use it?" without scrolling.

### Visibility Tiers

| Modifier | Scope | Use for |
|---|---|---|
| *(none)* | Public | User-facing API |
| `private[kyo]` | Cross-package | Internal utilities used across modules |
| `private[kernel]` | Kernel only | Runtime internals (Kyo, KyoSuspend, Safepoint) |
| `private` | Class-local | Mutable state, helpers (rare) |

### Large Files

Use `// ---...` separators with section names:

```scala
// -------------------------------------------------
// Generic
// -------------------------------------------------

def foreach[...] = ...
def filter[...] = ...

// -------------------------------------------------
// List
// -------------------------------------------------

def foreach[A, B, S](source: List[A])(...) = ...
```

Group by semantic category (reads → writes → updates → handlers), then by arity within each group.

### `export` for Nested Type Promotion

When a nested type is heavily used, promote it to package level with `export`:

```scala
// In Fiber.scala — after the companion object
export Fiber.Promise   // makes kyo.Promise available without Fiber. prefix
```

Use sparingly — only for types that users reference frequently enough that qualification would be noisy.

### `@deprecated` Aliases for Renames

When renaming a type, keep the old name as a deprecated alias (both `type` and `val`) so downstream code gets a migration path:

```scala
@deprecated("Will be removed in 1.0. Use `Scope` instead.", "1.0-RC")
type Resource = Scope

@deprecated("Will be removed in 1.0. Use `Scope` instead.", "1.0-RC")
val Resource = Scope
```

---

## Unsafe Boundary

### The Two-Tier API Pattern

Every concurrent type (`Channel`, `Queue`, `Hub`, `Fiber`, `Meter`, `Latch`, `Signal`, etc.) exposes two parallel APIs: a **safe** tier that tracks effects in the type system, and an **Unsafe** tier for integrations, libraries, and performance-sensitive code that bypasses effect tracking.

Users can always navigate between the two tiers:
- **`.unsafe`** on any safe instance returns the `Unsafe` counterpart
- **`.safe`** on any `Unsafe` instance returns the safe counterpart

The two tiers mirror each other — every operation available on the safe API has an `Unsafe` equivalent, and vice versa. The safe tier wraps operations in effects (`Sync`, `Abort[Closed]`, `Async`), while the `Unsafe` tier returns raw values and `Result`s, guarded by `(using AllowUnsafe)`.

**Structure**: For a type `T`, expect to find:
- `T` — the safe type with effectful methods (takes `using Frame`)
- `T.Unsafe` — `sealed abstract class` with the same operations but taking `(using AllowUnsafe)` instead
- `T.Unsafe.init(...)` — factory in the `Unsafe` companion (takes `using AllowUnsafe`; add `Frame` only if the factory uses it, e.g., to capture creation context for `Closed`)
- `T.init(...)` / `T.use(...)` / `T.initUnscoped(...)` — safe factories that delegate to `Unsafe.init` with lifecycle management (see "Factory methods" in Naming above)

**Safe→Unsafe bridge**: Safe methods enter the unsafe tier via `Sync.Unsafe { ... }`, which provides `AllowUnsafe` implicitly. Inside, they call the `Unsafe` method and wrap `Result[Closed, A]` in `Abort.get` to convert to `Abort[Closed]` effect.

**Subtypes preserve the pattern**: `Queue.Unbounded` has `Queue.Unbounded.Unsafe` that extends `Queue.Unsafe`. The subtype relationship holds on both tiers.

### Unsafe API Conventions

- **WARNING scaladoc** on every `Unsafe` class and `object Unsafe` — always the same text: "Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details."
- **`(using AllowUnsafe)`** on every method that performs side effects. Pure accessors like `capacity` don't need it.
- **`.safe` / `.unsafe` parity** — the `Unsafe` class has `def safe: T = this` and the safe type has `def unsafe: T.Unsafe = self`. Users can freely convert between tiers.
- **`extends Serializable`** on `sealed abstract class Unsafe` for closeable types
- **Closeable operations return `Result[Closed, A]`** — never throw on closed state
- **Factory methods** in `object Unsafe` take `(using AllowUnsafe)`. Add `Frame` only when the factory actually uses it (e.g., to capture the creation context for `Closed` errors)

### AllowUnsafe Tiers

**All side effects must be suspended.** No side-effecting code should execute outside of Kyo's effect system without either an `AllowUnsafe` proof or a suspension boundary like `Sync.Unsafe`. This is a hard rule — unsuspended side effects break referential transparency.

In order of preference:

1. **Propagate the proof** — caller explicitly opts in (preferred for performance — no suspension overhead):
   ```scala
   def init[A](capacity: Int)(using AllowUnsafe): Queue.Unsafe[A]
   ```

2. **Suspend in Sync** — wraps the unsafe operation in an effect:
   ```scala
   def offer(v: A)(using Frame): Boolean < (Sync & Abort[Closed]) =
       Sync.Unsafe(Abort.get(self.offer(v)))
   ```

3. **Import danger** — external runtime callbacks (Netty listeners, platform interop) and application boundaries (KyoApp, tests) where code is already outside Kyo's effect system:
   ```scala
   import AllowUnsafe.embrace.danger
   ```

Library and effect code must NEVER import `embrace.danger`.

**Scope `AllowUnsafe` as narrowly as possible.** Never place `(using AllowUnsafe)` on a constructor or class-level import where it leaks to all methods — this masks accidental unsafe operations that the compiler would otherwise catch. Instead, take it only on the specific methods that need it, or scope the import to the smallest block possible:
```scala
// Wrong — leaks AllowUnsafe to every method in the class
class MyConnection(...)(using AllowUnsafe):
    private val flag = AtomicBoolean.Unsafe.init()
    def send(...) = ... // compiler won't catch unsafe ops here

// Right — scoped to the specific initialization
class MyConnection(..., closed: AtomicBoolean):
    def isAlive(using AllowUnsafe): Boolean = !closed.unsafe.get()
    def send(...) = ... // compiler catches accidental unsafe ops
```

**Prefer the safe type, access `.unsafe` when needed.** When a type has safe/unsafe tiers, hold the safe version and use `.unsafe` only in methods that already have `AllowUnsafe` in scope. This ensures the compiler enforces safety by default:
```scala
// Preferred — hold safe type, access .unsafe in AllowUnsafe methods
class MyConnection(closed: AtomicBoolean):
    def isAlive(using AllowUnsafe): Boolean = !closed.unsafe.get()
    def close(using Frame): Unit < Async = closed.set(true).andThen(...)

// Avoid — holding Unsafe type bypasses safety checks everywhere
class MyConnection(closed: AtomicBoolean.Unsafe):
    def isAlive(using AllowUnsafe): Boolean = !closed.get()
```

### AllowUnsafe for Zero-Allocation Side Effects

The `AllowUnsafe` implicit serves a specific purpose: it is a compiler-enforced proof that the side effect has already been suspended at an outer scope. This enables methods to perform side effects directly — without allocating a `Sync.Unsafe` suspension — while still guaranteeing that the call chain is rooted in a properly suspended context.

This is the mechanism behind the safe→unsafe bridge. The safe tier suspends via `Sync.Unsafe`, which provides `AllowUnsafe` implicitly, then calls the `Unsafe` method that performs the side effect directly:

```scala
// Safe tier — suspends once, then delegates
def set(v: Boolean)(using Frame): Unit < Sync = Sync.Unsafe(unsafe.set(v))

// Unsafe tier — performs the side effect directly, no allocation
// The AllowUnsafe proof guarantees an outer scope has already suspended
extension (self: Unsafe)
    inline def set(v: Boolean)(using AllowUnsafe): Unit = self.set(v)
```

The same pattern applies to internal APIs. When a method takes `(using AllowUnsafe)`, it's declaring: "I perform side effects, but I trust my caller to have suspended." This allows multiple unsafe operations to compose without each one wrapping in its own `Sync.Unsafe`:

```scala
// One suspension covers multiple unsafe operations — no per-operation allocation
def release(conn: Connection)(using AllowUnsafe, Frame): Unit =
    if conn.isAlive then          // unsafe: reads atomic flag
        idleChannels.offer(conn)  // unsafe: mutates concurrent queue
    else
        conn.closeAbruptly()      // unsafe: closes connection
```

Without `AllowUnsafe`, each of these would need its own `Sync.Unsafe` wrapper, allocating a closure each time. The `AllowUnsafe` proof eliminates this overhead by hoisting the suspension to the outermost boundary.

### Closeable Resource Pattern

All closeable resources follow:

```scala
// Closed carries creation context for diagnostics
class Closed(resource: String, createdAt: Frame, details: String = "")(using Frame)

// Unsafe level returns Result[Closed, A]
def offer(v: A)(using AllowUnsafe): Result[Closed, Boolean]

// Safe level converts to Abort[Closed]
def offer(v: A)(using Frame): Boolean < (Sync & Abort[Closed]) =
    Sync.Unsafe(Abort.get(self.offer(v)))
```

Resource factory convention — all variants delegate down to `Unsafe.init`:

| Method | Lifecycle | Effect Set | Delegates to |
|---|---|---|---|
| `init` | `Scope`-managed cleanup | `Sync & Scope` | `initWith(identity)` |
| `initWith(f)` | `Scope`-managed + callback | `Sync & Scope & S` | `Unsafe.init` + `Scope.ensure(close)` |
| `use(f)` | Bracket (no `Scope` needed) | `Sync & S` | `Unsafe.init` + `Sync.ensure(close)` |
| `initUnscoped` | No cleanup guarantees | `Sync` | `initUnscopedWith(identity)` |
| `initUnscopedWith(f)` | No cleanup + callback | `Sync & S` | Bare `Unsafe.init` |

The delegation chain (using `Channel` as canonical example):
```scala
// init delegates to initWith with identity — Scope-managed lifecycle
def init[A](capacity: Int, access: Access)(using Frame): Channel[A] < (Sync & Scope) =
    initWith[A](capacity, access)(identity)

// initWith: create resource, register Scope cleanup, apply callback
inline def initWith[A](capacity: Int, access: Access)[B, S](
    inline f: Channel[A] => B < S
)(using inline frame: Frame): B < (S & Sync & Scope) =
    Sync.Unsafe:
        val channel = Unsafe.init[A](capacity, access)
        Scope.ensure(Channel.close(channel)).andThen:
            f(channel)

// use: bracket semantics via Sync.ensure — no Scope in the effect set
inline def use[A](capacity: Int, access: Access)[B, S](
    inline f: Channel[A] => B < S
)(using inline frame: Frame): B < (S & Sync) =
    Sync.Unsafe:
        val channel = Unsafe.init[A](capacity, access)
        Sync.ensure(Channel.close(channel)):
            f(channel)

// initUnscoped delegates to initUnscopedWith with identity — no cleanup
def initUnscoped[A](capacity: Int, access: Access)(using Frame): Channel[A] < Sync =
    initUnscopedWith[A](capacity, access)(identity)

// initUnscopedWith: bare Unsafe.init, no cleanup registered
inline def initUnscopedWith[A](capacity: Int, access: Access)[B, S](
    inline f: Channel[A] => B < S
)(using inline frame: Frame): B < (S & Sync) =
    Sync.Unsafe(f(Unsafe.init[A](capacity, access)))
```

Choose `init` (Scope-managed) by default. Use `use` when you want bracket semantics without `Scope` in the effect set. Use `initUnscoped` only when the caller manages lifecycle manually.

### Close Method Convention

Types that offer a `close` method must provide three variants. The parameterized version is the canonical implementation; the others delegate to it:

```scala
// Canonical — takes an explicit grace period
def close(gracePeriod: Duration)(using Frame): Unit < Async

// Default — delegates with a sensible default (typically 30 seconds)
def close(using Frame): Unit < Async = close(30.seconds)

// Immediate — delegates with zero grace period
def closeNow(using Frame): Unit < Async = close(Duration.Zero)
```

This gives callers control without forcing them to pick a timeout for the common case. `closeNow` makes the intent explicit — no ambiguity about whether a bare `close` waits or not.

For producer/consumer resources (Channel, Queue), also provide `closeAwaitEmpty` — closes the resource but waits until remaining elements are consumed before completing.

### Local-Backed Service Pattern

Types like `Clock`, `Log`, `Random`, `Console`, `System`, and `HttpClient` follow a common pattern: a `Local` holds a default instance, and the companion exposes `get`/`use`/`let` to interact with it. Convenience methods on the companion delegate through the local so callers never need the instance directly.

```scala
final case class Clock(unsafe: Clock.Unsafe):
    // Instance methods operate on `this`
    def now(using Frame): Instant < Sync = ...

object Clock:
    private val local = Local.init(live)

    // Access the local instance
    def get(using Frame): Clock < Any = local.get
    def use[A, S](f: Clock => A < S)(using Frame): A < S = local.use(f)

    // Swap the instance for a scope
    def let[A, S](c: Clock)(f: => A < S)(using Frame): A < S = local.let(c)(f)

    // Convenience — delegates through the local
    def now(using Frame): Instant < Sync = ...
    def sleep(duration: Duration)(using Frame): Unit < Async = ...
```

Key points:
- The `Local` is private; external code uses only `get`/`use`/`let`
- Companion convenience methods delegate to the local instance so most callers never call `get` or `use` explicitly
- `let` enables testing by substituting a mock/controlled instance
- The live default means the service works out of the box with no setup

### KyoException Convention

All custom exceptions extend `KyoException`, which provides:
- `NoStackTrace` for performance (stack traces are expensive and rarely useful for expected errors)
- `Frame`-based context that captures the creation site
- Environment-aware formatting (rich ANSI in dev, minimal in prod)

```scala
class Closed(resource: String, createdAt: Frame, details: String = "")(using Frame)
    extends KyoException(s"$resource created at ${createdAt.position.show} is closed.", details)
```

Follow this pattern for all new exception types:
- Extend `KyoException`, not `Exception` or `RuntimeException` — `KyoException` already includes `NoStackTrace`, so you never need to add it yourself
- Take `(using Frame)` to capture context
- Use `String` for messages
- Keep the message concise — the `Frame` provides the location context

---

## Effect Implementation Reference

NOTE: This section is a reference for the uncommon task of implementing a new effect. For everyday coding, the sections above are sufficient.

### Anatomy of an Effect

1. **Type definition** — sealed trait extending one of two base classes:
   - **`ArrowEffect[I, O]`** — for function-like effects that transform inputs to outputs. Operations are encoded as an ADT and dispatched in a handler loop. Used by `Abort`, `Var`, `Emit`, `Choice`, `Poll`.
   - **`ContextEffect[A]`** — for value-providing effects (dependency injection). No ADT or handler loop needed — just `ContextEffect.handle(tag, value)(computation)`. Values are inherited across async boundaries by default; mark with `ContextEffect.Noninheritable` to prevent this. Used by `Env`, `Local`.

   ```scala
   sealed trait Var[V] extends ArrowEffect[Const[Op[V]], Const[V]]   // ArrowEffect
   sealed trait Env[+R] extends ContextEffect[R]                     // ContextEffect
   ```

2. **Operations as data** — encode operations as an ADT, not as methods:
   ```scala
   private type Op[V] = Get.type | V | Update[V]
   private object Get
   private type Update[V] = V => V
   ```

3. **Suspend** — translate domain operations into kernel inputs:
   ```scala
   inline def get[V](...): V < Var[V] = use[V](identity)
   inline def use[V](...)(inline f: V => A < S)(...) =
       ArrowEffect.suspendWith[V](tag, Get: Op[V])(f)
   inline def set[V](inline value: V)(...) =
       ArrowEffect.suspend[Unit](tag, value: Op[V])
   ```

4. **Handle** — choose the right kernel handler for the effect:

   | Handler | When to use |
   |---|---|
   | `ArrowEffect.handle` | Simple one-shot handling, no looping or state |
   | `ArrowEffect.handleFirst` | Handle only the first occurrence, get a continuation for the rest |
   | `ArrowEffect.handleLoop` (no state) | Loop through all occurrences without accumulating state |
   | `ArrowEffect.handleLoop` (with state) | Loop with state threaded through each occurrence |
   | `ArrowEffect.handleLoop` (state + done) | Same, plus a final transformation when computation completes |

   Example with stateful `handleLoop`:
   ```scala
   ArrowEffect.handleLoop(tag, initialState, computation)(
       [C] => (input, state, cont) =>
           input match
               case _: Get.type  => Loop.continue(state, cont(state))
               case input: Update[V] =>
                   val nst = input(state)
                   Loop.continue(nst, cont(nst))
               case input: V     => Loop.continue(input, cont(state)),
       done = (state, result) => ...
   )
   ```

   Use `Effect.catching` to intercept exceptions inside effect implementations and convert them to effect errors.

5. **`Reducible.Eliminable` given** if the effect can be fully eliminated:
   ```scala
   given eliminateAbort: Reducible.Eliminable[Abort[Nothing]] with {}
   ```

6. **`Reducible` in handler signatures** — when an effect has union types (e.g., `Abort[E | ER]`), handlers use `Reducible` to allow partial handling. The handler eliminates `E` and reduces the remaining `ER` via `reduce.SReduced`:
   ```scala
   def run[E](using Frame)[A, S, ER](
       v: => A < (Abort[E | ER] & S)
   )(using
       ct: ConcreteTag[E],
       reduce: Reducible[Abort[ER]]
   ): Result[E, A] < (S & reduce.SReduced)
   ```
   This lets callers handle one layer of a union error type while preserving the rest in the effect stack.

7. **Variants delegate to canonical** — never duplicate handler logic:
   ```scala
   def run[V, A, S](state: V)(v: A < (Var[V] & S)): A < S =
       runWith(state)(v)((_, result) => result)
   def runTuple[V, A, S](state: V)(v: A < (Var[V] & S)): (V, A) < S =
       runWith(state)(v)((state, result) => (state, result))
   ```

### Delegation Pattern for Higher-Level Types

Higher-level types delegate to lower-level ones — they don't reimplement:

| Higher | Delegates to | Adds |
|---|---|---|
| `Channel` | `Queue` | Fiber-aware put/take with suspension |
| `Hub` | `Channel` + `Fiber` | Fan-out distribution fiber |
| `Async` | `Fiber` | Structured concurrency, isolation |
| `Stream` | `Emit[Chunk[V]]` | Chunked processing, transformations |
| `Meter.pipeline` | `Seq[Meter]` | Composed admission control |

Build new types by composing existing ones. Create anonymous instances when composition logic varies:
```scala
def pipeline[S](meters: Seq[Meter < (Sync & S)]): Meter < (Sync & S) =
    Kyo.collectAll(meters).map { seq =>
        new Meter:
            def run[A, S](v: => A < S)(using Frame) = ...
    }
```

### Isolate Protocol for Fiber-Crossing Operations

Any method that forks work into a new fiber must use the three-step `Isolate` protocol to safely move effectful state across fiber boundaries:

```scala
def race[E, A, S](
    using isolate: Isolate[S, Abort[E] & Async, S]   // Isolate comes first in using clause
)(iterable: Seq[A < (Abort[E] & Async & S)])(using Frame): A < (Abort[E] & Async & S) =
    isolate.capture { state =>                         // 1. capture current state
        Fiber.internal.race(
            iterable.map(isolate.isolate(state, _))    // 2. attach state to each forked computation
        ).map(fiber => isolate.restore(fiber.get))     // 3. restore state from the winner
    }
```

Note the `using` parameter ordering: `Isolate` precedes `Frame` because it participates in type inference.

Effects that carry state across fibers provide standard isolate strategies in an `object isolate` namespace in their companion:
- **`update`** — the forked fiber gets the current state; on completion, the outer state is replaced with the forked fiber's final state
- **`merge(f)`** — combines the outer and inner states using a merge function `f`
- **`discard`** — the forked fiber gets the current state but its final state is thrown away

Example: `Var.isolate.update`, `Var.isolate.merge((a, b) => a + b)`, `Var.isolate.discard`.

---

## PR Checklists

### Any Change

- [ ] Public methods ordered by frequency of use, not alphabetically
- [ ] No category theory naming
- [ ] `.map` instead of `.flatMap` on pending types
- [ ] `discard(expr)` for unused values, not `val _ =`
- [ ] `final` on concrete classes; `final` on methods in abstract classes/traits when not intended for override
- [ ] No `protected` — use `private[kyo]` or `private[kernel]`
- [ ] Explicit return types on public methods (including effect type); omit on private/local
- [ ] No unnecessary comments on self-evident code
- [ ] `WARNING:`/`IMPORTANT:`/`Note:` on surprising behavior
- [ ] Performance: no unnecessary allocations, suspensions, or boxing

### New Public Method

All items from "Any Change" plus:

- [ ] `Frame` in `using` clause (if it suspends or handles effects)
- [ ] `Tag` in `using` clause (if runtime effect dispatch needed)
- [ ] `using` clause ordering correct (Tag→Frame for inline; Frame→evidence for non-inline)
- [ ] `(using Frame)` separator if multiple type parameter clauses needed
- [ ] Parameter lists: data → function → context
- [ ] Effect-producing function parameters have `Safepoint ?=>` prefix
- [ ] Overloads delegate to canonical implementation
- [ ] `fooDiscard` variant if the method returns a value callers often ignore
- [ ] Pure variant provided if the function parameter doesn't need suspension
- [ ] Scaladoc with description, `@param`/`@return` where non-obvious

### New Type

All items from "Any Change" plus:

- [ ] Type-level scaladoc: opening sentence, "why" paragraph, features, gotchas, `@tparam`, `@see`
- [ ] `final` class, `sealed` trait, or `opaque` type (never open class); `final` on methods not intended for override
- [ ] Companion object follows file template ordering
- [ ] Kyo primitives over stdlib equivalents (see Types table above)

### New Concurrent Type

All items from "New Type" plus:

- [ ] Opaque type over `.Unsafe` implementation
- [ ] Extension methods for safe API
- [ ] `init` / `initWith` / `initUnscoped` factory variants as appropriate
- [ ] `Closed` error pattern with creation `Frame`
- [ ] Lock-free where possible (CAS + `@tailrec`)
- [ ] Comments on race conditions and state encoding
- [ ] `AllowUnsafe` properly threaded (never imported in library code)
- [ ] Close method convention if the type supports closing: `close(gracePeriod)` / `close` (default 30s) / `closeNow`; `closeAwaitEmpty` for producer/consumer types
- [ ] `noop`/`Noop` singleton for degenerate cases (e.g., zero-count latch, no-op meter)
- [ ] Sync/async method pairs if applicable: `offer`/`poll` (immediate) vs `put`/`take` (suspending)
- [ ] Isolate protocol (`capture`/`isolate`/`restore`) for any method that forks into new fibers
- [ ] Local-backed service pattern if the type is a swappable service: private `Local`, `get`/`use`/`let` in companion
- [ ] Custom exceptions extend `KyoException` with `(using Frame)` and `String` messages

### New Effect

All items from "New Type" plus:

- [ ] Sealed trait extending `ArrowEffect` or `ContextEffect`
- [ ] Operations encoded as ADT
- [ ] Handler using `handleLoop` (stateful) or `handle` (stateless)
- [ ] `Reducible.Eliminable` given if fully eliminable
- [ ] Handler variants delegate to canonical `runWith`
- [ ] `get`/`use` pair for access (where applicable)
- [ ] `run` as primary handler name
