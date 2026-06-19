# kyo-logging-jpl

A `Log` backend that routes kyo-core's `Log` effect through `java.lang.System.Logger`, the JDK 9+ Java Platform Logging API (JPL). JPL is a service-loader interface, so the actual sink is whatever the JVM resolves at runtime: the built-in `java.util.logging` (JUL) implementation, Log4j via its JPL bridge, SLF4J via `slf4j-jdk-platform-logging`, or any other JPL-compatible binding. The typical first call is `Log.let(JavaLog("kyo.app")) { ... }`: wire a named JPL logger as the active `Log` for a scope, then call the ordinary `Log.info` / `Log.debug` / etc. surface from kyo-core inside that scope.

This module's API surface is intentionally tiny: one entry-point object with two factories. Everything beyond constructing the backend lives in kyo-core's `Log` and in the JDK's logger configuration. The level gate is evaluated live on each call by probing `Logger.isLoggable` for each JPL level, and call-site frames are prepended to each message so `[file:line]` shows up in the formatter output. JVM-only: `System.Logger` is a JDK interface with no JS or Native analog.

```scala
import kyo.*

// Resolve a JPL logger by name and install it for a scope.
Log.let(JavaLog("kyo.app")) {
    Log.info("user signed in")
    // With JUL's SimpleFormatter the line above prints, roughly:
    //   INFO: [Example.scala:5] user signed in
}
```

## Installing it with Log.let

Constructing a `JavaLog` does nothing on its own; you install it for a scope with `Log.let`. `Log.live` in kyo-core defaults to an internal console logger, and `JavaLog` does not replace that default globally: it produces a `Log` value you pass to `Log.let`. Everything outside the `Log.let` block keeps using whatever `Log` is in effect there.

### Construction by logger name

`JavaLog(name: String): Log`

The name-based factory is the typical first call. It delegates to `java.lang.System.getLogger(name)`, so the name is whatever the underlying JPL binding expects: with JUL that maps to a `java.util.logging.Logger` of the same name, with the SLF4J-to-JPL bridge it maps to an SLF4J `Logger`, and so on.

```scala
import kyo.*

val log: Log = JavaLog("kyo.app")

Log.let(log) {
    Log.info("backend wired")
}
```

The factory returns an ordinary `kyo.Log` value, so it can be passed around, stored in a layer, or installed with `Log.let` at the boundary of a request, a fiber, or `main`.

### Construction from an existing System.Logger

`JavaLog(logger: java.lang.System.Logger): Log`

When the caller already has a `java.lang.System.Logger` (resolved through a custom `LoggerFinder`, a service-loaded factory, or some other integration that hands the logger over), `JavaLog(logger)` wraps that exact instance instead of resolving a new one by name.

```scala
import java.lang.System.Logger
import kyo.*

val custom: Logger = java.lang.System.getLogger("kyo.app")
val log: Log       = JavaLog(custom)

Log.let(log) {
    Log.info("backend wired via custom finder")
}
```


## How log calls reach the JDK

Inside a `Log.let(JavaLog(...))` scope, every `Log.trace` / `Log.debug` / `Log.info` / `Log.warn` / `Log.error` call from kyo-core delegates to this backend, which decorates the message and dispatches to `logger.log(level, msg, throwable?)` on the underlying `System.Logger`. There are four behaviors worth knowing before the output surprises you.

### Frame prefix

Every message is wrapped as `s"[${frame.position.show}] $msg"` before reaching JPL. The `Frame` is the implicit kyo-core threads through the `Log` API, so the bracketed prefix is the source file and line of the original `Log.info` call, not of the backend.

```scala
import kyo.*

Log.let(JavaLog("kyo.app")) {
    Log.info("user signed in")
    // JPL receives: "[Example.scala:5] user signed in"
}
```

> **Caution:** JPL formatters that already include caller information (JUL's `SimpleFormatter` with the `%2$s` source-class field, custom formatters that emit `%C` or `%M`) will show the call site twice: once from the formatter, once from the bracketed kyo prefix. If you do not want the duplication, choose a formatter that omits the source field, or strip the bracketed prefix in a custom formatter.

### Level mapping

Kyo's levels map one-to-one onto JPL's:

| `kyo.Log.Level` | `java.lang.System.Logger.Level` |
|---|---|
| `trace`  | `TRACE`   |
| `debug`  | `DEBUG`   |
| `info`   | `INFO`    |
| `warn`   | `WARNING` |
| `error`  | `ERROR`   |
| `silent` | (no JPL level is loggable) |

`kyo.Log.Level.silent` does not correspond to JPL's `Level.OFF`; instead, it is reported when none of `TRACE` / `DEBUG` / `INFO` / `WARNING` / `ERROR` is loggable. `isLoggable(OFF)` is always false, so there is no mapping back from `OFF` to anything kyo can report.

### `level`: live gate

Querying `log.level` returns the logger's current threshold, not a value frozen at construction. Each access walks `isLoggable` from `TRACE` down to `ERROR` on the underlying `System.Logger` and returns the most permissive enabled level. Runtime reconfiguration (for example `java.util.logging.Logger.setLevel(...)`) is reflected immediately.

```scala
// log.level probes the logger live on each access:
//   if (isLoggable(TRACE)) trace
//   else if (isLoggable(DEBUG)) debug
//   else if (isLoggable(INFO))  info
//   else if (isLoggable(WARNING)) warn
//   else if (isLoggable(ERROR)) error
//   else silent
val log = JavaLog("kyo.app")
// log.level always reflects the current state of the underlying JPL logger.
```

> **Note:** The chain picks the most verbose enabled level. When both `TRACE` and `INFO` are loggable (the usual case for JUL where finer levels imply coarser ones), the reported level is `trace`, not the logger's effective threshold.

### Async dispatch

On JVM and Native, `Log` calls are async by default. Each call enqueues the event to a bounded background channel (default capacity 4096) and returns without waiting for the JPL logger to write. A daemon fiber drains the channel and forwards events to the backend in FIFO order.

`Log.flush: Unit < Async` suspends until the daemon has dispatched every currently-enqueued event. Call it before asserting logger output in tests, or before shutdown.

```scala
import kyo.*

val program: Unit < Async =
    Log.let(JavaLog("kyo.app")) {
        for
            _ <- Log.info("processing complete")
            _ <- Log.flush // await delivery before checking logger output
        yield ()
    }
```

Tests that capture output via thread-local stream redirection should set `-Dkyo.Log.asyncLogging=false` instead, since the daemon runs on a separate thread and does not inherit `DynamicVariable`-based redirections. On JS/Wasm, all writes are inline and `Log.flush` returns immediately.

## Configuring the underlying logger

This module does not configure JPL. It consumes whatever binding the JVM resolves via the JPL service-loader: `java.util.logging` is the JDK's default; Log4j Core ships a JPL-compatible `LoggerFinder`; SLF4J's `slf4j-jdk-platform-logging` artifact provides one too. Pick the binding by putting the right jar on the classpath / module path and configure it through that binding's own mechanism (`logging.properties` for JUL, `log4j2.xml` for Log4j, `logback.xml` for Logback-via-SLF4J, etc.).

The example that follows uses plain JUL because it ships in the JDK and needs no extra dependencies. The shape is the same for any binding.

```scala
import java.util.logging.{Logger as JulLogger, *}
import kyo.*

// Strip the default root handler so JDK-defaults don't pollute stderr.
val root = JulLogger.getLogger("")
root.getHandlers.foreach(root.removeHandler)

// Attach our own handler, set BOTH the logger and the handler to DEBUG.
val logger = JulLogger.getLogger("kyo.app")
logger.setLevel(Level.FINE)
val handler = new StreamHandler(java.lang.System.out, new SimpleFormatter)
handler.setLevel(Level.FINE) // logger.setLevel isn't enough
logger.addHandler(handler)

Log.let(JavaLog("kyo.app")) {
    for
        _ <- Log.trace("won't show up") // FINER, below the handler's threshold
        _ <- Log.debug("test message")  // FINE
        _ <- Log.info("info message")   // INFO
        _ <- Log.warn("warning", new RuntimeException("boom"))
    yield ()
}

// Output (SimpleFormatter, two lines per record):
//   <timestamp> kyo.Example log
//   FINE: [Example.scala:18] test message
//   <timestamp> kyo.Example log
//   INFO: [Example.scala:19] info message
//   <timestamp> kyo.Example log
//   WARNING: [Example.scala:20] warning
//   java.lang.RuntimeException: boom
//   ...
```

> **Caution:** With JUL, the logger's level and the handler's level are independent filters and both must allow a record for it to be emitted. Setting only `logger.setLevel(Level.FINE)` while the handler keeps its default `INFO` threshold silently drops every `Log.debug` call. The example sets both. The same dual-filter pattern shows up in most JPL bindings under different names (Log4j's `LoggerConfig` level vs. `Appender` filters, Logback's logger level vs. appender filters).

> **Note:** The JDK's default JUL configuration installs a `ConsoleHandler` on the root logger that writes to `System.err`. Without removing it (the `root.getHandlers.foreach(root.removeHandler)` line above), JUL log output will interleave with anything kyo writes to stderr from other sources. This is a JUL default, not a kyo behavior.

## Unsafe tier

### Sharing a Log.Unsafe instance

When you need to share a single `Log.Unsafe` across integrations, plug into kyo-core's `Log.Unsafe` machinery directly, or hand a `System.Logger` to a framework that already holds one, reach for `JavaLog.Unsafe.JPL`. `JavaLog.Unsafe.JPL(logger)` is the concrete `Log.Unsafe` implementation that `JavaLog(name)` and `JavaLog(logger)` wrap. Construction requires an `AllowUnsafe` because the `Log.Unsafe` surface bypasses kyo's `Sync` effect tracking.

```scala
import kyo.*
import kyo.AllowUnsafe

given AllowUnsafe = AllowUnsafe.embrace.danger

val unsafe: Log.Unsafe = new JavaLog.Unsafe.JPL(java.lang.System.getLogger("kyo.app"))
val log: Log           = Log(unsafe)

Log.let(log) {
    Log.info("wired via Unsafe tier")
}
```

`JavaLog(name)` and `JavaLog(logger)` already do exactly this internally. Reach for `JavaLog.Unsafe.JPL` directly only when you need the `Log.Unsafe` value itself: passing it into another component that takes `Log.Unsafe`, building a composite backend that fans out to multiple sinks, or probing the backend's live `level` without going through `Log.let`. For everyday code, the `JavaLog(...)` factories are the intended path.
