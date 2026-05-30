package kyo.internal.tasty.symbol

/** Opaque integer handle used to reference a Symbol within a Classpath instance.
  *
  * Values are produced exclusively by Classpath Pass C construction and obtained by users from `Classpath.symbol`,
  * `Classpath.rootSymbolId`, `Classpath.topLevelClassIds`, `Classpath.packageIds`, or from any `SymbolId` field on `Symbol` or `Type`.
  * Outside of `object Tasty`, callers cannot construct a SymbolId from a raw Int.
  *
  * Two SymbolId values produced by the same Classpath compare equal via `==` iff they refer to the same Symbol. SymbolId values are NOT
  * stable across distinct Classpath instances (different `Classpath.open` calls produce independent id spaces).
  */
opaque type SymbolId = Int

object SymbolId:

    /** Internal smart constructor. Callable only from `object Tasty` and from internal pickling code under the same compilation unit; user
      * code cannot invoke this.
      */
    private[kyo] def apply(i: Int): SymbolId = i

    extension (id: SymbolId)
        /** Internal accessor for the underlying integer value. Used by `Classpath.symbol(id)` to index the dense
          * `symbols: IndexedSeq[Symbol]` array.
          */
        private[kyo] def value: Int = id
    end extension

    given CanEqual[SymbolId, SymbolId] = CanEqual.canEqualAny

end SymbolId
