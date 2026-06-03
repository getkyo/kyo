package kyo.internal.tasty.symbol

import kyo.Tasty
import kyo.Tasty.Name.asString
import kyo.Tasty.SymbolId.value

/** Computes the JVM binary name for a Symbol.
  *
  * Rules:
  *   - Package segments are separated by `'/'`.
  *   - Nested class and object names are separated by `'$'`.
  *   - Top-level classes are separated from their package by `'/'`.
  *   - Object (module) symbols keep their name as-is (the companion object of `Foo` is encoded as `Foo$` in the fqnIndex, but BinaryName
  *     reflects the actual JVM class name which uses `$` only for nested types, not for top-level companions).
  *
  * Called by `Symbol.binaryName(using cp)`.
  */
private[kyo] object BinaryName:

    def compute(sym: Tasty.Symbol, cp: Tasty.Classpath): String =
        buildSegments(sym, cp, Nil)
    end compute

    private def buildSegments(
        sym: Tasty.Symbol,
        cp: Tasty.Classpath,
        suffix: List[String]
    ): String =
        val nameStr = sym.name.asString
        if sym.ownerId == sym.id || sym.ownerId.value == -1 || sym.ownerId == cp.rootSymbolId then
            // Root: emit the accumulated segments joined appropriately.
            suffix.mkString
        else
            val owner = cp.symbol(sym.ownerId)
            if owner.ownerId == owner.id || owner.ownerId.value == -1 || owner.id == cp.rootSymbolId then
                // sym is a top-level declaration; its name connects to the owner (package) via '/'.
                if suffix.isEmpty then nameStr
                else nameStr + suffix.mkString
            else
                val ownerKind = owner.kind
                val sep =
                    if ownerKind == Tasty.SymbolKind.Package then "/"
                    else "$"
                buildSegments(owner, cp, sep :: nameStr :: suffix)
            end if
        end if
    end buildSegments

end BinaryName
