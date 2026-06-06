package examples

import kyo.*
import kyo.Tasty.*

/** Code-generation use case: enumerate top-level classes in a classpath, project each into a typed descriptor, emit code.
  *
  * Updated for carry A8: Tasty.withClasspath replaces Classpath.initCached. Symbol.declarations has moved to
  * declarationIds on ClassLike; type rendering uses Tasty.typeShow (effectful) at the call site.
  */
object CodegenExample:

    /** A simplified facade descriptor for a Scala class. Real codegen tooling like kyo-ts uses something close to this shape. */
    final case class FacadeType(
        name: Tasty.Name,
        flags: Tasty.Flags,
        parents: Chunk[Tasty.Type],
        methods: Chunk[FacadeMethod]
    )

    final case class FacadeMethod(
        name: Tasty.Name,
        flags: Tasty.Flags,
        returnType: Maybe[Tasty.Type],
        params: Chunk[Tasty.Type]
    )

    /** Discover all top-level classes in a classpath, project each into FacadeType, render. */
    def run(roots: Seq[String])(using Frame): Unit < (Sync & Async & Abort[TastyError]) =
        // Unsafe: Symbol accessors require AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        Tasty.withClasspath(roots, Maybe.Present(".kyo-tasty-cache")):
            for
                cp <- Tasty.classpath
                facades = cp.topLevelClasses.map(sym => buildFacadeType(sym, cp))
                _ <- Kyo.foreach(facades)(f => renderFacade(f).map(println))
            yield ()
    end run

    private def buildFacadeType(sym: Tasty.Symbol, cp: Tasty.Classpath): FacadeType =
        // Unsafe: Symbol accessors require AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        val parents = sym match
            case cl: Tasty.Symbol.ClassLike => cl.parentTypes
            case _                          => Chunk.empty[Tasty.Type]
        val decls = sym match
            case cl: Tasty.Symbol.ClassLike => cl.declarationIds.flatMap(id => cp.symbol(id).toChunk)
            case _                          => Chunk.empty[Tasty.Symbol]
        val methods = decls.collect { case m: Tasty.Symbol.Method => buildFacadeMethod(m) }
        FacadeType(sym.name, sym.flags, parents, methods)
    end buildFacadeType

    private def buildFacadeMethod(m: Tasty.Symbol.Method): FacadeMethod =
        // Unsafe: Symbol.Method.declaredType requires AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        m.declaredType match
            case Maybe.Present(t: Tasty.Type.Function) =>
                FacadeMethod(m.name, m.flags, Maybe.Present(t.result), t.params)
            case Maybe.Present(t: Tasty.Type.ContextFunction) =>
                FacadeMethod(m.name, m.flags, Maybe.Present(t.result), t.params)
            case Maybe.Present(other) =>
                FacadeMethod(m.name, m.flags, Maybe.Present(other), Chunk.empty)
            case Maybe.Absent =>
                FacadeMethod(m.name, m.flags, Maybe.Absent, Chunk.empty)
        end match
    end buildFacadeMethod

    private def renderFacade(f: FacadeType)(using Frame): String < Sync =
        // Unsafe: Name.asString requires AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        Kyo.foreach(f.methods): m =>
            m.returnType match
                case Maybe.Present(t) => Tasty.typeShow(t).map(retStr => s"  ${m.name.asString}: $retStr")
                case Maybe.Absent     => s"  ${m.name.asString}: <absent>"
        .map: methodLines =>
            s"facade ${f.name.asString} {\n${methodLines.mkString("\n")}\n}"
    end renderFacade

end CodegenExample
