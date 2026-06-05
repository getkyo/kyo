package examples

import kyo.*
import kyo.Tasty.*

/** IDE-style hover query: scan a classpath for the symbol at a given (file, line) position.
  *
  * This is the canonical "kyo-lsp shaped" use case. The walk iterates over topLevelClasses (pure),
  * declarations via declarationIds (pure), position (pure), scaladoc (pure). Only the classpath open
  * itself carries Sync & Async effects; all symbol traversal is pure data access with no effect threading.
  *
  * Updated for carry A8: Tasty.withClasspath replaces Classpath.initCached. declarations uses
  * declarationIds; symbol kind uses sealed pattern matching; type rendering uses t.toString.
  */
object IdeHoverExample:

    /** A hover result for an LSP textDocument/hover request. */
    final case class HoverInfo(
        symbolName: String,
        kind: String,
        signature: String,
        doc: Maybe[String]
    )

    /** Find the symbol at (file, line) across all top-level classes in the classpath.
      *
      * Walks topLevelClasses -> declarationIds -> sourcePosition in a plain while loop. All accessor calls are
      * pure; only withClasspath produces effects (Sync & Async & Abort[TastyError]).
      */
    def hover(
        file: String,
        line: Int
    )(using Frame): Maybe[HoverInfo] < (Sync & Async & Abort[TastyError]) =
        // Unsafe: Symbol accessors require AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        Tasty.withClasspath(Seq("."), Maybe.Present(".kyo-tasty-cache")):
            Tasty.classpath.map: cp =>
                val classes                  = cp.topLevelClasses
                var result: Maybe[HoverInfo] = Absent
                var i                        = 0
                while i < classes.size && result.isEmpty do
                    val cls   = classes(i)
                    val decls = cls.declarationIds.flatMap(id => cp.symbol(id).toChunk)
                    var j     = 0
                    while j < decls.size && result.isEmpty do
                        val sym = decls(j)
                        sym.sourcePosition match
                            case Present(pos) if pos.sourceFile.contains(file) && pos.line == line =>
                                result = Present(HoverInfo(
                                    symbolName = sym.name.asString,
                                    kind = symbolKindStr(sym),
                                    signature = symbolSignature(sym, cp),
                                    doc = sym.scaladoc
                                ))
                            case _ =>
                        end match
                        j += 1
                    end while
                    i += 1
                end while
                result
    end hover

    /** Simplified hover that looks up by FQN and member name. Demonstrates the same pure accessor pattern. */
    def hoverByName(fqn: String, member: String)(using Frame): Maybe[String] < (Sync & Async & Abort[TastyError]) =
        // Unsafe: Symbol accessors require AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        Tasty.withClasspath(Seq("."), Maybe.Present(".kyo-tasty-cache")):
            Tasty.classpath.map: cp =>
                cp.findClass(fqn) match
                    case Absent => Absent
                    case Present(cls) =>
                        val decls = cls.declarationIds.flatMap(id => cp.symbol(id).toChunk)
                        decls.find(_.name.asString == member) match
                            case None    => Absent
                            case Some(s) => Present(s"${s.name.asString}: ${symbolSignature(s, cp)}")
    end hoverByName

    /** "Find all sealed classes in this classpath" composed query. Pure filter over topLevelClasses. */
    def findSealed(using Frame): Chunk[Tasty.Symbol] < (Sync & Async & Abort[TastyError]) =
        // Unsafe: Symbol accessors require AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        Tasty.withClasspath(Seq("."), Maybe.Present(".kyo-tasty-cache")):
            Tasty.classpath.map(_.topLevelClasses.filter(_.flags.contains(Tasty.Flag.Sealed)))
    end findSealed

    /** Return a short string identifying the symbol's kind via sealed pattern matching. */
    private def symbolKindStr(sym: Tasty.Symbol): String =
        sym match
            case _: Tasty.Symbol.Class     => "Class"
            case _: Tasty.Symbol.Trait     => "Trait"
            case _: Tasty.Symbol.Object    => "Object"
            case _: Tasty.Symbol.Method    => "Method"
            case _: Tasty.Symbol.Val       => "Val"
            case _: Tasty.Symbol.Var       => "Var"
            case _: Tasty.Symbol.Field     => "Field"
            case _: Tasty.Symbol.Package   => "Package"
            case _: Tasty.Symbol.TypeAlias => "TypeAlias"
            case _: Tasty.Symbol.Parameter => "Parameter"
            case _                         => "Unknown"
    end symbolKindStr

    /** Produce a human-readable type signature for any symbol. Uses t.toString for type rendering. */
    private def symbolSignature(sym: Tasty.Symbol, cp: Tasty.Classpath): String =
        import AllowUnsafe.embrace.danger
        sym match
            case m: Tasty.Symbol.Method =>
                m.declaredType match
                    case Maybe.Present(t) => t.toString
                    case Maybe.Absent     => m.name.asString
            case v: Tasty.Symbol.Val =>
                v.declaredType match
                    case Maybe.Present(t) => t.toString
                    case Maybe.Absent     => v.name.asString
            case other =>
                other.name.asString
        end match
    end symbolSignature

end IdeHoverExample
