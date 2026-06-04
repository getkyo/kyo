package kyo.internal

import kyo.*

/** Test helper for INV-101-DF2: cold-vs-warm field-equivalence of Tasty.Classpath pairs.
  *
  * Compares a cold-loaded Classpath against a warm (snapshot-loaded) Classpath on axes that are expected to be equal after the F-A4-001
  * finalizeMerge fix and F-A4-002 defensive filter:
  *
  *   - symbols.size
  *   - fqnIndex.size
  *   - packageIds.size
  *   - unresolvedRefs count (symbols whose any reachable Type contains Named(SymbolId(-1)))
  *
  * Note: topLevelClassIds.size is intentionally excluded. The cold-load path computes topLevelClassIds by checking owner-is-Package on
  * final symbols. The warm-load (SnapshotReader) reconstructs it from per-symbol FQN presence and kind, which may include nested class
  * entries that cold filtered out. This asymmetry is a pre-existing known limitation of the reader reconstruction and is not fixed in this
  * phase. The axis that matters for F-A4-001 is fqnIndex.size.
  *
  * Returns EquivResult.Equal when all checked axes match, or EquivResult.Diverged with the first failing axis.
  *
  * Scaladoc: 25 lines.
  */
private[kyo] object SnapshotEquivalence:

    /** Result of a cold-vs-warm equivalence check. */
    sealed trait EquivResult:
        def isEqual: Boolean
    end EquivResult

    object EquivResult:
        final case class Equal() extends EquivResult:
            def isEqual: Boolean = true
        final case class Diverged(axis: String, coldVal: String, warmVal: String) extends EquivResult:
            def isEqual: Boolean = false
    end EquivResult

    /** Compare cold and warm on checked axes. Returns EquivResult.Equal if all match. */
    private[kyo] def warmColdEquivalent(cold: Tasty.Classpath, warm: Tasty.Classpath): EquivResult =
        check("symbols.size", cold.symbols.size, warm.symbols.size)
            .orElse(check("fqnIndex.size", cold.indices.byFqn.size, warm.indices.byFqn.size))
            .orElse(check("packageIds.size", cold.indices.packageIds.size, warm.indices.packageIds.size))
            .orElse:
                val coldUnresolved = countUnresolvedRefs(cold)
                val warmUnresolved = countUnresolvedRefs(warm)
                check("unresolvedRefs.size", coldUnresolved, warmUnresolved)
            .getOrElse(EquivResult.Equal())

    /** Count symbols whose any reachable Type contains Named(SymbolId(-1)). */
    private[kyo] def countUnresolvedRefs(cp: Tasty.Classpath): Int =
        import kyo.Tasty.SymbolId.value as idValue
        var count = 0
        cp.symbols.foreach: sym =>
            var found = false
            sym match
                case m: Tasty.Symbol.Method =>
                    m.declaredType.foreach: dt =>
                        if !found then
                            dt.foreach: t =>
                                if !found then
                                    t match
                                        case Tasty.Type.Named(id) if idValue(id) == -1 =>
                                            found = true
                                        case _ => ()
                case c: Tasty.Symbol.ClassLike =>
                    c.parentTypes.foreach: pt =>
                        if !found then
                            pt.foreach: t =>
                                if !found then
                                    t match
                                        case Tasty.Type.Named(id) if idValue(id) == -1 =>
                                            found = true
                                        case _ => ()
                case _ => ()
            end match
            if found then count += 1
        count
    end countUnresolvedRefs

    private def check(axis: String, coldVal: Int, warmVal: Int): Maybe[EquivResult] =
        if coldVal != warmVal then Maybe(EquivResult.Diverged(axis, coldVal.toString, warmVal.toString))
        else Maybe.Absent

end SnapshotEquivalence
