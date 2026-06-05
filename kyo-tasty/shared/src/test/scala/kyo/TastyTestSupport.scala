package kyo

/** Shared test helpers for kyo-tasty test files.
  *
  * Phase 21g cleanup: extracts the makeNamed helper that was duplicated verbatim in TastyAnnotationTest and TastyTypeTest. Both files now
  * extend this trait.
  *
  * Phase 08 update: makePlaceholder is deleted; makeNamed now returns Type.Named(SymbolId(-1)) directly.
  */
trait TastyTestSupport:

    /** Build a synthetic Named type.
      *
      * Returns Type.Named(SymbolId(-1)) -- the sentinel unresolved id. All tests that call makeNamed only use
      * the returned type for structural assertions, never for symbol lookup.
      */
    protected def makeNamed(fqn: String): Tasty.Type.Named =
        Tasty.Type.Named(Tasty.SymbolId(-1))
    end makeNamed

end TastyTestSupport
