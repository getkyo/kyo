package kyo
import kyo.internal.tasty.symbol.SymbolKind

/** Tests for Tasty.Annotation public API surface after Phase 08 (pure case class with eager arguments).
  *
  * Phase 08 (INV-006): Annotation is now a pure case class with arguments: Chunk[Tree] populated eagerly at open time. No argsPickle field,
  * no DecodeContext, no effectful arguments accessor.
  *
  * makeNamed is inherited from TastyTestSupport (Phase 21g deduplication).
  */
class TastyAnnotationTest extends Test with TastyTestSupport:

    import AllowUnsafe.embrace.danger

    // Test 6 (INV: T1, Annotation): synthetic factory produces correct field values.
    // Phase 09: Type.Named(id).show resolves cp.symbol(id).map(_.name.asString).getOrElse("<unresolved>"); the symbol must
    // be registered in the classpath at index id.value.
    "Annotation case class: annotationType.show returns leaf name 'deprecated', arguments is empty" in run {
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
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(deprecatedSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                val deprecatedType = Tasty.Type.Named(SymbolId(0))
                val a              = Tasty.Annotation(deprecatedType, Chunk.empty)
                Tasty.typeShow(a.annotationType).map: showStr =>
                    assert(
                        showStr == "deprecated",
                        s"Expected 'deprecated' but got '$showStr'"
                    )
                    assert(
                        a.arguments.isEmpty,
                        s"Expected empty arguments but got ${a.arguments}"
                    )
                    succeed
    }

    // Phase 08 Test 2: case-class unapply matches (annotationType, arguments).
    "Annotation case class unapply matches (annotationType, arguments)" in run {
        Tasty.withPickles(Chunk.empty)(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val deprecatedType    = makeNamed("scala.deprecated")
            val a                 = Tasty.Annotation(deprecatedType, Chunk.empty)
            a match
                case Tasty.Annotation(tpe, arguments) =>
                    assert(
                        tpe.eq(a.annotationType),
                        "Expected unapply to return the same annotationType reference"
                    )
                    assert(
                        arguments.isEmpty,
                        s"Expected empty arguments from unapply but got $arguments"
                    )
            end match
    }

    // Phase 08 Test 3: Annotation with non-empty arguments field holds the trees directly.
    // INV-006: arguments is a plain Chunk[Tree] field; no effect row needed.
    "Annotation with a non-empty arguments chunk holds the trees as a plain field" in run {
        import AllowUnsafe.embrace.danger
        val sym  = Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name("Foo"), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)
        val tree = Tasty.Tree.Literal(Tasty.Constant.UnitConst)
        val a    = Tasty.Annotation(Tasty.Type.Named(sym.id), Chunk(tree))
        assert(
            a.arguments.nonEmpty,
            s"Expected non-empty arguments but got ${a.arguments}"
        )
    }

end TastyAnnotationTest
