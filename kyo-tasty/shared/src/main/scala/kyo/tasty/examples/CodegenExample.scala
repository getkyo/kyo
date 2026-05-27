package kyo.tasty.examples

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
        for
            cp <- Tasty.Classpath.openCached(roots, cacheDir = ".kyo-tasty-cache")
            given Classpath = cp
            facades         = cp.topLevelClasses.map(buildFacadeType)
            _ <- Kyo.foreach(facades)(f => Sync.defer(println(renderFacade(f))))
        yield ()

    private def buildFacadeType(sym: Tasty.Symbol): FacadeType =
        val parents = sym.parents
        val decls   = sym.declarations
        val methods = decls.filter(_.kind == Tasty.SymbolKind.Method).map(buildFacadeMethod)
        FacadeType(sym.name, sym.flags, parents, methods)
    end buildFacadeType

    private def buildFacadeMethod(sym: Tasty.Symbol): FacadeMethod =
        sym.declaredType match
            case f: Tasty.Type.Function => FacadeMethod(sym.name, sym.flags, f.result, f.params)
            case other                  => FacadeMethod(sym.name, sym.flags, other, Chunk.empty)

    private def renderFacade(f: FacadeType): String =
        val methodLines = f.methods.map(m => s"  ${m.name.asString}: ${m.returnType.show}").mkString("\n")
        s"facade ${f.name.asString} {\n$methodLines\n}"

end CodegenExample
