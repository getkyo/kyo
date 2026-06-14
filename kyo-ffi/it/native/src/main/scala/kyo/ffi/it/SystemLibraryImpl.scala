package kyo.ffi.it

/** Scala Native system library init, no-op.
  *
  * Scala Native auto-links libc, so `@link("c")` is implicitly satisfied at link time. No runtime initialization is needed.
  */
private[it] object SystemLibraryInitImpl:
    def ensureInitialized(): Unit = ()
end SystemLibraryInitImpl
