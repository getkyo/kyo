package kyo

/** A safe wrapper around a `Log.Unsafe` backend that exposes each log level as a `Unit < Sync`
  * effect.
  *
  * Obtain a `Log` from an existing backend, or use the ambient logger via `Log.get`,
  * `Log.let`, or `Log.init`. Each method is inline and gated: the message thunk is evaluated
  * only when the event level is at or above the backend's current threshold.
  *
  * {{{
  * val myLog = Log(Log.Unsafe.ConsoleLogger("my.service", Log.Level.info))
  * // or, using the ambient logger:
  * Log.let(myLog) {
  *   for
  *     _ <- Log.info("starting")
  *     _ <- Log.warn("low disk space")
  *   yield ()
  * }
  * }}}
  *
  * All ten methods (`trace`, `debug`, `info`, `warn`, `error`, each with and without a
  * `Throwable`) suspend in `Sync`. No `Abort[Closed]` or `Scope` leaks to the call site.
  *
  * @param unsafe
  *   the underlying backend; accessible for integrations that need the raw API
  */
final case class Log(unsafe: Log.Unsafe):
    def level: Log.Level = unsafe.level
    def name: String     = unsafe.name

    private inline def emitAt(inline level: Log.Level)(inline msg: => String, inline throwable: Maybe[Throwable])(
        using inline frame: Frame
    ): Unit < Sync =
        Sync.Unsafe.defer(
            if level.enabled(unsafe.level) then
                Clock.nowWith { now =>
                    Log.emit(Log.Event(level, unsafe, msg, throwable, frame, now))
                }
            else ()
        )

    inline def trace(inline msg: => String)(using inline frame: Frame): Unit < Sync =
        emitAt(Log.Level.trace)(msg, Absent)
    inline def trace(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        emitAt(Log.Level.trace)(msg, Maybe(t))
    inline def debug(inline msg: => String)(using inline frame: Frame): Unit < Sync =
        emitAt(Log.Level.debug)(msg, Absent)
    inline def debug(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        emitAt(Log.Level.debug)(msg, Maybe(t))
    inline def info(inline msg: => String)(using inline frame: Frame): Unit < Sync =
        emitAt(Log.Level.info)(msg, Absent)
    inline def info(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        emitAt(Log.Level.info)(msg, Maybe(t))
    inline def warn(inline msg: => String)(using inline frame: Frame): Unit < Sync =
        emitAt(Log.Level.warn)(msg, Absent)
    inline def warn(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        emitAt(Log.Level.warn)(msg, Maybe(t))
    inline def error(inline msg: => String)(using inline frame: Frame): Unit < Sync =
        emitAt(Log.Level.error)(msg, Absent)
    inline def error(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        emitAt(Log.Level.error)(msg, Maybe(t))
end Log

/** Logging for Kyo applications.
  *
  * Provides a three-tier API: a safe `Log` case class whose methods suspend in `Sync`, a
  * static object tier (`Log.trace`, `Log.debug`, ...) that reads the ambient logger from a
  * `Local`, and a `Log.Unsafe` abstract class for custom backends.
  *
  * The ambient logger is bound with `Log.let` and derived with `Log.init`. On JVM and Native,
  * emit calls are forwarded to a background daemon fiber over a bounded channel; on JS/Wasm
  * the same path writes inline. The daemon is started lazily on the first async emit.
  *
  * Overflow when the channel is full is controlled by `Log.asyncLogging.overflow`, parsed from
  * `-Dkyo.Log.asyncLogging.overflow` at startup. The default (`SyncFallback`) writes the overflowing
  * event inline without dropping. `DropBelow(level)` drops events strictly below `level`.
  *
  * To drain all buffered events before asserting output in tests, call `Log.flush`. Tests that
  * capture output should use `-Dkyo.Log.asyncLogging=false` to force synchronous dispatch, since
  * the daemon runs on a separate thread that does not inherit thread-local stream redirections.
  *
  * @see [[Log.Level]] for ordering and the `silent` suppression sentinel
  * @see [[Log.Overflow]] for overflow policy configuration
  * @see [[Log.Unsafe]] for the low-level backend contract
  */
object Log extends kyo.internal.LogPlatformSpecific:

    /** Ordered severity levels for log filtering.
      *
      * Each level carries a numeric priority; a higher number means higher severity.
      * `enabled(other)` returns true when `other` is at or above the receiver's severity,
      * which is the gate predicate used by `Log` and `Log.Unsafe` methods.
      *
      * The six cases in ascending order:
      *   - `trace` (10): fine-grained diagnostic detail
      *   - `debug` (20): developer-facing diagnostic output
      *   - `info`  (30): informational milestones and state changes
      *   - `warn`  (40): recoverable conditions worth surfacing
      *   - `error` (50): failures requiring attention
      *   - `silent` (60): disables all output; a backend set to `silent` receives nothing
      *
      * Use `silent` as a threshold (not a level to emit at) to suppress all logging for a
      * particular scope without removing the logger from the ambient context.
      */
    enum Level(private val priority: Int) derives CanEqual:
        def enabled(other: Level): Boolean = other.priority <= priority
        case trace  extends Level(10)
        case debug  extends Level(20)
        case info   extends Level(30)
        case warn   extends Level(40)
        case error  extends Level(50)
        case silent extends Level(60)
    end Level

    val live: Log = Log(Unsafe.ConsoleLogger("kyo.logs", Level.warn))

    private val local = Local.init(live)

    /** Executes a function with a custom logger.
      *
      * @param log
      *   The custom logger to use
      * @param f
      *   The function to execute
      * @return
      *   The result of the function execution
      */
    def let[A, S](log: Log)(f: A < S)(using Frame): A < S =
        local.let(log)(f)

    /** Derives a logger named `name` from the currently active backend.
      *
      * Reads the active `Log` from the ambient context and mints a sibling logger of the
      * SAME backend with the new name, so a name is chosen without naming a backend.
      *
      * @param name
      *   The logger name
      * @return
      *   A `Log` of the active backend, named `name`
      */
    def init(name: String)(using Frame): Log < Sync =
        local.use(active => Sync.Unsafe.defer(Log(active.unsafe.withName(name))))

    /** Executes a function with a logger named `name`, derived from the active backend.
      *
      * Re-derives a sibling logger of the active backend named `name` and binds it for the
      * scope of `f`, in one step.
      *
      * @param name
      *   The logger name
      * @param f
      *   The function to execute
      * @return
      *   The result of the function execution, with `Sync` added to the effect row
      */
    def let[A, S](name: String)(f: A < S)(using Frame): A < (S & Sync) =
        init(name).map(let(_)(f))

    /** Suspends until the daemon has dispatched every currently-enqueued event.
      *
      * Returns `Unit < Async` because waiting for an off-fiber drain is a suspension. On
      * JS/Wasm, where all writes are already inline, this returns immediately without
      * suspending. Tests that capture output via thread-local stream redirection should
      * use `-Dkyo.Log.asyncLogging=false` instead, since the daemon runs on a separate thread
      * that does not inherit `DynamicVariable`-based redirections.
      */
    def flush(using Frame): Unit < Async = flushDaemon

    /** Gets the current logger from the local context.
      *
      * @return
      *   The current Log instance wrapped in an effect
      */
    def get(using Frame): Log < Any = local.get

    /** Executes a function with access to the current logger.
      *
      * @param f
      *   The function to execute, which takes a Log instance as input
      * @return
      *   The result of the function execution
      */
    def use[A, S](f: Log => A < S)(using Frame): A < S = local.use(f)

    /** Executes an effect with a console logger using the default name "kyo.logs" and debug level.
      *
      * @param v
      *   The effect to execute with the console logger
      * @return
      *   The result of executing the effect with the console logger
      */
    def withConsoleLogger[A, S](v: A < S)(using Frame): A < S =
        withConsoleLogger()(v)

    /** Executes an effect with a console logger using a custom name and log level.
      *
      * @param name
      *   The name to use for the console logger
      * @param level
      *   The log level
      * @param v
      *   The effect to execute with the console logger
      * @return
      *   The result of executing the effect with the console logger
      */
    def withConsoleLogger[A, S](name: String = "kyo.logs", level: Level = Level.debug)(v: A < S)(using Frame): A < S =
        let(Log(Unsafe.ConsoleLogger(name, level)))(v)

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe extends Serializable:
        /** The current minimum enabled level. A backend that wraps an externally configurable logger
          * (one whose level can change at runtime) MUST re-read that level on each access: implement
          * this as a live `def`, never a `val` that captures the level once at construction. A captured
          * value satisfies Scala's override rule but defeats dynamic level checking, so a later
          * reconfiguration of the underlying logger would never take effect. A backend whose level is
          * intrinsic and immutable, such as `ConsoleLogger`, may return a fixed value.
          */
        def level: Level
        def name: String
        def withName(name: String): Log.Unsafe

        def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit
        def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit
        def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit
        def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit
        def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit
        def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit

        /** Renders and writes a fully-formed event to this backend. This is the entry the async
          * drain and the synchronous-fallback path dispatch to. The default routes by level and
          * throwable to the per-level methods, so a backend that only implements those keeps
          * working. The `ConsoleLogger` overrides it to render `event.timestamp` (the emission
          * time) rather than re-reading the clock at write time.
          */
        def emit(event: Log.Event)(using allow: AllowUnsafe): Unit =
            event.throwable match
                case Present(t) =>
                    event.level match
                        case Level.trace  => trace(event.message, t)(using event.frame, allow)
                        case Level.debug  => debug(event.message, t)(using event.frame, allow)
                        case Level.info   => info(event.message, t)(using event.frame, allow)
                        case Level.warn   => warn(event.message, t)(using event.frame, allow)
                        case Level.error  => error(event.message, t)(using event.frame, allow)
                        case Level.silent => ()
                case Absent =>
                    event.level match
                        case Level.trace  => trace(event.message)(using event.frame, allow)
                        case Level.debug  => debug(event.message)(using event.frame, allow)
                        case Level.info   => info(event.message)(using event.frame, allow)
                        case Level.warn   => warn(event.message)(using event.frame, allow)
                        case Level.error  => error(event.message)(using event.frame, allow)
                        case Level.silent => ()

        def safe: Log = Log(this)
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        case class ConsoleLogger(name: String, level: Level) extends Log.Unsafe:
            def withName(name: String): Log.Unsafe = ConsoleLogger(name, level)

            // The async/sync dispatch entry. Renders the event's own emission timestamp (captured
            // at the log call by emitAt), so a line written later by the detached drain still shows
            // when the event was issued, not when the drain wrote it. The level gate here is the
            // dispatch-side companion of the gate emitAt already applied.
            override def emit(event: Log.Event)(using AllowUnsafe): Unit =
                if event.level.enabled(level) then
                    writeLine(event.level, event.frame, event.message, event.throwable, event.timestamp)

            // The direct unsafe tier (Log.live.unsafe.trace, ...) bypasses emitAt, so it gates here
            // and stamps with the ambient clock's now: a synchronous direct call's emission time is
            // the current instant, and reading the ambient clock keeps Clock time-control in reach.
            inline def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.trace.enabled(level) then writeLine(Level.trace, frame, msg, Absent, stamp)
            inline def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.trace.enabled(level) then writeLine(Level.trace, frame, msg, Present(t), stamp)

            inline def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.debug.enabled(level) then writeLine(Level.debug, frame, msg, Absent, stamp)
            inline def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.debug.enabled(level) then writeLine(Level.debug, frame, msg, Present(t), stamp)

            inline def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.info.enabled(level) then writeLine(Level.info, frame, msg, Absent, stamp)
            inline def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.info.enabled(level) then writeLine(Level.info, frame, msg, Present(t), stamp)

            inline def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.warn.enabled(level) then writeLine(Level.warn, frame, msg, Absent, stamp)
            inline def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.warn.enabled(level) then writeLine(Level.warn, frame, msg, Present(t), stamp)

            inline def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.error.enabled(level) then writeLine(Level.error, frame, msg, Absent, stamp)
            inline def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.error.enabled(level) then writeLine(Level.error, frame, msg, Present(t), stamp)

            private def stamp(using Frame, AllowUnsafe): Instant =
                Sync.Unsafe.evalOrThrow(Clock.nowWith(now => now))
            private def streamFor(lvl: Level): java.io.PrintStream =
                if lvl == Level.warn || lvl == Level.error then scala.Console.err else scala.Console.out
            private def writeLine(lvl: Level, frame: Frame, msg: String, t: Maybe[Throwable], ts: Instant): Unit =
                val stream = streamFor(lvl)
                stream.println(s"${ts.show} ${lvl.toString.toUpperCase} $name -- [${frame.position.show}] $msg")
                t.foreach(_.printStackTrace(stream))
            end writeLine
        end ConsoleLogger
    end Unsafe

    private inline def enqueue(
        inline level: Level
    )(inline msg: => String, inline throwable: Maybe[Throwable])(using inline frame: Frame): Unit < Sync =
        Sync.Unsafe.withLocal(local) { log =>
            log.emitAt(level)(msg, throwable)
        }

    /** Logs a trace message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An Sync effect that logs the message
      */
    inline def trace(inline msg: => String)(using inline frame: Frame): Unit < Sync =
        enqueue(Level.trace)(msg, Absent)

    /** Logs a trace message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An Sync effect that logs the message and exception
      */
    inline def trace(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        enqueue(Level.trace)(msg, Maybe(t))

    /** Logs a debug message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An Sync effect that logs the message
      */
    inline def debug(inline msg: => String)(using inline frame: Frame): Unit < Sync =
        enqueue(Level.debug)(msg, Absent)

    /** Logs a debug message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An Sync effect that logs the message and exception
      */
    inline def debug(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        enqueue(Level.debug)(msg, Maybe(t))

    /** Logs an info message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An Sync effect that logs the message
      */
    inline def info(inline msg: => String)(using inline frame: Frame): Unit < Sync =
        enqueue(Level.info)(msg, Absent)

    /** Logs an info message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An Sync effect that logs the message and exception
      */
    inline def info(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        enqueue(Level.info)(msg, Maybe(t))

    /** Logs a warning message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An Sync effect that logs the message
      */
    inline def warn(inline msg: => String)(using inline frame: Frame): Unit < Sync =
        enqueue(Level.warn)(msg, Absent)

    /** Logs a warning message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An Sync effect that logs the message and exception
      */
    inline def warn(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        enqueue(Level.warn)(msg, Maybe(t))

    /** Logs an error message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An Sync effect that logs the message
      */
    inline def error(inline msg: => String)(using inline frame: Frame): Unit < Sync =
        enqueue(Level.error)(msg, Absent)

    /** Logs an error message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An Sync effect that logs the message and exception
      */
    inline def error(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        enqueue(Level.error)(msg, Maybe(t))

    /** An immutable log event dispatched by the drain to the backend it was created with.
      *
      * Each event is self-contained: it carries its own `sink` so a single global drain can
      * serve multiple backends concurrently without reading the ambient `Local[Log]`.
      *
      * Fields:
      *   - `level`: the severity at which the event was emitted
      *   - `sink`: the `Log.Unsafe` backend that will receive this event
      *   - `message`: the already-forced log message string
      *   - `throwable`: an optional associated throwable, wrapped in `Maybe`
      *   - `frame`: the call-site source position captured at capture time
      *   - `timestamp`: the emission time, captured from the ambient `Clock` at the log call. The
      *     backend renders this value, so an async-dispatched line shows when the event was issued,
      *     not when the drain wrote it.
      */
    private[kyo] case class Event(
        level: Log.Level,
        sink: Log.Unsafe,
        message: String,
        throwable: Maybe[Throwable],
        frame: Frame,
        timestamp: Instant
    ) derives CanEqual

    /** The policy applied when the bounded async buffer is full and a producer offer is rejected.
      *
      * Parsed from the `-Dkyo.Log.asyncLogging.overflow` system property at startup, via a custom
      * `Flag.Reader[Log.Overflow]`. The property value is either `"SyncFallback"` or
      * `"DropBelow:<level>"` where `<level>` is one of the `Log.Level` names.
      *
      * The two cases:
      *   - `SyncFallback` (default): writes the overflowing event inline on the calling fiber,
      *     without dropping. This adds latency to the producer when the channel is persistently
      *     full, but guarantees every event is delivered.
      *   - `DropBelow(level)`: drops events strictly below `level` and writes those at or above
      *     `level` inline. Useful when high-volume low-severity events are acceptable to lose
      *     under load.
      */
    sealed private[kyo] trait Overflow derives CanEqual

    private[kyo] object Overflow:
        /** Default: writes the overflowing event inline on the calling fiber without dropping. */
        case object SyncFallback extends Overflow

        /** Drop events strictly below `level`; write the rest inline. */
        case class DropBelow(level: Log.Level) extends Overflow
    end Overflow

    /** Parses `-Dkyo.Log.asyncLogging.overflow`: `"SyncFallback"` or `"DropBelow:<level>"`. */
    private[kyo] given Flag.Reader[Overflow] with
        def typeName: String = "Log.Overflow"
        def apply(s: String): Either[Throwable, Overflow] =
            s.trim match
                case "SyncFallback" => Right(Overflow.SyncFallback)
                case other if other.startsWith("DropBelow:") =>
                    val token = other.stripPrefix("DropBelow:")
                    Maybe.fromOption(Level.values.find(_.toString == token)) match
                        case Present(level) => Right(Overflow.DropBelow(level))
                        case Absent         => Left(new IllegalArgumentException(s"unknown level: $token"))
                case other => Left(new IllegalArgumentException(s"unknown overflow policy: $other"))
    end given

    /** Async logging master switch (`-Dkyo.Log.asyncLogging`). `false` forces fully-synchronous
      * logging with no daemon. Default `true` on JVM/Native; has no effect on JS/Wasm, which are
      * always synchronous. The nested `capacity` and `overflow` flags tune the bounded buffer.
      */
    private[kyo] object asyncLogging extends StaticFlag[Boolean](true):
        /** Bounded buffer capacity, clamped `>= 1` (`-Dkyo.Log.asyncLogging.capacity`). */
        private[kyo] object capacity extends StaticFlag[Int](4096, n => Right(Math.max(1, n)))

        /** Default overflow policy (`-Dkyo.Log.asyncLogging.overflow`), parsed by the custom `Flag.Reader[Log.Overflow]`. */
        private[kyo] object overflow extends StaticFlag[Overflow](Overflow.SyncFallback)
    end asyncLogging

end Log
