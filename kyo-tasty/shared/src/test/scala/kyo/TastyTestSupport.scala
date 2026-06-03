package kyo

/** Shared test helpers for kyo-tasty test files.
  *
  * Phase 21g cleanup: extracts the makeNamed helper that was duplicated verbatim in TastyAnnotationTest and TastyTypeTest. Both files now
  * extend this trait.
  *
  * plan: phase-02 update; Symbol is now a case class; owner chaining replaced by simple synthetic Symbols.
  */
trait TastyTestSupport:

    import AllowUnsafe.embrace.danger

    /** Build a synthetic Named type for the given dotted FQN.
      *
      * plan: phase-02 bridge; produces a synthetic Symbol with the leaf name from the FQN. The full owner chain is not reconstructed since
      * Symbol.make only takes (kind, flags, name). Phase 09 restores full FQN via Symbol.fullName.
      */
    protected def makeNamed(fqn: String): Tasty.Type.Named =
        val leafName = fqn.split("\\.").last
        val sym = Tasty.Symbol.makePlaceholder(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name.Unsafe.init(leafName)
        )
        Tasty.Type.Named(sym.id)
    end makeNamed

end TastyTestSupport
