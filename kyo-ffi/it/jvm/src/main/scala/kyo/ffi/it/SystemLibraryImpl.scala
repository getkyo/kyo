package kyo.ffi.it

/** JVM-side system library init, no-op.
  *
  * The JVM Foreign Linker resolves literal short names ("c", "m") via its default `SymbolLookup.libraryLookup`, which dispatches to
  * `dlopen(3)` / equivalent. No env-var priming is needed.
  */
private[it] object SystemLibraryInitImpl:
    def ensureInitialized(): Unit = ()
end SystemLibraryInitImpl
