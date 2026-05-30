package kyo

import kyo.internal.tasty.query.ClasspathRef

/** Shared test helpers for kyo-tasty test files.
  *
  * Phase 21g cleanup: extracts the makeNamed helper that was duplicated verbatim in TastyAnnotationTest and TastyTypeTest. Both files now
  * extend this trait.
  */
trait TastyTestSupport:

    import AllowUnsafe.embrace.danger

    /** Build a synthetic Named type for the given dotted FQN.
      *
      * Splits the FQN on '.' and chains each segment as a Class symbol owned by the previous. The root is a Package sentinel with an empty
      * name and null owner (matching the real classpath sentinel convention).
      *
      * Example: makeNamed("scala.List") produces a Named(sym) whose sym.fullName.asString == "scala.List".
      */
    protected def makeNamed(fqn: String): Tasty.Type.Named =
        val parts = fqn.split("\\.").toList
        val root = Tasty.Symbol.make(
            Tasty.SymbolKind.Package,
            Tasty.Flags.empty,
            Tasty.Name(""),
            null,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val finalSym = parts.foldLeft(root) { (owner, part) =>
            Tasty.Symbol.make(
                Tasty.SymbolKind.Class,
                Tasty.Flags.empty,
                Tasty.Name(part),
                owner,
                new ClasspathRef,
                Tasty.Symbol.TastyOrigin.empty,
                Absent
            )
        }
        Tasty.Type.Named(finalSym)
    end makeNamed

end TastyTestSupport
