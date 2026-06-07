package kyo

import kyo.Tasty.SymbolId

/** steering target-use-case end-to-end validation.
  *
  * Fixture: a synthetic classpath with pkg.A (class with method foo and val x, parent B) and pkg.B (class).
  */
class SteeringTargetUseCaseTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Symbol index layout:
    //  0 -> Symbol.Class "B" in pkg (owner = 4)
    //  1 -> Symbol.Class "A" in pkg (owner = 4, parentTypes = [Named(0)], declarations = [2, 3])
    //  2 -> Symbol.Method "foo" in A (owner = 1)
    //  3 -> Symbol.Val "x" in A (owner = 1)
    //  4 -> Symbol.Package "pkg" (members = [0, 1])

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val clsB = Tasty.Symbol.Class(
                SymbolId(0),
                Tasty.Name("B"),
                Tasty.Flags.empty,
                SymbolId(4),
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
            val clsA = Tasty.Symbol.Class(
                SymbolId(1),
                Tasty.Name("A"),
                Tasty.Flags.empty,
                SymbolId(4),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk(Tasty.Type.Named(SymbolId(0))),
                typeParamIds = Chunk.empty,
                declarationIds = Chunk(SymbolId(2), SymbolId(3)),
                permittedSubclassIds = Maybe.Absent,
                annotations = Chunk.empty,
                javaAnnotations = Chunk.empty
            )
            val mFoo = Tasty.Symbol.Method(
                SymbolId(2),
                Tasty.Name("foo"),
                Tasty.Flags.empty,
                SymbolId(1),
                Maybe.Absent,
                Maybe.Absent,
                declaredType = Maybe.Absent,
                paramListIds = Chunk.empty,
                typeParamIds = Chunk.empty,
                annotations = Chunk.empty,
                javaMetadata = Maybe.Absent
            )
            val vX = Tasty.Symbol.Val(
                SymbolId(3),
                Tasty.Name("x"),
                Tasty.Flags.empty,
                SymbolId(1),
                Maybe.Absent,
                Maybe.Absent,
                declaredType = Maybe.Absent,
                annotations = Chunk.empty
            )
            val pkg = Tasty.Symbol.Package(
                SymbolId(4),
                Tasty.Name("pkg"),
                Tasty.Flags.empty,
                SymbolId(-1),
                memberIds = Chunk(SymbolId(0), SymbolId(1))
            )
            Tasty.Classpath.make(
                symbols = Chunk(clsB, clsA, mFoo, vX, pkg),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1)),
                packageIds = Chunk(SymbolId(4)),
                fqnIndex = Dict("pkg.A" -> SymbolId(1), "pkg.B" -> SymbolId(0)),
                packageIndex = Dict("pkg" -> SymbolId(4)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    "steering target use case runs end-to-end" in {
        buildFixture.flatMap: cp =>
            val cls = cp.findClass("pkg.A")
            assert(cls.isDefined, "findClass(\"pkg.A\") must return Present")
            cls match
                case Maybe.Present(c) =>
                    val methodNames =
                        c.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method]).map(_.name)
                    val valNames =
                        c.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Val]).map(_.name)
                    val resolvedParents: Chunk[Tasty.Symbol] =
                        c.parentTypes.flatMap:
                            case Tasty.Type.Named(pid) => cp.symbol(pid).toChunk
                            case _                     => Chunk.empty
                    Kyo.foreach(resolvedParents)(p =>
                        Sync.defer(cp.fullNameUnsafe(p).asString)
                    ).map: parentFqns =>
                        assert(
                            methodNames.exists(_.asString == "foo"),
                            s"Expected method 'foo' in ${methodNames.map(_.asString).mkString(", ")}"
                        )
                        assert(
                            valNames.exists(_.asString == "x"),
                            s"Expected val 'x' in ${valNames.map(_.asString).mkString(", ")}"
                        )
                        assert(
                            parentFqns.exists(_ == "pkg.B"),
                            s"Expected parent 'pkg.B' in ${parentFqns.mkString(", ")}"
                        )
                        succeed
                case Maybe.Absent => fail("cls must be Present")
            end match
    }

    "c.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(_.isInstanceOf[Tasty.Symbol.Method]) returns Chunk[Symbol.Method] with size == 1" in {
        buildFixture.map: cp =>
            cp.findClass("pkg.A") match
                case Maybe.Present(c) =>
                    val ms =
                        c.declarationIds.flatMap(id => cp.symbol(id).toChunk).filter(
                            _.isInstanceOf[Tasty.Symbol.Method]
                        ).asInstanceOf[Chunk[Tasty.Symbol.Method]]
                    // buildFixture declares pkg.A with declarationIds=[SymbolId(2)=foo, SymbolId(3)=x].
                    // Filtering for Method yields exactly [foo].
                    assert(ms.length == 1, s"Expected exactly 1 method (foo), got ${ms.length}")
                    succeed
                case Maybe.Absent => fail("pkg.A must be Present")
            end match
    }

    "all 40 flag predicates invoke without error on every symbol in the fixture" in {
        buildFixture.map: cp =>
            cp.symbols.foreach: sym =>
                // 40 flag predicates must all be callable
                sym.isFinal; sym.isAbstract; sym.isSealed; sym.isCase; sym.isLazy
                sym.isOverride; sym.isPrivate; sym.isProtected; sym.isPublic; sym.isStatic
                sym.isMutable; sym.isErased; sym.isInfix; sym.isOpen; sym.isTransparent
                sym.isMacro; sym.isSynthetic; sym.isArtifact; sym.isCovariant; sym.isContravariant
                sym.isExtension; sym.isTracked; sym.isStable; sym.isParamAccessor; sym.isCaseAccessor
                sym.isFieldAccessor; sym.isExported; sym.isLocal; sym.hasDefault; sym.isInvisible
                sym.isInto; sym.isInlineProxy; sym.isTailrec; sym.isScala2; sym.isJavaRecord
                sym.isEnum; sym.isModule; sym.isJava; sym.isInline; sym.isGiven
            succeed
    }

    "cp.symbols returns a Chunk[Tasty.Symbol] with the expected count" in {
        buildFixture.map: cp =>
            val all: Chunk[Tasty.Symbol] = cp.symbols
            assert(all.length == 5, s"Expected 5 symbols (B, A, foo, x, pkg) but got ${all.length}")
            succeed
    }

end SteeringTargetUseCaseTest
