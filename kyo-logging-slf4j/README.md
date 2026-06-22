# kyo-logging-slf4j

An SLF4J backend for Kyo's `Log` effect. Build a `Log` from an SLF4J logger name (or an existing `org.slf4j.Logger`) and install it for a scope via `Log.let`, so every `Log.info` / `Log.warn` / `Log.error` call inside that scope routes through SLF4J. Whatever appenders, encoders, and level configuration your SLF4J setup provides (Logback, Log4j 2, java.util.logging, ...) handles the output.

The level gate is read live on each call by querying `isTraceEnabled` / `isDebugEnabled` / `isInfoEnabled` / `isWarnEnabled` / `isErrorEnabled` on the underlying SLF4J logger (the most permissive enabled level wins: trace, debug, info, warn, error, silent). Runtime SLF4J reconfiguration is reflected immediately without rebuilding the backend. Every log message is automatically prefixed with the caller's source position captured from the implicit `Frame`.

This module is JVM-only. SLF4J is a JVM API; there is no JS or Native artifact.

```scala
import kyo.*

val log: Log = SLF4JLog("com.example.app")

val program: Unit < Sync =
    Log.let(log) {
        Log.info("starting request")
    }
```

The opening already exercises everything this module ships: both `SLF4JLog.apply` paths (by name above), `Log.let` for scoped installation, the live level gate, and the message-emitting calls that pick up the caller `Frame`. The rest of this document walks the same surfaces one cluster at a time.

## Installing an SLF4J backend

You install an SLF4J backend in two steps: build a `kyo.Log` from an SLF4J logger, then scope it with `Log.let`. The default `Log.live` is a console logger that writes to `kyo.logs` at `warn` level; without `Log.let`, constructing an `SLF4JLog` does nothing on its own.

The SLF4J API is declared as a dependency of this module, but no implementation is bundled. Add Logback, Log4j 2, slf4j-jdk14, or another SLF4J binding to your build for output to actually appear.

> **Caution:** importing `kyo.SLF4JLog` or constructing one without installing it via `Log.let` leaves the default console logger active for the surrounding effect. The new logger only routes messages emitted inside the `Log.let` block.

### Construction by logger name

`SLF4JLog(name)` resolves the SLF4J logger via `LoggerFactory.getLogger(name)` and wraps it. This is the common path: most applications have a named logger per package or feature, configured in `logback.xml` (or equivalent) with the appender, encoder, and level you want.

```scala
import kyo.*

val log: Log = SLF4JLog("com.example.app")

val program: Unit < Sync =
    Log.let(log) {
        for
            _ <- Log.info("worker started")
            _ <- Log.info("job complete")
        yield ()
    }
```

The string passed to `apply` is forwarded verbatim to SLF4J's `LoggerFactory.getLogger`; logger hierarchies, additivity, and per-package level overrides defined in your SLF4J configuration apply unchanged.

### Construction from an existing `Logger`

When the surrounding application or framework already holds an `org.slf4j.Logger` (for example a logger injected by a container, or one looked up against a class instead of a name), pass it to `SLF4JLog.apply` directly.

```scala
import kyo.*
import org.slf4j.LoggerFactory

val rawLogger = LoggerFactory.getLogger(classOf[MyService])
val log: Log  = SLF4JLog(rawLogger)

val program: Unit < Sync =
    Log.let(log) {
        Log.info("MyService is up")
    }

class MyService
```

Both overloads return `kyo.Log`; the choice between them is purely whether you have a name or a `Logger` instance in hand. Behavior is identical from that point on.

### Scoping with `Log.let`

`Log.let` (from `kyo-core`) replaces the ambient `Log` for the duration of the passed effect. It is the only way to swap the backend: there is no global registration, no implicit pickup, no environment variable.

```scala
import kyo.*

val workerLog = SLF4JLog("com.example.worker")
val auditLog  = SLF4JLog("com.example.audit")

val program: Unit < Sync =
    for
        _ <- Log.let(workerLog) {
            Log.info("starting batch")
        }
        _ <- Log.let(auditLog) {
            Log.info("permission check passed")
        }
    yield ()
```

Inside each `Log.let` block, every direct or transitive `Log.info` / `Log.warn` / `Log.error` call sees the installed `Log`. Outside the block, the previously-installed `Log` (or the default `Log.live`) is back in scope.

## Behavior notes

Three implementation choices show up in real log output and routinely surprise readers expecting a thin SLF4J pass-through. All three are intentional.

### Source-position prefix on every message

Every message emitted through an `SLF4JLog` is prefixed with the caller's source position, formatted as `[file:line]`, taken from the implicit `kyo.Frame` at the call site.

```scala
import kyo.*

val log = SLF4JLog("com.example.app")

val program: Unit < Sync =
    Log.let(log) {
        // The SLF4J appender will see something like:
        //   INFO com.example.app [App.scala:42] starting request
        Log.info("starting request")
    }
```

The prefix is unconditional. If your SLF4J encoder pattern is `%level %logger %msg%n`, the rendered line above is exactly:

```
INFO com.example.app [App.scala:42] starting request
```

> **Note:** the prefix is added by `SLF4JLog`, not by your SLF4J layout. Patterns that already include `%file:%line` or `%caller` will produce both an SLF4J-rendered position and the `Frame`-derived one; remove the SLF4J-side caller fields from the layout pattern if the duplication bothers you.

Throwable overloads behave the same: the message gets the `[file:line]` prefix, and the `Throwable` flows through to SLF4J as the second argument, so encoders that render stack traces (`%ex`, the default in many Logback patterns) work unchanged.

```scala
import kyo.*
import scala.util.control.NoStackTrace

case object MyError extends RuntimeException with NoStackTrace

val log = SLF4JLog("com.example.app")

val program: Unit < Sync =
    Log.let(log) {
        Log.warn("downstream failure", MyError)
    }
```

### Live level gate

The `level` field on the returned `Log` is computed live on each access. The implementation checks `isTraceEnabled` / `isDebugEnabled` / `isInfoEnabled` / `isWarnEnabled` / `isErrorEnabled` on the underlying SLF4J `Logger` in that order and returns the most permissive enabled level. Runtime SLF4J reconfiguration (for example a Logback `setLevel` call or a JMX-triggered reload) is reflected by `log.level` immediately, without rebuilding the backend.

```scala
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LBLogger
import kyo.*
import org.slf4j.LoggerFactory

LoggerFactory.getLogger("com.example.app").asInstanceOf[LBLogger].setLevel(Level.INFO)
val log: Log = SLF4JLog("com.example.app")
assert(log.level == Log.Level.info)

// Runtime change to DEBUG is reflected immediately.
LoggerFactory.getLogger("com.example.app").asInstanceOf[LBLogger].setLevel(Level.DEBUG)
assert(log.level == Log.Level.debug)
```

> **Note:** `log.level` reflects the live SLF4J state because each access re-queries the wrapped logger's enabled flags. There is no stale snapshot; no rebuild is needed after a runtime SLF4J reconfiguration.

### Async dispatch

On JVM and Native, `Log` calls are async by default. Each call enqueues the event to a bounded background channel (default capacity 4096) and returns without waiting for the SLF4J appender to write. A daemon fiber drains the channel and forwards events to the backend in FIFO order.

> **When to rely on this with SLF4J:** for high-throughput logging, prefer the appender's own async mechanism (Logback's `AsyncAppender`, Log4j2's async loggers), which is tuned for the backend and batches I/O at the appender layer. Kyo's `Log.asyncLogging` is positioned primarily for the built-in `ConsoleLogger` and the JS/Native backends, which have no appender-level async to fall back on; with SLF4J it mainly keeps the enqueue off the calling fiber while the configured appender does the buffering.

`Log.flush: Unit < Async` suspends until the daemon has dispatched every currently-enqueued event. Call it before asserting appender output in tests, or before shutdown.

```scala
import kyo.*

val program: Unit < Async =
    Log.let(SLF4JLog("com.example.app")) {
        for
            _ <- Log.info("processing complete")
            _ <- Log.flush // await delivery before checking appender output
        yield ()
    }
```

Tests that capture output via thread-local stream redirection should set `-Dkyo.Log.asyncLogging=false` instead, since the daemon runs on a separate thread and does not inherit `DynamicVariable`-based redirections. On JS/Wasm, all writes are inline and `Log.flush` returns immediately.

## Low-level integration

The `SLF4JLog.Unsafe.SLF4J` class is the direct `kyo.Log.Unsafe` implementation. It is exposed for libraries that need to construct a `Log` without going through the `apply` factories (for example, to wrap the unsafe instance in further logic, or to integrate with a logger source that does not return a `String` name or `org.slf4j.Logger` reference).

Application code should not reach into `Unsafe`; use `SLF4JLog.apply` instead. The unsafe constructor exists for the same reason every Kyo `Unsafe` namespace does: integrators that already manage their own effect boundaries can opt out of `Sync` deferral, at the cost of an explicit `AllowUnsafe` capability at each call site.

```scala
import kyo.*
import org.slf4j.LoggerFactory

val unsafe: Log.Unsafe = new SLF4JLog.Unsafe.SLF4J(LoggerFactory.getLogger("com.example.bridge"))
val log: Log           = Log(unsafe)

// The wrapped Log behaves identically to one built via SLF4JLog.apply
val program: Unit < Sync =
    Log.let(log) {
        Log.info("bridge installed")
    }
```

Calling methods on `Unsafe.SLF4J` directly (`unsafe.trace`, `unsafe.warn`, ...) requires an in-scope `AllowUnsafe` and bypasses the `Sync` effect that the public `Log` methods produce. Prefer wrapping the unsafe instance in a `Log` and using the safe surface unless you have a concrete reason to skip the `Sync` boundary.

> **Note:** `Unsafe.SLF4J` computes `level` live on each access, exactly as the safe `SLF4JLog.apply` path does. Building the unsafe instance directly carries the same live-gate behavior.
