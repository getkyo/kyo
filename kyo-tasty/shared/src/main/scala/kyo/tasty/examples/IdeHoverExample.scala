package kyo.tasty.examples

import kyo.*
import kyo.Tasty.*

/** IDE-style hover query: scan a classpath for the symbol at a given (file, line) position.
  *
  * This is the canonical "kyo-lsp shaped" use case. The walk is a plain for-comprehension over pure accessors: topLevelClasses (pure),
  * declarations (pure), position (pure), scaladoc (pure). Only the classpath open itself carries Sync & Async & Scope effects; all symbol
  * traversal is pure data access with no effect threading.
  *
  * v3 Phase 7: accessors are pure values. No Sync.defer, no flatMap ceremony around reads.
  */
object IdeHoverExample:

    /** A hover result for an LSP textDocument/hover request. */
    final case class HoverInfo(
        symbolName: String,
        kind: Tasty.SymbolKind,
        signature: String,
        doc: Maybe[String]
    )

    /** Find the symbol at (file, line) across all top-level classes in the classpath.
      *
      * Walks topLevelClasses -> declarations -> position in a plain for-comprehension. All accessor calls are pure; only openCached
      * produces effects (Sync & Async & Scope & Abort[TastyError]).
      */
    def hover(
        file: String,
        line: Int
    )(using Frame): Maybe[HoverInfo] < (Sync & Async & Abort[TastyError] & Scope) =
        for
            cp <- Tasty.Classpath.openCached(Seq("."), cacheDir = ".kyo-tasty-cache")
        yield
            // Pure walk: no flatMap, no Sync.defer, no effect threading on accessors.
            // topLevelClasses, declarations, position, scaladoc, declaredType are all pure values.
            val classes                  = cp.topLevelClasses
            var result: Maybe[HoverInfo] = Absent
            var i                        = 0
            while i < classes.size && result.isEmpty do
                val cls   = classes(i)
                val decls = cls.declarations
                var j     = 0
                while j < decls.size && result.isEmpty do
                    val sym = decls(j)
                    sym.position match
                        case Present(pos) if pos.sourceFile.contains(file) && pos.line == line =>
                            result = Present(HoverInfo(
                                symbolName = sym.name.asString,
                                kind = sym.kind,
                                signature = s"${sym.name.asString}: ${sym.declaredType.show}",
                                doc = sym.scaladoc
                            ))
                        case _ =>
                    end match
                    j += 1
                end while
                i += 1
            end while
            result

    /** Simplified hover that looks up by FQN and member name. Demonstrates the same pure accessor pattern. */
    def hoverByName(fqn: String, member: String)(using Frame): Maybe[String] < (Sync & Async & Abort[TastyError] & Scope) =
        for
            cp <- Tasty.Classpath.openCached(Seq("."), cacheDir = ".kyo-tasty-cache")
        yield cp.findClass(fqn) match
            case Absent => Absent
            case Present(cls) =>
                Maybe.fromOption(cls.declarations.find(_.name.asString == member)) match
                    case Absent     => Absent
                    case Present(s) => Present(s"${s.name.asString}: ${s.declaredType.show}")

    /** "Find all sealed classes in this classpath" composed query. Pure filter over topLevelClasses. */
    def findSealed(using Frame): Chunk[Tasty.Symbol] < (Sync & Async & Abort[TastyError] & Scope) =
        for
            cp <- Tasty.Classpath.openCached(Seq("."), cacheDir = ".kyo-tasty-cache")
        yield cp.topLevelClasses.filter(_.flags.contains(Tasty.Flag.Sealed))

end IdeHoverExample
