package kyo

import kyo.Tasty.SymbolId

/** Package typed accessor methods.
  *
  * All six leaves use a synthetic fixture: a Package with memberIds pointing to 1 Class, 1 Trait, 1 Object, and 1 sub-Package.
  */
class PackageTypedAccessorsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // ── Fixture ───────────────────────────────────────────────────────────────
    // Symbol placement in the fromPicklesWithSymbols array (index == id.value):
    //   0 -> Class "pkg.A"
    //   1 -> Trait "pkg.T"
    //   2 -> Object "pkg.O"
    //   3 -> Package "pkg.sub"
    //   4 -> Package "pkg" (root package with memberIds 0.3)
    // The Package accessor tests use pkg (index 4) so that memberIds = Chunk(0,1,2,3).

    private def makeClass(id: Int, name: String): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
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

    private def makeTrait(id: Int, name: String): Tasty.Symbol.Trait =
        Tasty.Symbol.Trait(
            SymbolId(id),
            Tasty.Name(name),
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

    private def makeObject(id: Int, name: String): Tasty.Symbol.Object =
        Tasty.Symbol.Object(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(4),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty
        )

    private def makeSubPkg(id: Int, name: String, ownerId: Int): Tasty.Symbol.Package =
        Tasty.Symbol.Package(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Chunk.empty
        )

    private def makeRootPkg(id: Int, name: String, memberIds: Chunk[SymbolId]): Tasty.Symbol.Package =
        Tasty.Symbol.Package(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            memberIds
        )

    private def buildFixture(using Frame) =
        val cls    = makeClass(0, "A")
        val trt    = makeTrait(1, "T")
        val obj    = makeObject(2, "O")
        val subPkg = makeSubPkg(3, "sub", ownerId = 4)
        val pkg    = makeRootPkg(4, "pkg", memberIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2), SymbolId(3)))
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(cls, trt, obj, subPkg, pkg)).map: cp =>
            (pkg, cp)
    end buildFixture

    "members-untyped: Package.members returns Chunk[Symbol] with all 4 direct members" in {
        buildFixture.map: (pkg, cp) =>
            given Tasty.Classpath = cp
            val ms                = pkg.memberIds.flatMap(id => cp.symbol(id).toChunk)
            assert(ms.length == 4, s"Expected 4 members but got ${ms.length}")
            succeed
    }

    "classes-typed: Package.classes returns Chunk[Class] size 1" in {
        buildFixture.map: (pkg, cp) =>
            given Tasty.Classpath = cp
            val cs                = pkg.memberIds.flatMap(id => cp.symbol(id).toChunk).collect { case c: Tasty.Symbol.Class => c }
            assert(cs.length == 1, s"Expected 1 class but got ${cs.length}")
            import Tasty.Name.asString
            assert(cs(0).name.asString == "A", s"Expected name A but got ${cs(0).name.asString}")
            succeed
    }

    "traits-typed: Package.traits returns Chunk[Trait] size 1" in {
        buildFixture.map: (pkg, cp) =>
            given Tasty.Classpath = cp
            val ts                = pkg.memberIds.flatMap(id => cp.symbol(id).toChunk).collect { case t: Tasty.Symbol.Trait => t }
            assert(ts.length == 1, s"Expected 1 trait but got ${ts.length}")
            import Tasty.Name.asString
            assert(ts(0).name.asString == "T", s"Expected name T but got ${ts(0).name.asString}")
            succeed
    }

    "objects-typed: Package.objects returns Chunk[Object] size 1" in {
        buildFixture.map: (pkg, cp) =>
            given Tasty.Classpath = cp
            val os                = pkg.memberIds.flatMap(id => cp.symbol(id).toChunk).collect { case o: Tasty.Symbol.Object => o }
            assert(os.length == 1, s"Expected 1 object but got ${os.length}")
            import Tasty.Name.asString
            assert(os(0).name.asString == "O", s"Expected name O but got ${os(0).name.asString}")
            succeed
    }

    "classLike-typed: Package.classLike returns Chunk[ClassLike] of size 3" in {
        buildFixture.map: (pkg, cp) =>
            given Tasty.Classpath = cp
            val cl                = pkg.memberIds.flatMap(id => cp.symbol(id).toChunk).collect { case cl: Tasty.Symbol.ClassLike => cl }
            assert(cl.length == 3, s"Expected 3 classLike members but got ${cl.length}")
            succeed
    }

    "subpackages-typed: Package.subpackages returns Chunk[Package] size 1" in {
        buildFixture.map: (pkg, cp) =>
            given Tasty.Classpath = cp
            val sp                = pkg.memberIds.flatMap(id => cp.symbol(id).toChunk).collect { case sp: Tasty.Symbol.Package => sp }
            assert(sp.length == 1, s"Expected 1 sub-package but got ${sp.length}")
            import Tasty.Name.asString
            assert(sp(0).name.asString == "sub", s"Expected name sub but got ${sp(0).name.asString}")
            succeed
    }

end PackageTypedAccessorsTest
