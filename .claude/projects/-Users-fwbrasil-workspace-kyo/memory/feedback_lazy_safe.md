---
name: Lazy safe referentially transparent
description: ALL facade operations must be lazy Kyo computations — matching Scala's execution model exactly
type: feedback
---

ALL operations in the Kyo TS facade must be lazy, safe, and referentially transparent.

**Execution model (matches Scala exactly):**
- `<.eval` / `Kyo.eval()` — pure exit point. Works when S = never (all effects handled). kyo-prelude effects are all pure.
- `Sync.run` — side-effect exit point. Evaluates `A < Sync`. The ONLY place `Sync.Unsafe.evalOrThrow` appears.
- `Async.run` — async exit point. Sugar over Sync.run + Fiber.initUnscoped. Returns JS Promise.

**kyo-prelude** = pure effects only (Abort, Env, Var, Emit, Check, Memo, Choice, Batch). No side effects. Can be fully handled then eval'd.

**kyo-core** = adds Sync and Async. These involve actual side effects (IO, mutable state, time, fibers). Cannot be eval'd — need Sync.run.

**How to apply:**
- Every method returns `JsKyo` (a lazy computation), never a direct value
- No `Sync.Unsafe.evalOrThrow` anywhere except `Sync.run`
- Factory methods (AtomicInt.init, Cache.init, etc.) return `Kyo<AtomicInt, Sync>`
- Instance methods (AtomicInt.get, Clock.now, etc.) return `Kyo<number, Sync>`
- `Sync.run` is the single side-effect exit point
- `Async.run` delegates through Sync
