# kyo-core contributor guide

This file documents the internal design contracts, invariants, and conventions
specific to `kyo-core`. Read the root `CONTRIBUTING.md` first; everything there
applies here, and this file extends it with module-local rules.

---

## Architecture overview

`kyo-core` is the primary effect module. It provides:

- **Concurrency**: `Async`, `Fiber`, `Channel`, `Queue`, `Hub`, `Latch`, `Barrier`, `Meter`, `Gate`
- **Time and scheduling**: `Clock`, `Duration`, `Deadline`
- **Streams**: `Stream`, `Pipe`, `Sink` (in `kyo-prelude`; `StreamCoreExtensions.scala` adds core-specific combinators)
- **Atomic primitives**: `AtomicInt`, `AtomicLong`, `AtomicBoolean`, `AtomicRef`, `Adder`
- **Environment and process**: `Console`, `Log`, `Signal`
- **Application entry**: `KyoApp`, `KyoAppInterrupts`
- **Resource management**: `Scope`, `Sync`, `Sync.Unsafe`
- **Data types**: `Chunk`, `Span` (in `kyo-data`; re-exported here)
- **Observability**: `Stat`

Every API in this module is cross-platform (JVM, Scala.js / Node, Scala Native)
unless it is in a `jvm/`, `js/`, or `native/` source tree and explicitly
documented as platform-specific.

---

## Kyo primitives mandate

Use Kyo types throughout `kyo-core`:

| Use this   | Not this             |
|------------|----------------------|
| `Maybe`    | `Option`             |
| `Result`   | `Either` / `Try`     |
| `Chunk`    | `List` / `Seq`       |
| `Span`     | `Array` (public ADT) |

---

## Safe-by-default tier

Every public API is in the safe tier. The unsafe tier (`Sync.Unsafe`)
exists for integrators and performance-critical bridging only.
Every site that calls `AllowUnsafe` or `Sync.Unsafe.defer` must have a
`// Unsafe:` comment explaining which safe-tier contract it is bridging.
