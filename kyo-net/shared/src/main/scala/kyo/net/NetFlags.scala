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
