package kyo.net

/** Forces a specific I/O backend by name (`-Dkyo.net.backend`), consumed by `IoBackend.select`'s callers. A `StaticFlag`'s resolved property
  * name is derived from its own fully-qualified object name, so this MUST stay a top-level object directly in `kyo.net`: nesting it under
  * another object (e.g. `IoBackend`, in package `kyo.net.internal.backend`) would resolve to that object's qualified name instead and
  * silently stop honoring `-Dkyo.net.backend`.
  */
private[net] object backend extends kyo.StaticFlag[String]("")

/** Forces a specific TLS provider by name (`-Dkyo.net.tls`), consumed by `TlsProvider.selectFor` and its platform callers. See [[backend]]
  * for why this stays a top-level object in `kyo.net`.
  */
private[net] object tls extends kyo.StaticFlag[String]("")

/** The [[kyo.net.internal.posix.HostResolver]] DNS cache TTL in milliseconds (`-Dkyo.net.dnsTtl`), default 30000 (30 seconds). Backed by
  * `Long` rather than `Duration` because `StaticFlag` requires a `Flag.Reader[A]` and kyo-config has no built-in reader for `Duration`
  * (only `Int`/`Long`/`Double`/`Boolean`/`String`/`Seq[A]`); `.millis` converts at the one call site that needs a `Duration`. See [[backend]]
  * for why this stays a top-level object in `kyo.net`.
  */
private[net] object dnsTtl extends kyo.StaticFlag[Long](30_000L)

/** Number of independent I/O driver instances a transport builds (`-Dkyo.net.ioPoolSize`). Each driver owns its own poller or io_uring ring
  * fd; new connections are distributed round-robin across the pool, so this is the transport's multiplexing width. On the io_uring backend it
  * also sets the ring submission-queue depth, `max(256, ioPoolSize * 64)`.
  *
  * Based on the scheduler's carrier count rather than `availableProcessors`, because the drivers contend for scheduler carriers: capping
  * `-Dkyo.scheduler.coreWorkers` caps this too, instead of leaving N drivers competing for fewer carriers than they assume. Process-global
  * for the same reason the scheduler's own sizing is: it describes the machine's I/O fabric, not any one caller's behavior, and a transport is
  * a multiplexer shared across every client and server in the process. See [[backend]] for why this stays a top-level object in `kyo.net`.
  */
private[net] object ioPoolSize
    extends kyo.StaticFlag[Int](clampIoPoolSize(kyo.scheduler.coreWorkers() / 4), n => Right(clampIoPoolSize(n)))

/** Force a driver count into the only range a transport can build: at least one.
  *
  * A transport with zero drivers has nothing to round-robin connections across and cannot serve anything, so a `-Dkyo.net.ioPoolSize=0` (or a
  * negative value, or a scheduler configured with fewer than four carriers, where the default division floors to zero) is clamped rather than
  * rejected: the flag is read once at class load, long before any caller could handle a failure, and refusing to start the process over a
  * tuning knob would be a worse outcome than serving on one driver.
  *
  * Named rather than inlined into the flag so it is reachable: [[kyo.StaticFlag]] resolves once at class load and exposes neither its validator
  * nor a way to re-resolve, so a test that asserted this by setting the system property would depend on class-initialization order.
  */
private[net] def clampIoPoolSize(n: Int): Int = Math.max(1, n)
