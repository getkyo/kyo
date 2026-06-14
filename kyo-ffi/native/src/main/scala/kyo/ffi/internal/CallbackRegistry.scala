package kyo.ffi.internal

/** Snapshot of one shape's retained-callback pool utilization, returned by [[CallbackRegistry.poolStats]].
  *
  * @param used
  *   number of slots currently claimed (not returned to the free-list).
  * @param total
  *   total slot count, equal to [[CallbackRegistry.PoolSize]].
  * @param utilizationPercent
  *   `used * 100.0 / total`; `0.0` when `total == 0`.
  */
private[ffi] case class PoolStats(used: Int, total: Int, utilizationPercent: Double)

/** Scala Native runtime registry backing the generated callback trampolines.
  *
  * Scala Native 0.5's `CFuncPtr.fromScalaFunction` only accepts a top-level non-capturing `def` (via eta-expansion), a top-level `val`
  * holding a non-capturing lambda, or a literal lambda whose body references only its own parameters. Anything else, a closure over local
  * state, even a wrapped `FunctionN` passed as a parameter, is rejected at compile time with either "Function passed to method
  * fromScalaFunction needs to be inlined" or "Closing over local state of parameter … undefined behaviour". There is also no `void*`
  * user-data hatch for callbacks on Scala Native.
  *
  * This registry works around both limitations:
  *
  *   - **Transient path**: the generated method body pushes the user `FunctionN` onto a per-thread stack before the FFI call and pops it
  *     after. The top-level trampoline reads `peekTransient()` to recover it. A stack (not a slot) is used so re-entrant FFI calls,  *     callback A fires back into another FFI call whose comparator is callback B, nest cleanly.
  *   - **Retained path**: a fixed-size slot pool holds the user `FunctionN` for the lifetime of the `Ffi.Guard`. The generated method body
  *     calls `claimRetainedSlot_XXX` to obtain `(slotIdx, ptr)`, the `ptr` is a `CFuncPtr` pre-built at class-init time whose trampoline
  *     reads from that specific slot index. Because each trampoline is a per-slot top-level `def`, `fromScalaFunction` always sees a
  *     literal eta-expansion with no closure state. The guard pins a `() => releaseRetainedSlot_XXX(slotIdx)` closure via
  *     `NativeGuard.unsafeRetainCleanup` which runs at `guard.close()` to free the slot.
  *
  * Pool size defaults to 1024 slots per shape; override via `-Dkyo.ffi.native.retainedCallbackPoolSize=N`. Any guard can claim any free
  * slot from the global pool, there is no per-guard partitioning. Exhaustion throws `IllegalStateException` naming the shape, callers
  * either close unused guards, raise the pool size, or accept that this shape cannot hold more retained callbacks.
  *
  * A high-watermark warning (default threshold 75%) is emitted to stderr when pool utilization crosses the threshold, giving operators
  * early notice to tune pool sizes before exhaustion. The threshold is configurable via
  * `-Dkyo.ffi.native.retainedCallbackPoolWarnPercent=`. The `poolUsage(shapeId)` method provides a simple `(used, total)` tuple for runtime
  * monitoring.
  *
  * Thread-safety: transient stack is a `ThreadLocal` (safe under Scala Native's "C synchronously invokes the trampoline on the same OS
  * thread that made the downcall" contract). Retained-pool slot tracking uses a CAS-based `AtomicLongArray` bitset, lock-free and safe for
  * the roadmap's multi-thread pivot.
  *
  * Shape coverage is derived from a SINGLE source of truth: `project/CallbackShapesGen.scala`. Adding a new shape is one append to that
  * file's `SHAPES` list (rebuild re-emits `CallbackRegistryShapes.scala` + per-shape `RetainedTrampolines_*.scala` under `sourceManaged`).
  * The codegen-side shape resolver [[kyo.ffi.codegen.emitters.NativeCallbackCatalog]] has a mirror match arm per shape, update both in
  * lockstep when adding a shape.
  */
private[ffi] object CallbackRegistry extends CallbackRegistryShapes
