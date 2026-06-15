package kyo
import kyo.internal.tasty.symbol.SymbolKind

/** Tests for Tasty.Annotation public API surface.
  *
  * Annotation is a pure case class with arguments: Chunk[Tree] populated eagerly at open time.
  *
  * makeNamed is inherited from TastyTestSupport.
  */
class TastyAnnotationTest extends kyo.test.Test[Any] with TastyTestSupport:

    import AllowUnsafe.embrace.danger

    // Type.Named(id).show resolves classpath.symbol(id).map(_.name.asString).getOrElse("<unresolved>"); the symbol must
    // be registered in the classpath at index id.value.
    "Annotation case class: annotationType.show returns leaf name 'deprecated', arguments is empty" in {
        import kyo.Tasty.SymbolId
        val deprecatedSym = Tasty.Symbol.Class(
            SymbolId(0),
            Tasty.Name("deprecated"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(deprecatedSym)).map { classpath =>
            val deprecatedType = Tasty.Type.Named(SymbolId(0))
            val a              = Tasty.Annotation(deprecatedType, Chunk.empty, Tasty.Name("deprecated"))
            val showStr        = classpath.typeShow(a.annotationType)
            assert(
                showStr == "deprecated",
                s"Expected 'deprecated' but got '$showStr'"
            )
            assert(
                a.arguments.isEmpty,
                s"Expected empty arguments but got ${a.arguments}"
            )
        }
    }

    "Annotation case class unapply matches (annotationType, arguments)" in {
        Tasty.withPickles(Chunk.empty)(Tasty.classpath).map { classpath =>
            given Tasty.Classpath = classpath
            val deprecatedType    = makeNamed("scala.deprecated")
            val a                 = Tasty.Annotation(deprecatedType, Chunk.empty, Tasty.Name("scala.deprecated"))
            a match
                case Tasty.Annotation(tpe, arguments, fullName) =>
                    assert(
                        tpe.eq(a.annotationType),
                        "Expected unapply to return the same annotationType reference"
                    )
                    assert(
                        arguments.isEmpty,
                        s"Expected empty arguments from unapply but got $arguments"
                    )
                    assert(
                        fullName == Tasty.Name("scala.deprecated"),
                        s"Expected annotationFullName 'scala.deprecated' but got $fullName"
                    )
            end match
        }
    }

    // arguments is a plain Chunk[Tree] field; no effect row needed.
    "Annotation with a non-empty arguments chunk holds the trees as a plain field" in {
        import AllowUnsafe.embrace.danger
        val symbol = Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name("Foo"), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)
        val tree   = Tasty.Tree.Literal(Tasty.Constant.UnitConst)
        val a      = Tasty.Annotation(Tasty.Type.Named(symbol.id), Chunk(tree), Tasty.Name(""))
        assert(
            a.arguments.nonEmpty,
            s"Expected non-empty arguments but got ${a.arguments}"
        )
    }

end TastyAnnotationTest
