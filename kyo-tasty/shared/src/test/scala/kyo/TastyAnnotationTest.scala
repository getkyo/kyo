package kyo

/** Tests for Tasty.Annotation public API surface after Phase 08 (pure case class with eager args).
  *
  * Phase 08 (INV-006): Annotation is now a pure case class with args: Maybe[Tree] populated eagerly at open time. No argsPickle field, no
  * DecodeContext, no effectful args accessor.
  *
  * makeNamed is inherited from TastyTestSupport (Phase 21g deduplication).
  */
class TastyAnnotationTest extends Test with TastyTestSupport:

    import AllowUnsafe.embrace.danger

    // Test 6 (INV: T1, Annotation): synthetic factory produces correct field values.
    // Phase 09: Type.Named(id).show resolves cp.symbol(id).name.asString; the symbol must
    // be registered in the classpath at index id.value.
    "Annotation case class: annotationType.show returns leaf name 'deprecated', args is Absent" in run {
        import kyo.internal.tasty.symbol.SymbolId
        val deprecatedSym = Tasty.Symbol.fromDescriptor(
            id = SymbolId(0),
            kind = Tasty.SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name("deprecated"),
            ownerId = SymbolId(0),
            declaredType = Maybe.Absent,
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            bodyRecord = Maybe.Absent
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(deprecatedSym)).map: cp =>
            given Tasty.Classpath = cp
            val deprecatedType    = Tasty.Type.Named(SymbolId(0))
            val a                 = Tasty.Annotation(deprecatedType, Maybe.Absent)
            val showStr           = a.annotationType.show
            assert(
                showStr == "deprecated",
                s"Expected 'deprecated' but got '$showStr'"
            )
            assert(
                a.args == Maybe.Absent,
                s"Expected args == Maybe.Absent but got ${a.args}"
            )
    }

    // Phase 08 Test 2: case-class unapply matches (annotationType, args).
    "Annotation case class unapply matches (annotationType, args)" in run {
        Tasty.Classpath.fromPickles(Seq.empty).map: cp =>
            given Tasty.Classpath = cp
            val deprecatedType    = makeNamed("scala.deprecated")
            val a                 = Tasty.Annotation(deprecatedType, Maybe.Absent)
            a match
                case Tasty.Annotation(tpe, maybeArgs) =>
                    assert(
                        tpe eq a.annotationType,
                        "Expected unapply to return the same annotationType reference"
                    )
                    assert(
                        maybeArgs == Maybe.Absent,
                        s"Expected Absent args from unapply but got $maybeArgs"
                    )
            end match
    }

    // Phase 08 Test 3: Annotation with Present args field holds the tree directly.
    // INV-006: args is a plain Maybe[Tree] field -- no effect row needed.
    "Annotation with Present(tree) args holds the tree as a plain field" in run {
        import AllowUnsafe.embrace.danger
        val sym  = Tasty.Symbol.make(Tasty.SymbolKind.Class, Tasty.Flags.empty, Tasty.Name("Foo"))
        val tree = Tasty.Tree.Literal(Tasty.Constant.UnitConst)
        val a    = Tasty.Annotation(Tasty.Type.Named(sym.id), Maybe(tree))
        assert(
            a.args.nonEmpty,
            s"Expected Present args but got ${a.args}"
        )
    }

end TastyAnnotationTest
