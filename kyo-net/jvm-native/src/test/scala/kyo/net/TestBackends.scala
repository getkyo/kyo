package kyo.net

import kyo.*
import kyo.net.internal.backend.IoBackendPlatform

/** The set of I/O backends the shared test harness fans every scenario over, exposed in a platform-uniform shape so the harness in
  * `kyo.net.Test` never names a platform's concrete registry type.
  *
  * The posix platforms (JVM and Native) drive their backends through `IoBackendPlatform.registered`, a `Seq` of `Entry{name, priority,
  * isAvailable, build(config): Transport}`. JVM registers io_uring, epoll, kqueue, and the always-available NIO floor; Native registers
  * io_uring, epoll, and kqueue (no NIO floor). Both registries expose the identical `Entry` shape, so this one shim serves both via the
  * `jvm-native` shared test source set; only the JS shim differs, because Node's registry holds a `Backend{name, isAvailable, createDriver}`
  * rather than an `Entry` with a transport-building thunk.
  *
  * Each [[Entry]] mirrors the real registry's own availability probe and transport construction: `build` invokes the registry entry's
  * `build`, which runs the driver-start-plus-`PosixTransport.init` (or `NioTransport.init`) sequence the posix tests use for a real
  * round-trip. The harness builds a transport only for an available backend, so an unavailable entry never constructs a driver.
  */
object TestBackends:

    /** One testable backend: its registry [[name]] (`io_uring`/`epoll`/`kqueue`/`nio`), whether it [[isAvailable]] on the current host, and a
      * [[build]] thunk that constructs and starts a real [[Transport]] over it. The thunk delegates to the production registry entry, so the
      * transport the harness exercises is exactly the one production selects when that backend wins.
      *
      * `build` takes the caller's [[Frame]] explicitly: constructing a transport calls into kyo's effect APIs, which require a `Frame`, but
      * the shim's `all` is a plain `val` with no `Frame` of its own (one cannot be derived inside the `kyo` package). The harness passes its
      * own `using Frame`, so the build is attributed to the test's call site.
      */
    final case class Entry(
        name: String,
        isAvailable: Boolean,
        build: (TransportConfig, Frame) => Transport
    )

    /** Every registered backend on this host, in registry order. The harness registers one leaf per entry and cancels the leaves whose
      * `isAvailable` is false, so a backend that should be available but is not stays visible as a canceled leaf rather than silently
      * vanishing.
      */
    val all: Seq[Entry] =
        import AllowUnsafe.embrace.danger
        IoBackendPlatform.registered.map { entry =>
            Entry(
                name = entry.name,
                isAvailable = entry.isAvailable,
                build = (config, frame) => entry.build(config)(using summon[AllowUnsafe], frame)
            )
        }
    end all

end TestBackends
