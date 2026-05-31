package kyo

import kyo.internal.tasty.symbol.SymbolId
import kyo.internal.tasty.type_.TypeArena

/** Plan-mandated tests for Phase 08 (leaves 175-178): steering target-use-case end-to-end validation.
  *
  * Fixture: a synthetic classpath with pkg.A (class with method foo and val x, parent B) and pkg.B (class).
  *
  * Pins: INV-001, INV-002, INV-003, INV-004, INV-005, INV-013.
  */
class SteeringTargetUseCaseTest extends Test:

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
                Chunk.empty,
                Maybe.Absent
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
                javaAnnotations = Chunk.empty,
                body = Maybe.Absent
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
                body = Maybe.Absent,
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
                annotations = Chunk.empty,
                body = Maybe.Absent
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
                fqnIndex = Map("pkg.A" -> SymbolId(1), "pkg.B" -> SymbolId(0)),
                packageIndex = Map("pkg" -> SymbolId(4)),
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )

    // ── Leaf 175: target-use-case-compiles-and-runs ───────────────────────────
    // Given: fixture with pkg.A having method foo, val x, parent B
    // When: run the steering snippet
    // Then: cls is Present(c); c.methods includes "foo"; c.vals includes "x"; c.parents includes "pkg.B"
    "Leaf 175: steering target use case runs end-to-end" in run {
        buildFixture.map: cp =>
            given Tasty.Classpath = cp
            val cls               = cp.findClass("pkg.A")
            assert(cls.isDefined, "findClass(\"pkg.A\") must return Present")
            cls match
                case Maybe.Present(c) =>
                    val methodNames = c.methods.map(_.name)
                    val valNames    = c.vals.map(_.name)
                    val parentFqns  = c.parents.map(_.fullNameString)
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

    // ── Leaf 176: fluent-Methods-typed ────────────────────────────────────────
    // Given: same fixture
    // When: bind val ms: Chunk[Tasty.Symbol.Method] = c.methods
    // Then: compiles; ms.size >= 1
    "Leaf 176: c.methods returns Chunk[Symbol.Method] with size >= 1" in run {
        buildFixture.map: cp =>
            given Tasty.Classpath = cp
            cp.findClass("pkg.A") match
                case Maybe.Present(c) =>
                    val ms: Chunk[Tasty.Symbol.Method] = c.methods
                    assert(ms.length >= 1, s"Expected at least 1 method, got ${ms.length}")
                    succeed
                case Maybe.Absent => fail("pkg.A must be Present")
            end match
    }

    // ── Leaf 177: prior-flag-predicates-end-to-end ────────────────────────────
    // Given: same fixture loaded
    // When: walk cp.symbols and invoke every predicate from the 40-set on each symbol
    // Then: no NoSuchMethodError; every invocation returns a Boolean
    "Leaf 177: all 40 flag predicates invoke without error on every symbol in the fixture" in run {
        buildFixture.map: cp =>
            cp.symbols.foreach: sym =>
                // 40 flag predicates must all be callable
                sym.isFinal; sym.isAbstract; sym.isSealed; sym.isCase; sym.isLazy
                sym.isOverride; sym.isPrivate; sym.isProtected; sym.isPublic; sym.isStatic
                sym.isMutable; sym.isErased; sym.isInfix; sym.isOpen; sym.isTransparent
                sym.isMacro; sym.isSynthetic; sym.isArtifact; sym.isCovariant; sym.isContravariant
                sym.isExtension; sym.isTracked; sym.isStable; sym.isParamAccessor; sym.isCaseAccessor
                sym.isFieldAccessor; sym.isExported; sym.isLocal; sym.isHasDefault; sym.isInvisible
                sym.isInto; sym.isInlineProxy; sym.isTailrec; sym.isScala2; sym.isJavaRecord
                sym.isEnum; sym.isModule; sym.isJava; sym.isInline; sym.isGiven
            succeed
    }

    // ── Leaf 178: cp-symbols-still-flat ──────────────────────────────────────
    // Given: same fixture
    // When: cp.symbols.size
    // Then: returns count of all loaded symbols; static type is Chunk[Tasty.Symbol]
    "Leaf 178: cp.symbols returns a Chunk[Tasty.Symbol] with the expected count" in run {
        buildFixture.map: cp =>
            val all: Chunk[Tasty.Symbol] = cp.symbols
            assert(all.length == 5, s"Expected 5 symbols (B, A, foo, x, pkg) but got ${all.length}")
            succeed
    }

end SteeringTargetUseCaseTest
