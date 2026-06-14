package kyo

/** Shared test helpers for kyo-tasty test files. */
trait TastyTestSupport:

    /** Build a synthetic Named type.
      *
      * Returns Type.Named(SymbolId(-1)) -- the sentinel unresolved id. All tests that call makeNamed only use
      * the returned type for structural assertions, never for symbol lookup.
      */
    protected def makeNamed(fullName: String): Tasty.Type.Named =
        Tasty.Type.Named(Tasty.SymbolId(-1))
    end makeNamed

end TastyTestSupport
