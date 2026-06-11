package kyo.ffi.internal

/** JS implementation of the drain primitives used by [[GuardCore.drainInFlight]].
  *
  * JS is single-threaded so no retained callback can ever be in flight when [[GuardCore.close]] fires, the `drainInFlight` loop is
  * therefore unreachable in practice. These stubs exist purely to satisfy the shared `GuardCore` type; they never execute at runtime
  * because `inFlight.get() == 0` short-circuits the loop before any primitive is used. Scala.js does not ship `Thread.onSpinWait` or
  * `LockSupport` in its javalib, so we cannot delegate, the implementations here are intentional no-ops.
  */
private[internal] object GuardDrainSupport:
    def onSpinWait(): Unit           = ()
    def parkNanos(nanos: Long): Unit = ()
    def unpark(t: Thread): Unit      = ()
end GuardDrainSupport
