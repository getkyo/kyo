package kyo.net.internal.posix

import kyo.*

/** One packed atomic carrying independent read-lock and write-lock holder bits plus a close bit:
  * the per-handle cross-direction ownership primitive (the Go `fdMutex` model).
  *
  * A single connection's read side (the loop carrier's recv-then-feed) and write side (the caller
  * carrier's encrypt/drain) can both be touching the handle's shared resources (the TLS engine, the
  * reused read buffer) when a close fires. Independent read/write holder accounting lets a read and
  * a write proceed simultaneously while two reads or two writes serialize, and the close bit makes
  * the last releasing holder run the deferred release exactly once. The separate read and write
  * holder halves are what let the full-duplex case proceed without blocking cross-direction.
  *
  * Encoding (single atomic Int): the low [[HandleGuard.ReadMask]] bits hold the read-holder count,
  * the next [[HandleGuard.WriteShift]] bits the write-holder count, and [[HandleGuard.CloseBit]]
  * records a requested close. The resources are released exactly once, by whichever holder observes
  * both counts at zero with the close bit set; the guard then stays at the terminal Closed value.
  */
final private[kyo] class HandleGuard(state: AtomicInt.Unsafe):

    /** Acquire the read half. Returns `true` when ownership was taken (the caller may touch the
      * read-side resources and MUST pair this with [[release]] passing `read = true`). Returns
      * `false` once a close has been requested.
      */
    def acquireRead()(using AllowUnsafe): Boolean = acquire(HandleGuard.ReadUnit)

    /** Acquire the write half. Returns `true` when ownership was taken (pair with [[release]]
      * passing `read = false`); `false` once a close has been requested.
      */
    def acquireWrite()(using AllowUnsafe): Boolean = acquire(HandleGuard.WriteUnit)

    /** Release a held half (one taken by an earlier acquireRead/acquireWrite). `read` selects which
      * holder count to decrement. Returns `true` when this caller is the LAST holder (both counts now
      * zero) WHILE a close is requested, so this caller must run the deferred release exactly once.
      */
    def release(read: Boolean)(using AllowUnsafe): Boolean =
        val unit      = if read then HandleGuard.ReadUnit else HandleGuard.WriteUnit
        var freedHere = false
        var done      = false
        while !done do
            val g       = state.get()
            val after   = g - unit
            val holders = after & HandleGuard.HolderBits
            val closing = (g & HandleGuard.CloseBit) != 0
            if holders == 0 && closing then
                if state.compareAndSet(g, HandleGuard.Closed) then
                    freedHere = true
                    done = true
            else if state.compareAndSet(g, after) then done = true
            end if
        end while
        freedHere
    end release

    /** Request close. If no holder is active (both counts zero), returns `true` immediately so the
      * caller runs the release now; otherwise sets the close bit and returns `false`, deferring the
      * release to the last holder's [[release]]. Idempotent once the close bit is set.
      */
    def requestClose()(using AllowUnsafe): Boolean =
        var releaseNow = false
        var done       = false
        while !done do
            val g = state.get()
            if (g & HandleGuard.CloseBit) != 0 || g == HandleGuard.Closed then done = true
            else
                val holders = g & HandleGuard.HolderBits
                if holders == 0 then
                    if state.compareAndSet(g, HandleGuard.Closed) then
                        releaseNow = true
                        done = true
                else if state.compareAndSet(g, g | HandleGuard.CloseBit) then done = true
                end if
            end if
        end while
        releaseNow
    end requestClose

    /** Whether a close has been requested or the resources are already released. */
    def isClosing()(using AllowUnsafe): Boolean =
        val g = state.get()
        (g & HandleGuard.CloseBit) != 0 || g == HandleGuard.Closed
    end isClosing

    private def acquire(unit: Int)(using AllowUnsafe): Boolean =
        var result = false
        var done   = false
        while !done do
            val g = state.get()
            if (g & HandleGuard.CloseBit) != 0 then done = true
            else if state.compareAndSet(g, g + unit) then
                result = true
                done = true
            end if
        end while
        result
    end acquire
end HandleGuard

private[kyo] object HandleGuard:
    // Encoding: 15 low bits read holders, next 15 bits write holders, bit 30 the close bit.
    final private val ReadShift  = 0
    final private val WriteShift = 15
    final private val CountBits  = (1 << 15) - 1
    final private val ReadMask   = CountBits << ReadShift
    final private val WriteMask  = CountBits << WriteShift
    final private val HolderBits = ReadMask | WriteMask
    final private val ReadUnit   = 1 << ReadShift
    final private val WriteUnit  = 1 << WriteShift
    final private val CloseBit   = 1 << 30
    final private val Closed     = -1

    /** Create a fresh guard under the caller's propagated AllowUnsafe (no embrace.danger at field
      * init: the unsafe value is built here and passed as a plain constructor param).
      */
    def init()(using AllowUnsafe): HandleGuard = new HandleGuard(AtomicInt.Unsafe.init(0))
end HandleGuard
