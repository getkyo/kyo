package kyo.internal.reflect.symbol

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Reflect
import kyo.internal.reflect.query.ClasspathRef

/** Internal factory for `Reflect.Symbol` instances.
  *
  * Provides `makeSymbol` which uses the package-private constructor of `Reflect.Symbol`. The per-file Addr->Symbol map
  * (`mutable.HashMap[Int, Reflect.Symbol]`) is held in the pass-1 context (`AstUnpickler`) and is not stored here.
  */
object Symbol:

    /** Create a new `Reflect.Symbol` with the given fields.
      *
      * `home` is stored but not called during pass 1. `origin` records the TASTy body slice or Java origin for pass 2 / Phase 5.
      * `javaMetadata` is populated for Java-sourced symbols by ClassfileUnpickler.
      */
    def makeSymbol(
        kind: Reflect.SymbolKind,
        flags: Reflect.Flags,
        name: Reflect.Name,
        owner: Reflect.Symbol,
        home: ClasspathRef,
        origin: Reflect.Symbol.Origin,
        javaMetadata: Maybe[Reflect.JavaMetadata] = Absent
    ): Reflect.Symbol =
        Reflect.Symbol.make(kind, flags, name, owner, home, origin, javaMetadata)

end Symbol
