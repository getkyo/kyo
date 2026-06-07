package kyo

import kyo.Tasty.Name.asString
import kyo.Tasty.SymbolId

/** Tasty.members(sym, scope) and findMember: verifies package scope semantics and edge cases. */
class MembersTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Build a synthetic classpath with a class hierarchy for member scope testing.
    // Base declares 'a' and 'b'; Child extends Base and declares 'c'.
    private def buildHierarchy(using Frame): Tasty.Classpath < Sync =
        val baseId  = SymbolId(0)
        val aId     = SymbolId(1)
        val bId     = SymbolId(2)
        val childId = SymbolId(3)
        val cId     = SymbolId(4)

        val methodA = Tasty.Symbol.Method(
            aId,
            Tasty.Name("a"),
            Tasty.Flags.empty,
            baseId,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val methodB = Tasty.Symbol.Method(
            bId,
            Tasty.Name("b"),
            Tasty.Flags.empty,
            baseId,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val baseClass = Tasty.Symbol.Class(
            baseId,
            Tasty.Name("Base"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk(aId, bId),
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        val methodC = Tasty.Symbol.Method(
            cId,
            Tasty.Name("c"),
            Tasty.Flags.empty,
            childId,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val childClass = Tasty.Symbol.Class(
            childId,
            Tasty.Name("Child"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk(Tasty.Type.Named(baseId)), // extends Base
            Chunk.empty,
            Chunk(cId),
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(baseClass, methodA, methodB, childClass, methodC))
    end buildHierarchy

    // default scope is Declared
    "default scope is Declared (same as explicit MemberScope.Declared)" in {
        buildHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                // Find Child by scanning symbols (fqnIndex may not have it in synthetic cp)
                val childOpt = cp.symbols.toSeq.collectFirst:
                    case c: Tasty.Symbol.Class if c.simpleName == "Child" => c
                childOpt match
                    case None =>
                        // Child must be present: the fixture always places it at id=3 in the array.
                        // A missing symbol indicates a fixture construction bug, not a normal test path.
                        fail("Child class not found in synthetic classpath fixture (Leaf 18)")
                    case Some(child) =>
                        for
                            defaultResult  <- Tasty.members(child)
                            declaredResult <- Tasty.members(child, Tasty.MemberScope.Declared)
                        yield
                            assert(
                                defaultResult.map(_.simpleName).toSet == declaredResult.map(_.simpleName).toSet,
                                s"Default scope must equal Declared: default=$defaultResult declared=$declaredResult"
                            )
                            succeed
                end match
    }

    "findDeclaredMember is absent from Symbol (compile-time check)" in {
        val __tcErrors1 = compiletime.testing.typeCheckErrors(
            "(null: kyo.Tasty.Symbol).findDeclaredMember(\"a\")"
        ).length

        assert(__tcErrors1 > 0, "findDeclaredMember must be absent from Symbol (it was removed from the public API)")
    }

    // Build a synthetic classpath: package "examplepkg" with one child class "Child".
    // Index layout (id.value == array index):
    //   0 -> Class "Child" (ownerId = 1)
    //   1 -> Package "examplepkg" (memberIds = Chunk(SymbolId(0)))
    private def buildPkgFixture(using Frame): Tasty.Classpath < Sync =
        val childId = SymbolId(0)
        val pkgId   = SymbolId(1)
        val childClass = Tasty.Symbol.Class(
            childId,
            Tasty.Name("Child"),
            Tasty.Flags.empty,
            pkgId,
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
        val pkg = Tasty.Symbol.Package(
            pkgId,
            Tasty.Name("examplepkg"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Chunk(childId)
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(childClass, pkg))
    end buildPkgFixture

    "members(pkg, All) returns memberIds (same as members(pkg, Declared))" in {
        buildPkgFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                val pkgOpt = cp.symbols.toSeq.collectFirst { case p: Tasty.Symbol.Package => p }
                pkgOpt match
                    case None => fail("Package symbol not found in fixture")
                    case Some(pkg) =>
                        for
                            declaredNames <- Tasty.members(pkg, Tasty.MemberScope.Declared).map(_.map(_.simpleName))
                            allNames      <- Tasty.members(pkg, Tasty.MemberScope.All).map(_.map(_.simpleName))
                        yield
                            assert(
                                declaredNames.toSet == Set("Child"),
                                s"members(pkg, Declared) must equal Set(Child); got $declaredNames"
                            )
                            assert(
                                allNames.toSet == Set("Child"),
                                s"members(pkg, All) must equal Set(Child); got $allNames"
                            )
                            assert(
                                allNames.toSet == declaredNames.toSet,
                                s"members(pkg, All) must equal members(pkg, Declared); all=$allNames declared=$declaredNames"
                            )
                            succeed
                end match
    }

    "members(pkg, Inherited) returns Chunk.empty for packages" in {
        buildPkgFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                val pkgOpt = cp.symbols.toSeq.collectFirst { case p: Tasty.Symbol.Package => p }
                pkgOpt match
                    case None => fail("Package symbol not found in fixture")
                    case Some(pkg) =>
                        Tasty.members(pkg, Tasty.MemberScope.Inherited).map: inh =>
                            assert(
                                inh == Chunk.empty,
                                s"members(pkg, Inherited) must be Chunk.empty; got $inh"
                            )
                            succeed
                end match
    }

    "members(emptyPkg, All) returns Chunk.empty for package with no members" in {
        val emptyPkg = Tasty.Symbol.Package(
            SymbolId(0),
            Tasty.Name("empty.pkg"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Chunk.empty
        )
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(emptyPkg)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.members(emptyPkg, Tasty.MemberScope.All).map: result =>
                    assert(
                        result == Chunk.empty,
                        s"members(emptyPkg, All) must be Chunk.empty; got $result"
                    )
                    succeed
    }

end MembersTest
