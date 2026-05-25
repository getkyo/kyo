package kyo.reflect.examples

import kyo.*
import kyo.Reflect.*

/** Code-generation use case: enumerate top-level classes in a classpath, project each into a typed descriptor, emit code.
  *
  * Phase 0 status: compiles against the skeleton. Each accessor call returns `Abort.fail(ReflectError.NotImplemented)` until the real
  * implementation lands per the phased plan in DESIGN.md.
  */
object CodegenExample:

    /** A simplified facade descriptor for a Scala class. Real codegen tooling like kyo-ts uses something close to this shape. */
    final case class FacadeType(
        name: Reflect.Name,
        flags: Reflect.Flags,
        parents: Chunk[Reflect.Type],
        methods: Chunk[FacadeMethod]
    )

    final case class FacadeMethod(
        name: Reflect.Name,
        flags: Reflect.Flags,
        returnType: Reflect.Type,
        params: Chunk[Reflect.Type]
    )

    /** Discover all top-level classes in a classpath, project each into FacadeType, render. */
    def run(roots: Seq[String])(using Frame): Unit < (Sync & Async & Abort[ReflectError] & Scope) =
        for
            cp <- Reflect.Classpath.openCached(roots, cacheDir = ".kyo-reflect-cache")
            given Classpath = cp
            classes <- cp.topLevelClasses
            facades <- Kyo.foreach(classes)(buildFacadeType)
            _       <- Kyo.foreach(facades)(f => Sync.defer(println(renderFacade(f))))
        yield ()

    private def buildFacadeType(sym: Reflect.Symbol)(using Frame): FacadeType < (Sync & Abort[ReflectError]) =
        for
            parents <- sym.parents
            decls   <- sym.declarations
            methods <- Kyo.foreach(decls.filter(_.kind == Reflect.SymbolKind.Method))(buildFacadeMethod)
        yield FacadeType(sym.name, sym.flags, parents, methods)

    private def buildFacadeMethod(sym: Reflect.Symbol)(using Frame): FacadeMethod < (Sync & Abort[ReflectError]) =
        sym.declaredType.map {
            case f: Reflect.Type.Function => FacadeMethod(sym.name, sym.flags, f.result, f.params)
            case other                    => FacadeMethod(sym.name, sym.flags, other, Chunk.empty)
        }

    private def renderFacade(f: FacadeType): String =
        val methodLines = f.methods.map(m => s"  ${m.name.asString}: ${m.returnType.show}").mkString("\n")
        s"facade ${f.name.asString} {\n$methodLines\n}"

end CodegenExample
