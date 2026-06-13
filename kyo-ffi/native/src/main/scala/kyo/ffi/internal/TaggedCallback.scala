package kyo.ffi.internal

/** Value-class pair of a user callback function plus its binding / method / kind tags. Stored in the Scala Native `CallbackRegistry`'s
  * per-shape transient stacks and retained slot arrays so trampolines can name the failing callback when the user function throws.
  *
  * Held in a plain final class rather than a `case class` to keep `AnyRef`-level access fast and avoid the `productElement` / `equals`
  * overhead the trampoline hot path does not need.
  *
  * `guardCore` is `null` for transient callbacks (their lifetime is bracketed by the surrounding FFI call, not a Guard). Retained callbacks
  * patch this field via [[CallbackRegistry.bindSlotToGuard]] once the owning [[NativeGuard]] calls `unsafeRetainRetainedSlot` so the
  * retained trampoline can gate its slot read on the guard's state.
  */
final class TaggedCallback(val bindingFqn: String, val methodName: String, val kind: String, val fn: AnyRef):
    /** Back-pointer to the owning [[GuardCore]], or `null` for transient callbacks. Written once after claim by
      * [[CallbackRegistry.bindSlotToGuard]]; read by retained trampolines to call `beginCallback` / `endCallback`. Marked `@volatile` so
      * the trampoline observes the write made by the user thread claiming the slot without taking a lock.
      */
    @volatile var guardCore: GuardCore | Null = null
end TaggedCallback
