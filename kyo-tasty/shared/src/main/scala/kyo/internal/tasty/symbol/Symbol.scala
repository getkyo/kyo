package kyo.internal.tasty.symbol

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Tasty
import kyo.internal.tasty.query.ClasspathRef

/** Internal factory for `Tasty.Symbol` instances.
  *
  * Provides `makeSymbol` which uses the package-private constructor of `Tasty.Symbol`. The per-file Addr->Symbol map (`mutable.HashMap[Int,
  * Tasty.Symbol]`) is held in the pass-1 context (`AstUnpickler`) and is not stored here.
  */
object Symbol:

    /** Create a new `Tasty.Symbol` with the given fields.
      *
      * `home` is stored but not called during pass 1. `origin` records the TASTy body slice or Java origin for pass 2 / Phase 5.
      * `javaMetadata` is populated for Java-sourced symbols by ClassfileUnpickler.
      */
    def makeSymbol(
        kind: Tasty.SymbolKind,
        flags: Tasty.Flags,
        name: Tasty.Name,
        owner: Tasty.Symbol,
        home: ClasspathRef,
        origin: Tasty.Symbol.Origin,
        javaMetadata: Maybe[Tasty.JavaMetadata]
    ): Tasty.Symbol =
        Tasty.Symbol.make(kind, flags, name, owner, home, origin, javaMetadata)

end Symbol
