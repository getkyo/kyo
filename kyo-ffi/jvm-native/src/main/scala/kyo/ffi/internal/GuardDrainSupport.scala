package kyo.ffi.internal

/** Shared JVM/Native implementation of the drain primitives used by [[GuardCore.drainInFlight]].
  *
  * Uses `java.util.concurrent.locks.LockSupport.parkNanos` for waits and `Thread.onSpinWait` for the short pre-park burst. Both are
  * available on the JVM and in Scala Native's javalib, and produce the expected scheduler hints on their respective runtimes.
  */
private[internal] object GuardDrainSupport:
    def onSpinWait(): Unit = Thread.onSpinWait()
    // Carrier-thread substrate: the guard-drain wait parks the CLOSING thread (after a bounded spin budget) until
    // in-flight retained callbacks drain or the timeout elapses; never a scheduler-managed fiber. See kyo-ffi/CONTRIBUTING.md.
    def parkNanos(nanos: Long): Unit = java.util.concurrent.locks.LockSupport.parkNanos(nanos)
    def unpark(t: Thread): Unit      = java.util.concurrent.locks.LockSupport.unpark(t)
end GuardDrainSupport
