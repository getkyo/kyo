package kyo.demos

import kyo.*
import kyo.Tasty.*
import kyo.internal.TestClasspaths

/** Code-generation use case: enumerate top-level classes in a classpath, project each into a typed descriptor, render.
  *
  * Demonstrates the read path a tool like kyo-ts would use: `topLevelClasses` to enumerate, `declarationIds` + `symbol`
  * to resolve members, `Symbol.Method.declaredType` to recover signatures, and `typeShow` to render. The assertions pin
  * concrete facade output for the `kyo.fixtures.SomeCaseClass` fixture.
  */
class CodegenDemoTest extends kyo.test.Test[Any]:

    /** A simplified facade descriptor for a Scala class. Real codegen tooling uses something close to this shape. */
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

    private def buildFacadeType(symbol: Tasty.Symbol, classpath: Tasty.Classpath): FacadeType =
        val parents = symbol match
            case cl: Tasty.Symbol.ClassLike => cl.parentTypes
            case _                          => Chunk.empty[Tasty.Type]
        val decls = symbol match
            case cl: Tasty.Symbol.ClassLike => cl.declarationIds.flatMap(id => classpath.symbol(id).toChunk)
            case _                          => Chunk.empty[Tasty.Symbol]
        val methods = decls.collect { case m: Tasty.Symbol.Method => buildFacadeMethod(m) }
        FacadeType(symbol.name, symbol.flags, parents, methods)
    end buildFacadeType

    private def buildFacadeMethod(m: Tasty.Symbol.Method): FacadeMethod =
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

    private def renderFacade(f: FacadeType, classpath: Tasty.Classpath): String =
        val methodLines = f.methods.map: m =>
            m.returnType match
                case Maybe.Present(t) => s"  ${m.name.asString}: ${classpath.typeShow(t)}"
                case Maybe.Absent     => s"  ${m.name.asString}: <absent>"
        s"facade ${f.name.asString} {\n${methodLines.mkString("\n")}\n}"
    end renderFacade

    "enumerate top-level classes and project each into a typed facade" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val facades = classpath.topLevelClasses.map(symbol => buildFacadeType(symbol, classpath))
            assert(facades.nonEmpty, "topLevelClasses must yield at least one facade")
            val plain = facades.find(_.name.asString == "PlainClass")
            assert(plain.isDefined, s"Expected a facade for PlainClass among ${facades.map(_.name.asString).take(10).mkString(", ")}")
            succeed
        }
    }

    "render a facade for kyo.fixtures.SomeCaseClass with its synthesized methods" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findClassLike("kyo.fixtures.SomeCaseClass") match
                case Present(symbol) =>
                    val facade   = buildFacadeType(symbol, classpath)
                    val rendered = renderFacade(facade, classpath)
                    assert(
                        rendered.startsWith("facade SomeCaseClass {"),
                        s"Expected facade header 'facade SomeCaseClass {', got:\n$rendered"
                    )
                    // A case class synthesizes Method symbols: copy, productArity, and the value-equality methods.
                    val methodNames = facade.methods.map(_.name.asString)
                    assert(
                        methodNames.contains("copy") && methodNames.contains("productArity"),
                        s"Expected synthesized methods 'copy' and 'productArity', got: ${methodNames.mkString(", ")}"
                    )
                    succeed
                case Absent =>
                    fail("classpath.findClassLike(kyo.fixtures.SomeCaseClass) returned Absent")
        }
    }

end CodegenDemoTest
