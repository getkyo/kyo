package kyo.net.internal.transport

import java.util.concurrent.atomic.AtomicInteger
import kyo.*

/** Spy decorator over a real [[Log.Unsafe]].
  *
  * Delegates all methods to the real underlying log and counts calls to info(). Used as the injection into [[WritePump]] (which accepts a
  * log parameter) to assert that the pump logs expected messages without replacing the real logging behavior.
  *
  * The real log runs on every call; no logging is suppressed. infoCount increments before delegating.
  */
final class RecordingLog(real: Log.Unsafe, val infoCount: AtomicInteger = new AtomicInteger(0)) extends Log.Unsafe:

    // infoCount: number of info() calls (both overloads). A constructor param so a logger derived via
    // withName shares the same counter, keeping the spy's count accurate across a rename.

    def name: String = real.name

    def withName(name: String): Log.Unsafe = new RecordingLog(real.withName(name), infoCount)

    def level: Log.Level = real.level

    def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
        real.trace(msg)

    def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
        real.trace(msg, t)

    def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
        real.debug(msg)

    def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
        real.debug(msg, t)

    def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
        discard(infoCount.getAndIncrement())
        real.info(msg)
    end info

    def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
        discard(infoCount.getAndIncrement())
        real.info(msg, t)
    end info

    def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
        real.warn(msg)

    def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
        real.warn(msg, t)

    def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
        real.error(msg)

    def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
        real.error(msg, t)

end RecordingLog
