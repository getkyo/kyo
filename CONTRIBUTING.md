# How to Make Contributions

Thank you for considering contributing to this project! We welcome all contributions, whether it's bug reports, feature requests, documentation improvements, or code contributions.

## Table of Contents

- [Contributing](#contributing)
  - [Getting Started](#getting-started)
  - [Configuring Java Options](#configuring-java-options)
  - [How to Build Locally](#how-to-build-locally)
  - [Adding a New API](#adding-a-new-api)
  - [LLM Use Guide](#llm-use-guide)
- [Core Principles](#core-principles)
- [API Design](#api-design)
  - [Naming](#naming)
  - [Types](#types)
  - [Method Signatures](#method-signatures)
- [Code Conventions](#code-conventions)
  - [Pending Type (A < S)](#pending-type-a--s)
  - [Scala Conventions](#scala-conventions)
  - [Documentation](#documentation)
  - [File Organization](#file-organization)
- [Optimization](#optimization)
  - [Performance](#performance)
  - [Zero-Cost Type Design](#zero-cost-type-design)
  - [Inline Guidelines](#inline-guidelines)
- [Testing](#testing)
  - [Framework](#framework)
  - [Base Trait Hierarchy](#base-trait-hierarchy)
  - [Test Patterns by Level](#test-patterns-by-level)
  - [Platform-Conditional Tests](#platform-conditional-tests)
  - [Compile-Time Tests](#compile-time-tests)
  - [Concurrent Test Helpers](#concurrent-test-helpers)
- [Unsafe Boundary](#unsafe-boundary)
  - [The Two-Tier API Pattern](#the-two-tier-api-pattern)
  - [Unsafe API Conventions](#unsafe-api-conventions)
  - [AllowUnsafe Tiers](#allowunsafe-tiers)
  - [AllowUnsafe for Zero-Allocation Side Effects](#allowunsafe-for-zero-allocation-side-effects)
  - [Closeable Resource Pattern](#closeable-resource-pattern)
  - [Close Method Convention](#close-method-convention)
  - [Local-Backed Service Pattern](#local-backed-service-pattern)
  - [KyoException Convention](#kyoexception-convention)
- [Effect Implementation Reference](#effect-implementation-reference)
  - [Anatomy of an Effect](#anatomy-of-an-effect)
  - [Delegation Pattern for Higher-Level Types](#delegation-pattern-for-higher-level-types)
  - [Isolate Protocol for Fiber-Crossing Operations](#isolate-protocol-for-fiber-crossing-operations)

## Contributing

### Getting Started

#### Prerequisites

Before you begin, make sure you have the following installed:

- **Java 21 (or later)**
- **Scala**
- **Node**
- **sbt** (Scala Build Tool)
- **Git**

#### Setting Up Your Environment

1. **Fork the Repository**
   - Navigate to the **kyo** repository and click the **Fork** button.

2. **Clone Your Fork**
   ```sh
   git clone https://github.com/your-username/kyo.git
   cd your-repo
   ```

3. **Set Up Upstream Remote**
   ```sh
   git remote add upstream https://github.com/getkyo/kyo.git
   ```

### Configuring Java Options

Java options (`JAVA_OPTS` and `JVM_OPTS`) define how much memory and resources the JVM should use when running the project. Setting these options correctly ensures stable performance and prevents out-of-memory errors.

To configure Java options, run the following commands in your terminal:
```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
```

#### Explanation of Parameters:

- `-Xms2G`: Sets the initial heap size to 3GB.
- `-Xmx3G`: Sets the maximum heap size to 4GB.
- `-Xss10M`: Sets the stack size to 10MB.
- `-XX:MaxMetaspaceSize=512M`: Limits the maximum metaspace size to 512MB.
- `-XX:ReservedCodeCacheSize=128M`: Reserves 128MB for compiled code caching.
- `-Dfile.encoding=UTF-8`: Ensures file encoding is set to UTF-8.

#### Adjusting These Values

If you experience memory issues or your system has more resources, you can increase these values. For example, if you have 16GB RAM, you might set:
```sh
export JAVA_OPTS="-Xms4G -Xmx8G -Xss10M -XX:MaxMetaspaceSize=1G -XX:ReservedCodeCacheSize=256M -Dfile.encoding=UTF-8"
export JVM_OPTS="-Xms4G -Xmx8G -Xss10M -XX:MaxMetaspaceSize=1G -XX:ReservedCodeCacheSize=256M -Dfile.encoding=UTF-8"
```

You can also add these lines to your `.bashrc` or `.zshrc` file for persistent settings.

### How to Build Locally

Run the following commands to build and test the project locally:
```sh
sbt '+kyoJVM/test' # Runs JVM tests
sbt '+kyoJS/test'  # Runs JS tests
sbt '+kyoNative/Test/compile' # Compiles Native code
```

Check formatting before submitting:
```sh
sbt "scalafmtCheckAll"
```

### Adding a New API

If you want to contribute a new method or type, feel free to:

- Open an issue
- Discuss on Discord: [https://discord.gg/afch62vKaW](https://discord.gg/afch62vKaW)
- Share design examples:
    - Use cases
    - Equivalent in `ZIO` or `Cats Effect`
    - Other motivating patterns

#### Where to Add Your API

| Subproject        | Use For                                                   |
| ----------------- | --------------------------------------------------------- |
| `kyo-data`        | Data structures (`Chunk`, `Maybe`, `Result`, etc.)        |
| `kyo-prelude`     | Effect types without `Sync` (`Abort`, `Env`, `Var`, etc.) |
| `kyo-core`        | Methods requiring `Sync`                                  |
| `kyo-combinators` | Extensions or composition helpers                         |

Add corresponding tests in the same subproject.

**Example:**\
A new `Stream.fromSomething` method:

- If it uses `Sync`: place it in `kyo-core/shared/src/main/scala/kyo/StreamCoreExtensions.scala`
- If it doesn't: place it in `kyo-prelude/shared/src/main/scala/kyo/Stream.scala`

### LLM Use Guide
We encourage contributors to leverage Large Language Models (LLMs) responsibly:
- Do **not** submit low-effort, AI-generated code without review.
- If you use AI assistance, ensure that the submission is well-tested and meets our standards.
- Automated PRs without human oversight may be closed.

---

## Core Principles

These are the axioms. Everything else in this guide derives from them.

1. **Source files are documentation.** Every `.scala` file is meant to be read top-to-bottom. Method ordering, scaladocs, section separators, and the flow from public API down to internals all serve readability. A contributor opening a file for the first time should understand the type's purpose and usage patterns just by reading the file — before touching any external docs. Treat the ordering and structure of a file as carefully as you treat the implementation.

2. **Most-used first.** Within each section, prioritize what users reach for most. Factory methods before configuration, `run` before `runWith`, simple overloads before complex ones. Discoverability beats alphabetical order.

3. **Action verbs, not theory.** `foreach` not `traverse`. Accessible naming lowers the barrier to entry and keeps the API approachable for developers who don't have category theory backgrounds.

4. **Performance is a first-class feature.** Avoid allocations, avoid unnecessary suspensions, use `inline` and opaque types where appropriate. Zero-cost abstractions aren't optional — they're the reason Kyo can be both safe and fast.

5. **Composition over inheritance.** Delegate, don't extend. No `protected`, no deep hierarchies. Build complex behavior by combining simple pieces.

6. **Type safety first, escape hatches as last resort.** Write type-safe code by default. `asInstanceOf` and `@unchecked` are acceptable only when they're strictly necessary inside opaque type boundaries or kernel internals where the type system can't express a known invariant — never as a convenience shortcut. Never use `@uncheckedVariance`. `Frame`, `Tag`, and `AllowUnsafe` guard the public surface where users interact.

7. **Symmetry across related types.** Paired or complementary types should share the same structural patterns (factory methods, config, lifecycle) and naming conventions. Keep names consistent — if one type uses `close`, the paired type should too. When users learn one side, the other should feel familiar.

8. **Explain the surprising, skip the obvious.** A comment on a race condition is essential. A comment on `get` returning a value is noise.

---

## API Design

### Naming
| Don't write                | Write instead                          | Why                                     |
| -------------------------- | -------------------------------------- | --------------------------------------- |
| `traverse`                 | `foreach`                              | Describes the action, not the structure |
| `sequence`                 | `collectAll`                           | Says what it does                       |
| `pure` / `succeed`         | `Kyo.lift` or rely on implicit lifting | No ceremony for the common case         |
| `void` / `as(())`          | `.unit`                                | Direct                                  |
| `*>` / `>>`                | `.andThen`                             | Readable without memorizing operators   |
| `replicateM`               | `fill`                                 | Plain English                           |
| `filterA`                  | `filter`                               | Same name as stdlib                     |
| `foldM`                    | `foldLeft`                             | Same name as stdlib                     |
| `bracket`                  | `acquireRelease`                       | Says what it does                       |
| `provide` / `provideLayer` | `Env.run` / `Env.runLayer`             | Consistent `run` pattern                |

**No symbolic operators** in `kyo-data`, `kyo-prelude`, or `kyo-core`. Use named methods (`.andThen`, `.unit`, `.map`). Symbolic operators like `*>`, `<*>`, `<&>` live exclusively in `kyo-combinators` for users who prefer that style.

Effect operations follow consistent naming:
- **`run`** eliminates an effect: `Abort.run`, `Var.run`, `Emit.run`, `Choice.run`, `Env.run`. Common handler variants beyond `run`:
  - `runWith(v)(continue)` — canonical handler with continuation
  - `runTuple` — returns `(State, A)` tuple
  - `runDiscard` — discards emitted/intermediate values
  - `runFirst` — handles only the first occurrence
  - `runPartial` / `runPartialOrThrow` — handles a subset of a union error type
  Non-`run` eliminators have specific semantics beyond elimination:
  - `recover` / `recoverError` — recovers from errors with a fallback value
  - `catching` — catches exceptions and converts to effect errors
  - `fold` / `foldError` — maps all result cases (success/failure) to a single return type
- **`get`** extracts a value from a container type, lifting the error case into the effect. `Abort.get(either)` extracts the `Right` value, lifting `Left` into `Abort`. `Abort.get(maybe)` extracts `Present`, lifting `Absent` into `Abort`. For context effects, `get` demands the current value: `Env.get[R]: R < Env[R]`, `Var.get[V]: V < Var[V]`.
- **`use`** applies a function to the gotten value: `Env.use[R](f: R => A < S)`, `Var.use[V](f: V => A < S)`.
- **`init`** / **`initWith`** / **`use`** / **`initUnscoped`** / **`initUnscopedWith`** — resource factory variants with increasing lifecycle control:
  - `init` — creates a `Scope`-managed resource (default choice)
  - `initWith(f)` — creates a `Scope`-managed resource and applies `f` to it
  - `use(f)` — bracket semantics without `Scope` in the effect set
  - `initUnscoped` — no cleanup guarantees, caller manages lifecycle
  - `initUnscopedWith(f)` — no cleanup + applies `f`

  See "Resource Factory Convention" in the Unsafe Boundary section for the full delegation chain.
- **`fooPure`** suffix for pure (non-effectful) variants: `mapPure`, `filterPure`, `collectPure`, `contramapPure`. The pure version avoids suspension overhead. Used consistently across `Stream`, `Pipe`, and `Sink`.
- **`fooDiscard`** drops the return value: `offer` returns `Boolean`, `offerDiscard` returns `Unit`. Same for `complete`/`completeDiscard`, `interrupt`/`interruptDiscard`, etc.
- **Sync-try vs async-wait** — sync-try operations use names that imply attempt (`offer`, `poll`) and return a success indicator (`Boolean`, `Maybe`). Async-wait operations use names that imply completion (`put`, `take`) and suspend until done. The async version tries the sync version first and only suspends on failure.
- **`noop`** / **`Noop`** for degenerate cases (formally, the identity implementation): `Latch(0)` returns a pre-completed noop, `Meter.Noop` is a no-op meter that passes through without rate-limiting. This is both an optimization (avoids allocating real state when nothing will happen) and a naming convention for when you need an identity/pass-through implementation of a type.

### Types

When a Kyo primitive exists for a concept, use it instead of the stdlib equivalent.

**kyo-data** — foundational value types:

| Kyo primitive  | Replaces                                                   | Notes                                                                                                                                                                                            |
| -------------- | ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `Maybe[A]`     | `Option[A]`                                                | `Option` only as conversion input (e.g., `Abort.get(opt: Option[A])`). When stdlib methods return `Option` (e.g., `collectFirst`), convert to `Maybe` as soon as possible via `Maybe.fromOption` |
| `Result[E, A]` | `Either`, `Try`                                            | Three-way: `Success`/`Failure`/`Panic` — never raw `Either` or `Try` in effect signatures                                                                                                        |
| `Chunk[A]`     | `Seq`, `List`, `Vector`                                    | Use internally; accept generic collections in public APIs (see below)                                                                                                                            |
| `Duration`     | `java.time.Duration`, `scala.concurrent.duration.Duration` | Opaque `Long`-based, zero-allocation                                                                                                                                                             |
| `Instant`      | `java.time.Instant`                                        | Kyo's own wrapper with consistent API                                                                                                                                                            |
| `Span[A]`      | `IArray[A]`, `ArraySeq[A]`                                 | Immutable array wrapper, avoids boxing, O(1) indexing                                                                                                                                            |
| `Schedule`     | Custom retry/timing logic                                  | Composable scheduling policies                                                                                                                                                                   |
| `TypeMap[A]`   | Heterogeneous maps                                         | Type-safe map keyed by type                                                                                                                                                                      |

**kyo-prelude** — effects:

| Kyo primitive | Purpose              | Notes                                                               |
| ------------- | -------------------- | ------------------------------------------------------------------- |
| `Abort[E]`    | Short-circuit errors | Typed error channel; use `Abort.fail`, `Abort.recover`, `Abort.run` |
| `Env[R]`      | Dependency injection | Context effect; use `Env.get`, `Env.run`                            |
| `Var[V]`      | Mutable state        | Effect-tracked state; use `Var.get`, `Var.set`, `Var.run`           |
| `Emit[V]`     | Value emission       | Push-based output; use `Emit.value`, `Emit.run`                     |
| `Poll[V]`     | Value polling        | Pull-based input; use `Poll.one`, `Poll.run`                        |
| `Choice`      | Nondeterminism       | Multiple values; use `Choice.eval`, `Choice.run`                    |
| `Check`       | Assertion checking   | Lightweight validation; use `Check.apply`, `Check.run`              |
| `Batch`       | Automatic batching   | Groups operations for batch execution                               |
| `Memo`        | Memoization          | Caches results; opaque alias for `Var[Memo.Cache]`                  |

**kyo-prelude** — streaming and composition:

| Kyo primitive   | Purpose                  | Notes                                                             |
| --------------- | ------------------------ | ----------------------------------------------------------------- |
| `Stream[V, S]`  | Lazy effectful sequences | Chunked push/pull hybrid; prefer over manual `Emit` for sequences |
| `Pipe[A, B, S]` | Stream transformation    | Composable `Stream[A] => Stream[B]`                               |
| `Sink[V, A, S]` | Stream consumption       | Composable `Stream[V] => A`                                       |
| `Layer[Out, S]` | Dependency injection     | Composable; use `Layer.init` for compile-time wiring              |

**kyo-core** — effects:

| Kyo primitive | Purpose                | Notes                                                                  |
| ------------- | ---------------------- | ---------------------------------------------------------------------- |
| `Sync`        | Effect suspension      | Marks side-effecting code; use `Sync.defer { ... }`                    |
| `Async`       | Concurrency            | Main effect for concurrent programming; prefer over direct `Fiber` use |
| `Scope`       | Resource management    | `acquireRelease`, `ensure`; structured cleanup via `Scope.run`         |
| `Clock`       | Time operations        | `now`, `sleep`, `deadline`; `withTimeControl` for testing              |
| `Log`         | Logging                | `trace`, `debug`, `info`, `warn`, `error` with level control           |
| `Console`     | Console I/O            | `readLine`, `print`, `printLine`, `printErr`                           |
| `Random`      | Random generation      | Seeded or context-bound; use for testability                           |
| `System`      | Environment/properties | Type-safe access with custom `Parser`s                                 |
| `Retry`       | Retry with policy      | Takes a `Schedule` from kyo-data                                       |

**kyo-core** — concurrency primitives:

| Kyo primitive                                              | Purpose                  | Notes                                                                               |
| ---------------------------------------------------------- | ------------------------ | ----------------------------------------------------------------------------------- |
| `Fiber[A]`                                                 | Async computation handle | Low-level; prefer `Async` API in application code                                   |
| `Channel[A]`                                               | Async message passing    | Bounded, backpressured; use over raw queues for async communication                 |
| `Queue[A]`                                                 | Concurrent collection    | Synchronous ops, bounded/unbounded; use `Channel` when async backpressure is needed |
| `Hub[A]`                                                   | Broadcast messaging      | Fan-out to multiple listeners; built on `Channel` + `Fiber`                         |
| `Signal[A]`                                                | Reactive value           | Current + change notification; use for observable state                             |
| `AtomicInt`, `AtomicLong`, `AtomicBoolean`, `AtomicRef[A]` | Atomic operations        | Thread-safe single-value containers                                                 |
| `LongAdder`                                                | High-contention counter  | Use over `AtomicLong` for write-heavy workloads                                     |
| `Meter`                                                    | Concurrency control      | Semaphore, mutex, rate limiter — composable via `pipeline`                          |
| `Latch`                                                    | One-shot barrier         | Use for coordination points                                                         |
| `Barrier`                                                  | Reusable barrier         | Use for phased coordination                                                         |

**kyo-core** — error types:

| Kyo primitive | Purpose             | Notes                                                            |
| ------------- | ------------------- | ---------------------------------------------------------------- |
| `Closed`      | Resource closed     | Standard error for closeable resources; carries creation `Frame` |
| `Timeout`     | Operation timed out | Used by `Async.timeout` and related APIs                         |
| `Interrupted` | Fiber interrupted   | Used for cancellation; extends `Panic` semantics                 |
| `Rejected`    | Admission rejected  | Load shedding signal from `Admission`                            |

Accept generic collections in public APIs, use `Chunk` internally:
```scala
def foreach[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](
    source: CC[A]
)(f: Safepoint ?=> A => B < S)(using Frame, Safepoint): CC[B] < S =
    Kyo.foreach(Chunk.from(source))(f).map(source.iterableFactory.from(_))
```

### Method Signatures


#### `(using Frame)` as Type Parameter Separator

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

#### `using` Clause Ordering

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

#### Frame and Tag

- **`Frame`** on every method that suspends or handles effects. Never on pure data accessors (`capacity`, `size`). Always `inline` on inline methods for zero-cost source location capture.
- **`Tag`** when runtime effect dispatch is needed — parametric effects like `Var[V]`, `Emit[V]`, `Env[R]` require tags because the handler must identify which effect to match at runtime.

#### `@targetName` for Extension Methods

Use `@targetName` to disambiguate extension methods on opaque types that erase to the same JVM signature as methods on the underlying type.

#### Overload Organization

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

## Code Conventions

### Pending Type (`A < S`)

- Use `.map`, not `.flatMap` — identical on pending types; `flatMap` exists for for-comprehensions only
- Use `.andThen(next)` to sequence, not `.map(_ => next)`
- Use `.unit` to discard a result to `Unit < S`
- Prefer `.map` chains over for-comprehensions — use for-comprehensions when readability benefits (many dependent steps)
- Use `.handle(Abort.run, Env.run(x))` for left-to-right handler pipelines instead of nested calls
- Prefer `Abort.recover` over `Abort.run` + `Result` pattern matching

### Scala Conventions

- Use `discard(expr)` to suppress unused value warnings, not `val _ = expr`
- Provide `CanEqual` for all comparable types — use `derives CanEqual` on case classes and enums. Skip types with non-comparable fields like functions.
- Minimize explicit type parameters at call sites — APIs should infer well from value arguments. If a user must write `Abort.fail[String]("error")` instead of `Abort.fail("error")`, that's a design smell. Use the `(using Frame)` type parameter separator pattern (see [Method Signatures](#method-signatures)) when user-specified types are unavoidable
- Explicit return types on public API only — let the compiler infer elsewhere:
  ```scala
  def offer(v: A)(using Frame): Boolean < (Sync & Abort[Closed])   // public — explicit
  private def helper(v: A) = ...                                    // private — inferred
  val result = someCall()                                           // val — inferred
  val chunk = Chunk.from(values)                                    // not Chunk.from[A](values)
  ```
- No `protected` — use `private[kyo]` or `private[kernel]`
- All public APIs in the `kyo` package — internal code uses `kyo.internal`
- Avoid `asInstanceOf` and `@unchecked` — acceptable only inside opaque type boundaries or kernel internals. Never use `@uncheckedVariance`
- Imports: specific over wildcard, internal wildcards OK, grouped by origin
- Keep `S` open if appropriate: use `A < (S & SomeEffect)` instead of `A < SomeEffect`
- Prefer call-by-name (`body: => A < S`) for methods that capture a side-effecting body. This is a safety net in case the user forgets to suspend side effects — without call-by-name, `Abort.catching(connection.read())` would execute `read()` eagerly before `catching` can intercept the exception
- Mark methods `final` in abstract classes/traits when not intended for override
- Run `sbt "scalafmtCheckAll"` before submitting

### Documentation

#### Type-Level Scaladoc

Every main public type needs a scaladoc (8-35 lines) covering:

- Opening sentence — what the type *is*, brief and definitional
- Conceptual "why" — 1-3 paragraphs on mental model and design rationale
- Feature/capability bullets — key operations and behaviors
- Gotcha callouts — `WARNING:`, `IMPORTANT:`, or `Note:` for surprising behavior
- `@tparam` tags for all type parameters
- `@see` references — 3-6 links per type, grouped by topic (creation, handling, related types)
- No code examples unless demonstrating composition patterns or system property syntax (rare)

WARNING/IMPORTANT/Note decision:
- **`WARNING:`** — risk of data loss, memory exhaustion, or incorrect behavior if misused
- **`IMPORTANT:`** — subtle semantic distinction that affects correctness
- **`Note:`** — behavioral clarification or platform difference

#### Method-Level Scaladoc

- Brief description (1-3 lines)
- `@param` / `@return` only when name and type aren't enough
- Skip for truly trivially obvious methods (`capacity: Int`, `size: Int`)

#### Inline Comments

Methods typically have short comments to aid understanding when they're more complex. Comments appear in these situations:

1. **Method-level clarity** — a brief comment on what a non-trivial method does, especially when the name alone isn't enough
2. **Phase markers in multi-step methods** — brief labels marking logical sections within a method body (`// extract path params`, `// combine inputs`, `// map errors`). Help readers scan without reading every line. One line per phase, not a paragraph. Comments must add understanding — don't restate what the function name already says (`// process completed transfers` before `processCompletedTransfers()` is noise).
3. **Navigational comments in large methods (30+ lines)** — longer methods benefit from slightly more comments even when individual lines are self-evident, because readers lose context over many lines. These act as signposts helping someone skim the method's structure without reading every line.
4. **Race conditions / concurrency hazards** — explain the interleaving
5. **Bit-packing / encoding schemes** — diagram the layout
6. **Known limitations / TODOs** — describe what's missing and why
7. **Non-obvious algorithmic choices** — explain *why*, not *what*

Quality bar: every comment should pass the test "would removing this make the code harder to understand?" If the answer is no, consider deleting it.

### File Organization

A source file should read like a guided tour of the type. A contributor opening it for the first time learns — in order — what the type is, how to create it, how to use it, and only then how it works internally. Scaladocs set the context, method ordering tells the story, and section separators mark the chapters.

#### File Template

```scala
package kyo                           // or kyo.internal

import kyo.specific.imports
import scala.annotation.tailrec

/** Type-level scaladoc.
  *
  * Conceptual explanation.
  *
  * @tparam A description
  * @see [[kyo.Related]]
  */
sealed trait MyEffect[A] extends ArrowEffect[...] // or opaque type, final class

object MyEffect:

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

    // --- Nested Types ---

    object Unsafe:
        ...

    // --- Internal ---

    private[kyo] def internal(...) = ...

end MyEffect
```

#### Readability Ordering

The file template above reflects a deliberate top-to-bottom reading order:

1. **Type definition + scaladoc** — the reader learns what the type is and why it exists
2. **Public API** — organized into groups by usage pattern, most-used first within each group:
   1. Suspend/create methods
   2. Query/access methods
   3. Handler methods
   4. Factory methods (for resource types)
3. **Nested types** — `Unsafe`, `Config`, auxiliary case classes, public type aliases
4. **Internal methods** — implementation details, `private[kyo]` helpers, internal-only givens

Within each group, simple overloads come before complex ones. The canonical implementation sits next to its variants so the reader sees the delegation at a glance. Scaladocs on each method flow naturally from one to the next — each building on context established by the previous.

This ordering matters because it determines how quickly a contributor can orient themselves. A well-ordered file answers "what does this do?" and "how do I use it?" without scrolling.

#### Visibility Tiers

| Modifier       | Scope         | Use for                                |
| -------------- | ------------- | -------------------------------------- |
| *(none)*       | Public        | User-facing API                        |
| `private[kyo]` | Cross-package | Internal utilities used across modules |
| `private`      | Class-local   | Mutable state, helpers                 |

#### Section Separators

Use `// ---...` separators with section names in all files:

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

#### `export` for Nested Type Promotion

When a nested type is heavily used, promote it to package level with `export`:

```scala
// In Fiber.scala — after the companion object
export Fiber.Promise   // makes kyo.Promise available without Fiber. prefix
```

Use sparingly — only for types that users reference frequently enough that qualification would be noisy.

---

## Optimization

### Performance

- Prefer `final class` for concrete types and `abstract class` over `trait` for base types — JVM interface dispatch is more expensive than class dispatch. Use `trait` only when defining a pure interface (like effect types extending `ArrowEffect`/`ContextEffect`)
- Mark classes `final` unless `sealed` or `abstract`
- Provide pure-function variants (`mapPure`, `filterPure`) when a hot transformation doesn't need suspension
- Fast-path before slow-path — always check for degenerate cases (empty, single-element, already-resolved) before entering the general/expensive path:
  ```scala
  source match
      case Nil          => Chunk.empty           // empty: return immediately
      case head :: Nil  => f(head).map(Chunk(_)) // single: avoid Loop setup
      case list         => Loop.indexed(...)      // general case
  ```
- Use opaque types — never wrap when you can alias. See [Zero-Cost Type Design](#zero-cost-type-design)
- Use `inline` strategically — inline creation paths, not handling paths. See [Inline Guidelines](#inline-guidelines)
- Prefer `@tailrec` loops — allocate continuations only when effects force suspension
- **Never block a thread** — use `Async`-based suspension (`Channel.put`, `Fiber.get`, `Clock.sleep`) instead of blocking primitives (`Thread.sleep`, `CountDownLatch.await`, `synchronized`, `Future.await`). Blocking a thread starves the fiber scheduler and defeats Kyo's concurrency model
- Prefer lock-free algorithms (CAS + `@tailrec`) over blocking synchronization
- Bit-pack atomically-updated composite state to avoid wrapper allocations. Always include a layout comment:
  ```scala
  // Bit allocation:
  // Bits 0-15 (16 bits): depth (0-65535)
  // Bit 16 (1 bit): hasInterceptor flag
  // Bits 17-63 (47 bits): threadId
  ```
- Avoid the erased tag pattern — existing usages are tech debt from when `Tag` had limitations with variant effects. `Tag` no longer has this limitation, so do not introduce new instances:
  ```scala
  // tech debt — do not copy
  private inline def erasedTag[E]: Tag[Abort[E]] = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]
  ```

### Zero-Cost Type Design

Kyo achieves zero-cost abstractions through opaque types. When designing a new type, choose the strategy that eliminates allocation on the hot path.

**Union discriminability:** When using union types, ensure all components are fully discriminable at runtime. Overlapping erasures will cause incorrect dispatch.

| Strategy               | Example                                      | Wraps                        | When to use                                                   |
| ---------------------- | -------------------------------------------- | ---------------------------- | ------------------------------------------------------------- |
| Opaque over primitive  | `Duration = Long`                            | Raw primitive                | Numeric quantities (time, size, count)                        |
| Opaque over JVM type   | `Instant = JInstant`                         | Existing class               | Wrapping a well-tested JVM type with a safer/simpler API      |
| Opaque over union      | `Maybe[A] = Absent \| Present[A]`            | Union of subtypes            | Discriminated types where the success path avoids boxing      |
| Opaque over array      | `Span[A] = Array[? <: A]`                    | Mutable array                | Immutable view of array data without copying                  |
| Opaque with lazy ops   | `Text = String \| Op`                        | String or deferred operation | When operations can be deferred until materialization          |
| Opaque over Unsafe     | `Channel[A] = Channel.Unsafe[A]`             | Unsafe implementation        | Concurrent types with safe/unsafe tiers (see Unsafe Boundary) |
| Effect alias           | `Async <: (Sync & Async.Join)`               | Subtype bounds               | Composing effects via subtype relationships                   |
| Subtype-bounded opaque | `Queue.Unbounded[A] <: Queue[A] = Queue[A]`  | Parent opaque                | Expressing subtype relationships between opaque types         |

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
- `Tag` — automatically derived; only customize if the type has special encoding

**Sealed trait vs opaque type:**
- Use **opaque type** when you want zero-cost wrapping of an existing representation
- Use **sealed abstract class** or **enum** when you need pattern matching on cases or case class features (structural equality, copy). Reserve sealed traits for effect type hierarchies
- Both can coexist: `Result` is an opaque union whose members (`Success`, `Failure`, `Panic`) are sealed subtypes

### Inline Guidelines

`inline` is powerful but creates code bloat when overused. The codebase follows a deliberate strategy: **inline the creation path (where effects are born), not the handling path (where effects are processed), and inline function parameters to avoid closure allocation**.

**IMPORTANT: Inline as little as possible.** When a method needs `inline`, mark only the function/by-name parameters as `inline` and keep the method body small. This eliminates closure allocation while minimizing code bloat at each call site. Two patterns:

1. **Trivial body** — the method body is a one-liner, so inlining the whole thing is fine:
   ```scala
   // Maybe.map — body is a simple branch, no bloat risk
   inline def map[B](inline f: A => B): Maybe[B] =
       if isEmpty then Absent else f(get)
   ```

2. **Non-trivial body** — define a local non-inline `def` for the real logic. The `inline` method eliminates the lambda allocation for `f`, but the loop/recursion compiles as a regular method that doesn't duplicate at every call site:
   ```scala
   // Pending.map — inline entry point, non-inline loop
   inline def map[B, S2](inline f: Safepoint ?=> A => B < S2)(...): B < (S & S2) =
       @nowarn("msg=anonymous") def mapLoop(v: A < S)(using Safepoint): B < (S & S2) =
           v match
               case kyo: KyoSuspend[...] => ...  // recursive loop logic
               case v => f(v.unsafeGet)
       mapLoop(v)
   ```

When in doubt, don't inline. The cost of unnecessary inlining (code bloat, slower compilation) is higher than the cost of a method call on a non-hot path.

**DO inline:**

| Category                                 | Examples                                                    | Why                                                                                          |
| ---------------------------------------- | ----------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| Effect suspend/create calls              | `Abort.fail`, `Var.set`, `Emit.value`, `Choice.evalSeq`     | Direct calls to `ArrowEffect.suspend`/`suspendWith` — must be zero-cost                      |
| Thin wrappers and redirects              | `Var.get` → `use(identity)`, `Maybe.isDefined` → `!isEmpty` | Compiler folds them away entirely                                                            |
| Kernel framework entry points            | `ArrowEffect.handle`, `ArrowEffect.suspend`                 | Backbone of the effect system; enables compile-time specialization                           |
| Simple predicates and branches           | `Maybe.getOrElse`, `Maybe.filter`, `Abort.when`             | Lets the compiler optimize branches at each call site                                        |
| `private[kyo]` hot-path helpers          | `Var.runWith`, internal handler implementations             | Optimizes internal glue without affecting public API size                                    |
| Methods with function/by-name parameters | `Maybe.map`, `Maybe.flatMap`, `Var.use`, `Result.fold`      | Eliminates `Function1` allocation — the lambda body is substituted directly at the call site |

**DO NOT inline:**

| Category                                | Examples                                                   | Why                                                                                                  |
| --------------------------------------- | ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Public effect handlers/runners          | `Abort.run`, `Var.run`, `Emit.runFold`, `Choice.run`       | These call back into the kernel; inlining would duplicate complex handler logic at every call site   |
| Methods that take only value parameters | `Maybe.get`, `Maybe.zip`, `Maybe.contains`, `Maybe.toList` | No function/by-name parameters means no closure to eliminate, so inlining adds bloat without benefit |

**The pattern in practice** — look at any effect like `Abort`:
- `Abort.fail`, `Abort.panic`, `Abort.get`, `Abort.when` → **inline** (creation)
- `Abort.run`, `Abort.runWith`, `Abort.recover`, `Abort.fold` → **not inline** (handling)

Similarly for data types like `Maybe`:
- `Maybe.map`, `Maybe.flatMap`, `Maybe.filter`, `Maybe.getOrElse` → **inline** (simple branches)
- `Maybe.get`, `Maybe.zip`, `Maybe.contains`, `Maybe.flatten` → **not inline** (complex logic)

**`@nowarn` for inlined lambdas** — when inlining a function parameter causes the compiler to warn about unused anonymous classes, use `@nowarn("msg=anonymous")` — the class elimination is intentional.

---

## Testing

### Framework

Each module defines an `abstract class Test` in `src/test/scala/kyo/Test.scala` that extends `AsyncFreeSpec with NonImplicitAssertions` and mixes in one of the base traits below. Test classes extend their module's `Test`, not ScalaTest traits directly. Test files are named `FooTest.scala` (not `FooSpec`) and mirror the main source structure.

### Base Trait Hierarchy

| Trait                  | Module     | Purpose                                                                               |
| ---------------------- | ---------- | ------------------------------------------------------------------------------------- |
| `BaseKyoDataTest`      | kyo-data   | Compile-time type checking (`typeCheck`, `typeCheckFailure`)                          |
| `BaseKyoKernelTest[S]` | kyo-kernel | Parameterized `run` method, platform-conditional runners (`runJVM`, `runNotJS`, etc.) |
| `BaseKyoCoreTest`      | kyo-core   | Full `run` handling `Abort[Any] & Async & Scope` with timeout                         |

Each module has a `src/test/scala/kyo/Test.scala` that wires ScalaTest to the appropriate base trait:

```scala
// kyo-data
abstract class Test extends AsyncFreeSpec, NonImplicitAssertions, BaseKyoDataTest:
    override type Assertion = org.scalatest.Assertion
    override val assertionSuccess: Assertion = Succeeded
    override def assertionFailure(msg: String): Assertion = fail(msg)

// kyo-core (and modules that depend on it)
abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:
    type Assertion = org.scalatest.Assertion
    def assertionSuccess = succeed
    def assertionFailure(msg: String) = fail(msg)
```

### Test Patterns by Level

**kyo-data** — pure assertions, no effects:
```scala
"name" in {
    assert(Maybe(42) == Maybe(42))
}
```

**kyo-prelude** — effect evaluation with `.eval`:
```scala
"name" in {
    assert(Abort.run(Abort.fail("error")).eval == Result.fail("error"))
}
```

**kyo-core+** — effectful tests using `run`:
```scala
"name" in run {
    for
        channel <- Channel.init[Int](2)
        _       <- channel.put(1)
        v       <- channel.take
    yield assert(v == 1)
}
```

### Platform-Conditional Tests

Use platform-specific runners for tests that depend on a particular platform:

```scala
"jvm only" in runJVM { ... }
"not js" in runNotJS { ... }
"not native" in runNotNative { ... }
"js only" in runJS { ... }
```

Platform-specific source code lives in `src/test/scala-jvm/`, `src/test/scala-js/`, etc.

### Compile-Time Tests

Verify that code compiles (or fails to compile with expected errors):

```scala
"valid code compiles" in {
    typeCheck("Env.get[Int]")
}

"invalid code fails" in {
    typeCheckFailure("Layer.init[String]()")("Missing Input: scala.Predef.String")
}
```

The expected error string in `typeCheckFailure` is a substring match.

### Concurrent Test Helpers

Use `untilTrue` for eventually-consistent assertions in concurrent tests. It retries the condition every 10ms until it returns `true` or the test times out:

```scala
"eventually consistent" in run {
    for
        counter <- AtomicInt.init(0)
        _       <- Async.run(counter.incrementAndGet.unit)
        _       <- untilTrue(counter.get.map(_ > 0))
    yield assert(true)
}
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
- `T.Unsafe` — the form depends on the type: `sealed abstract class` when multiple implementations are needed (e.g., open/closed states), or `opaque type` for zero-cost wrapping of a single Java/platform type (e.g., `AtomicInt.Unsafe = AtomicInteger`). Operations take `(using AllowUnsafe)` instead of `(using Frame)`
- `T.Unsafe.init(...)` — factory in the `Unsafe` companion (takes `using AllowUnsafe`; add `Frame` only if the factory uses it, e.g., to capture creation context for `Closed`). Unsafe methods should return raw values or `Result`s, not effectful computations (`A < S`). `Frame` in unsafe code is primarily for capturing creation context, not for effect suspension
- `T.init(...)` / `T.use(...)` / `T.initUnscoped(...)` — safe factories that delegate to `Unsafe.init` with lifecycle management (see "Factory methods" in Naming above)

**Safe→Unsafe bridge**: Safe methods enter the unsafe tier via `Sync.Unsafe.defer { ... }`, which provides `AllowUnsafe` implicitly. Inside, they call the `Unsafe` method and wrap `Result[Closed, A]` in `Abort.get` to convert to `Abort[Closed]` effect.

**Subtypes preserve the pattern**: `Queue.Unbounded` has `Queue.Unbounded.Unsafe` that extends `Queue.Unsafe`. The subtype relationship holds on both tiers.

### Unsafe API Conventions

- **WARNING scaladoc** on every `Unsafe` class and `object Unsafe` — always the same text: "Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details." Skip detailed method-level scaladocs for `Unsafe` APIs — the WARNING class-level scaladoc is sufficient. Add method-level docs only when the behavior is non-obvious or differs from the safe counterpart.
- **`(using AllowUnsafe)`** on every method that performs side effects without suspension. Pure accessors like `capacity` don't need it.
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
       Sync.Unsafe.defer(Abort.get(self.offer(v)))
   ```

3. **Import danger** — external runtime callbacks (Netty listeners, platform interop), application boundaries (KyoApp, tests), and initialization of globally shared module-level values that need unsafe operations at class loading time (e.g., `Clock.live`, `Async.defaultConcurrency`):
   ```scala
   import AllowUnsafe.embrace.danger
   ```

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

`AllowUnsafe` is a compiler-enforced proof that the side effect has already been suspended at an outer scope. Methods can perform side effects directly without allocating a `Sync.Unsafe` suspension, while the proof guarantees the call chain is rooted in a properly suspended context.

This is the mechanism behind the safe→unsafe bridge. The safe tier suspends via `Sync.Unsafe`, which provides `AllowUnsafe` implicitly, then calls the `Unsafe` method that performs the side effect directly:

```scala
// Safe tier — suspends once, then delegates
def set(v: Boolean)(using Frame): Unit < Sync = Sync.Unsafe.defer(unsafe.set(v))

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
    Sync.Unsafe.defer(Abort.get(self.offer(v)))
```

Resource factory convention — all variants delegate down to `Unsafe.init`:

| Method                | Lifecycle                   | Effect Set         | Delegates to                          |
| --------------------- | --------------------------- | ------------------ | ------------------------------------- |
| `init`                | `Scope`-managed cleanup     | `Sync & Scope`     | `initWith(identity)`                  |
| `initWith(f)`         | `Scope`-managed + callback  | `Sync & Scope & S` | `Unsafe.init` + `Scope.ensure(close)` |
| `use(f)`              | Bracket (no `Scope` needed) | `Sync & S`         | `Unsafe.init` + `Sync.ensure(close)`  |
| `initUnscoped`        | No cleanup guarantees       | `Sync`             | `initUnscopedWith(identity)`          |
| `initUnscopedWith(f)` | No cleanup + callback       | `Sync & S`         | Bare `Unsafe.init`                    |

The delegation chain (using `Channel` as canonical example):
```scala
// init delegates to initWith with identity — Scope-managed lifecycle
def init[A](capacity: Int, access: Access)(using Frame): Channel[A] < (Sync & Scope) =
    initWith[A](capacity, access)(identity)

// initWith: create resource, register Scope cleanup, apply callback
inline def initWith[A](capacity: Int, access: Access)[B, S](
    inline f: Channel[A] => B < S
)(using inline frame: Frame): B < (S & Sync & Scope) =
    Sync.Unsafe.defer:
        val channel = Unsafe.init[A](capacity, access)
        Scope.ensure(Channel.close(channel)).andThen:
            f(channel)

// use: bracket semantics via Sync.ensure — no Scope in the effect set
inline def use[A](capacity: Int, access: Access)[B, S](
    inline f: Channel[A] => B < S
)(using inline frame: Frame): B < (S & Sync) =
    Sync.Unsafe.defer:
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
    Sync.Unsafe.defer(f(Unsafe.init[A](capacity, access)))
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
- Extend `KyoException`, not `Exception` or `RuntimeException` — `KyoException` already includes `NoStackTrace`, so you don't need to add it yourself
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

   | Handler                                 | When to use                                                       |
   | --------------------------------------- | ----------------------------------------------------------------- |
   | `ArrowEffect.handle`                    | Simple one-shot handling, no looping or state                     |
   | `ArrowEffect.handleFirst`               | Handle only the first occurrence, get a continuation for the rest |
   | `ArrowEffect.handleLoop` (no state)     | Loop through all occurrences without accumulating state           |
   | `ArrowEffect.handleLoop` (with state)   | Loop with state threaded through each occurrence                  |
   | `ArrowEffect.handleLoop` (state + done) | Same, plus a final transformation when computation completes      |

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

| Higher           | Delegates to        | Adds                                 |
| ---------------- | ------------------- | ------------------------------------ |
| `Channel`        | `Queue`             | Fiber-aware put/take with suspension |
| `Hub`            | `Channel` + `Fiber` | Fan-out distribution fiber           |
| `Async`          | `Fiber`             | Structured concurrency, isolation    |
| `Stream`         | `Emit[Chunk[V]]`    | Chunked processing, transformations  |
| `Meter.pipeline` | `Seq[Meter]`        | Composed admission control           |

Build new types by composing existing ones. Create anonymous instances when composition logic varies:
```scala
def pipeline[S](meters: Seq[Meter < (Sync & S)]): Meter < (Sync & S) =
    Kyo.collectAll(meters).map { seq =>
        new Meter:
            def run[A, S](v: => A < S)(using Frame) = ...
    }
```

### Isolate Protocol for Fiber-Crossing Operations

Methods that fork computations carrying arbitrary effect state (`A < (Abort[E] & Async & S)`) into new fibers must use the three-step `Isolate` protocol to safely move effectful state across fiber boundaries. When the forked computation has a concrete type with no extra effects (e.g., `Int < Async`), no Isolate is needed because there is no effect state to transfer across the fiber boundary.

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
