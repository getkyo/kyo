package kyo.internal.tasty.symbol

import kyo.Maybe
import kyo.Tasty
import kyo.Tasty.Name.asString
import kyo.Tasty.SymbolId.value

/** Computes the JVM internal binary name for a Symbol.
  *
  * Rules:
  *   - Package segments are joined with `'/'`.
  *   - Nested class and object names are joined with `'$'`.
  *   - Top-level classes are separated from their package by `'/'`.
  *   - Top-level objects get a trailing `'$'` suffix (e.g. `example.MyObj` -> `"example/MyObj$"`).
  *
  * For example, `example.Outer.Inner` maps to `"example/Outer$Inner"` and a companion object
  * `example.MyObj` maps to `"example/MyObj$"`.
  *
  * Called by `Symbol.binaryName(using classpath)`.
  */
private[kyo] object BinaryName:

    /** JVM internal binary name. Package segments are joined with '/'. Nested class segments are
      * joined with '$'. Top-level objects get a '$' suffix.
      */
    def compute(symbol: Tasty.Symbol, classpath: Tasty.Classpath): String =
        // Append the '$' suffix for Object before walking owners, so the binary name
        // conforms to the JVM internal-name convention: "example/MyObj$" for "object MyObj".
        val leafSuffix: List[String] =
            if symbol.kind == SymbolKind.Object then List("$") else Nil
        buildSegments(symbol, classpath, leafSuffix)
    end compute

    private def buildSegments(
        symbol: Tasty.Symbol,
        classpath: Tasty.Classpath,
        suffix: List[String]
    ): String =
        val nameStr = symbol.name.asString
        // Termination: symbol is the root (self-referential ownerId, unowned sentinel -1, or IS the
        // root symbol). Root contributes no name; return accumulated segments as-is.
        if symbol.ownerId == symbol.id || symbol.ownerId.value == -1 || symbol.id == classpath.rootSymbolId then
            suffix.mkString
        else
            classpath.symbol(symbol.ownerId) match
                case Maybe.Absent =>
                    // Owner not resolvable (e.g. synthetic classpath with ownerId=-1 resolved as
                    // absent); emit nameStr directly as the leftmost prefix.
                    nameStr + suffix.mkString
                case Maybe.Present(owner) =>
                    if owner.kind == SymbolKind.Package then
                        // Package symbols store the full dotted package name (e.g.
                        // "kyo.fixtures"). Convert dots to slashes for the JVM binary name and stop
                        // recursion here; the package's own owner chain would only re-emit the same
                        // segments (computeFullName stops at the first Package for the same reason).
                        val pkgPrefix = owner.name.asString.replace('.', '/')
                        if pkgPrefix.isEmpty then
                            // Empty/root package: no package prefix.
                            nameStr + suffix.mkString
                        else
                            pkgPrefix + "/" + nameStr + suffix.mkString
                        end if
                    else
                        // Non-package owner (Class, Trait, Object, etc.): recurse with '$' separator.
                        buildSegments(owner, classpath, "$" :: nameStr :: suffix)
        end if
    end buildSegments

end BinaryName
