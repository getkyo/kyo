package examples

import kyo.*
import kyo.Tasty.*

/** Code-generation use case: enumerate top-level classes in a classpath, project each into a typed descriptor, emit code.
  *
  * Updated for v3 Phase 3: all Symbol accessors (parents, declarations, declaredType) are now pure values. The for-comprehension no longer
  * threads effects through these calls.
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
        returnType: Tasty.Type,
        params: Chunk[Tasty.Type]
    )

    /** Discover all top-level classes in a classpath, project each into FacadeType, render. */
    def run(roots: Seq[String])(using Frame): Unit < (Sync & Async & Abort[TastyError] & Scope) =
        // Unsafe: Symbol accessors require AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        for
            cp <- Tasty.Classpath.initCached(roots, cacheDir = ".kyo-tasty-cache")
            given Classpath = cp
            facades         = cp.topLevelClasses.map(buildFacadeType)
            _ <- Kyo.foreach(facades)(f => Sync.defer(println(renderFacade(f))))
        yield ()
        end for
    end run

    private def buildFacadeType(sym: Tasty.Symbol)(using cp: Tasty.Classpath): FacadeType =
        // Unsafe: Symbol accessors require AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        val parents = sym match
            case cl: Tasty.Symbol.ClassLike => cl.parentTypes
            case _                          => Chunk.empty[Tasty.Type]
        val decls   = sym.declarations
        val methods = decls.collect { case m: Tasty.Symbol.Method => buildFacadeMethod(m) }
        FacadeType(sym.name, sym.flags, parents, methods)
    end buildFacadeType

    private def buildFacadeMethod(m: Tasty.Symbol.Method)(using cp: Tasty.Classpath): FacadeMethod =
        // Unsafe: Symbol.Method.declaredType requires AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        m.declaredType match
            case Maybe.Present(t: Tasty.Type.Function) =>
                FacadeMethod(m.name, m.flags, t.result, t.params)
            case Maybe.Present(t: Tasty.Type.ContextFunction) =>
                FacadeMethod(m.name, m.flags, t.result, t.params)
            case Maybe.Present(other) =>
                FacadeMethod(m.name, m.flags, other, Chunk.empty)
            case Maybe.Absent =>
                FacadeMethod(m.name, m.flags, Tasty.Type.Unknown, Chunk.empty)
        end match
    end buildFacadeMethod

    private def renderFacade(f: FacadeType)(using cp: Tasty.Classpath): String =
        // Unsafe: Name.asString requires AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        val methodLines = f.methods.map(m => s"  ${m.name.asString}: ${m.returnType.show}").mkString("\n")
        s"facade ${f.name.asString} {\n$methodLines\n}"
    end renderFacade

end CodegenExample
