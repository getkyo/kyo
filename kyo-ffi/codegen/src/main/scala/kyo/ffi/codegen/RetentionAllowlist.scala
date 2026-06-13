package kyo.ffi.codegen

/** Known-retaining C symbols (C stores the callback and invokes it later). When a method takes a function parameter without an `Ffi.Guard`
  * and its resolved C symbol matches, the generator emits a build warning.
  */
private[codegen] object RetentionAllowlist:
    val symbols: Set[String] = Set(
        "epoll_ctl",
        "pthread_create",
        "pthread_key_create",
        "signal",
        "sigaction",
        "atexit",
        "on_exit",
        "pthread_atfork"
    )

    /** `true` iff the resolved C symbol appears in the retention allowlist. */
    def contains(symbol: String): Boolean = symbols.contains(symbol)
end RetentionAllowlist
