package kyo.internal

import kyo.*

/** Platform-agnostic logging helpers shared across JVM, Native, JS, and Wasm.
  *
  * Contains the pure dispatch, write, overflow, and channel-offer logic that operates on explicit
  * arguments without touching any global daemon state. The JVM/Native and JS/Wasm platform traits
  * each extend this trait and supply only the platform-specific `emit`, `flushDaemon`, and
  * `daemonInitCount` members.
  */
private[kyo] trait LogShared:

    /** Dispatches an event to its carried sink by invoking the matching level method. */
    private[kyo] def write(event: Log.Event)(using AllowUnsafe, Frame): Unit =
        LogShared.dispatch(event)

    /** Applies the given overflow policy for an event that could not be enqueued (full channel).
      *
      * DropBelow(level) writes the event inline when event.level is AT OR ABOVE the policy level
      * (i.e., event.level.enabled(level) holds), and drops it strictly below. This matches the
      * "drop strictly below `level`, write the rest inline" semantics of the DropBelow ADT.
      */
    private[kyo] def applyOverflow(event: Log.Event, policy: Log.Overflow)(using AllowUnsafe, Frame): Unit =
        policy match
            case Log.Overflow.SyncFallback     => write(event)
            case Log.Overflow.DropBelow(level) => if event.level.enabled(level) then write(event)

    /** Offers an event to an explicit channel, applying the overflow policy when the channel is full.
      *
      * Tests supply a synthetic capacity-1 channel and an explicit policy without involving the
      * global daemon or its StaticFlag config. The global path calls this with `daemon.channel`
      * and `daemon.overflow` (the policy resolved once at daemon start from `Log.async.overflow()`).
      */
    private[kyo] def enqueueWith(
        event: Log.Event,
        channel: Channel.Unsafe[Log.Event],
        policy: Log.Overflow
    )(using AllowUnsafe, Frame): Unit =
        channel.offer(event) match
            case Result.Success(true)                => ()
            case Result.Success(false)               => applyOverflow(event, policy)
            case Result.Failure(_) | Result.Panic(_) => write(event) // Closed (post-shutdown): synchronous fallback

end LogShared

/** Shared companion for platform-agnostic logging helpers, available on all platforms.
  *
  * Provides the pure `dispatch` function and the flush `fence` sentinel. Both are platform-agnostic:
  * dispatch resolves level -> sink method on explicit Event arguments; fence is a sentinel the drain
  * recognizes by identity and discards (its sink is never dispatched).
  */
private[kyo] object LogShared:

    // The flush fence: a sentinel event the drain recognizes by identity and discards (its sink is
    // never dispatched). Its timestamp and other fields are irrelevant semantically.
    // Lazy to avoid initialization order issues with object Log (Log extends LogPlatformSpecific
    // which extends LogShared; Log.live must be fully initialized before fence is accessed).
    private[kyo] lazy val fence: Log.Event =
        Log.Event(Log.Level.silent, Log.live.unsafe, "", Absent, Frame.internal, Instant.Min)

    private[kyo] def dispatch(event: Log.Event)(using AllowUnsafe, Frame): Unit =
        event.sink.emit(event)

end LogShared
