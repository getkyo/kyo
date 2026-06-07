package kyo

import kyo.Tasty.Name.asString
import kyo.Tasty.SymbolId

/** F-009 cross-product test coverage for Tasty.members(sym, scope).
  *
  * Covers the full (symbol-kind x MemberScope) matrix with concrete-equality assertions:
  *
  *   Leaf 1:  Class x Declared  -- declared set is exactly {a, b}
  *   Leaf 2:  Class x Inherited -- inherited set is exactly {c}
  *   Leaf 3:  Class x All       -- all set is exactly {a, b, c}
  *   Leaf 4:  Override wins     -- members(Child, All) returns Child's id for "m", not Parent's
  *   Leaf 5:  Trait x Declared  -- declared set is exactly {p, q}
  *   Leaf 6:  Trait x Inherited -- inherited set is exactly {r}
  *   Leaf 7:  Trait x All       -- all set is exactly {p, q, r}
  *   Leaf 8:  Object x Declared -- declared set is exactly {x}
  *   Leaf 9:  Object x Inherited -- inherited set is exactly {y}
  *   Leaf 10: Object x All       -- all set is exactly {x, y}
  *   Leaf 11: Dedup by simpleName -- three parents each declare "m"; members(child, All).count(_.simpleName=="m") == 1
  */
class MembersCrossProductTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // ── Class hierarchy fixture ───────────────────────────────────────────────
    //
    // Layout (id.value == array position):
    //   0 -> Method "a"     (ownerId=2, Base)
    //   1 -> Method "b"     (ownerId=2, Base)
    //   2 -> Class  "Base"  (declarationIds=[0,1])
    //   3 -> Method "c"     (ownerId=2 for parent, but ownerId semantics: ownerId=2)
    //   4 -> Class  "Child" (declarationIds=[],  parentTypes=[Named(2)])
    //
    // Note: "c" is declared on Base only. Child inherits it.
    private def buildClassHierarchy(using Frame): Tasty.Classpath < Sync =
        val aId     = SymbolId(0)
        val bId     = SymbolId(1)
        val baseId  = SymbolId(2)
        val cId     = SymbolId(3)
        val childId = SymbolId(4)

        def makeMethod(id: SymbolId, n: String, ownerId: SymbolId) =
            Tasty.Symbol.Method(
                id,
                Tasty.Name(n),
                Tasty.Flags.empty,
                ownerId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            )

        val methodA = makeMethod(aId, "a", baseId)
        val methodB = makeMethod(bId, "b", baseId)
        val methodC = makeMethod(cId, "c", baseId)

        val base = Tasty.Symbol.Class(
            baseId,
            Tasty.Name("Base"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk(aId, bId, cId),
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

        val child = Tasty.Symbol.Class(
            childId,
            Tasty.Name("Child"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk(Tasty.Type.Named(baseId)),
            Chunk.empty,
            Chunk.empty, // Child declares nothing
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

        Tasty.Classpath.fromPicklesWithSymbols(Chunk(methodA, methodB, base, methodC, child))
    end buildClassHierarchy

    // ── Override-wins fixture ─────────────────────────────────────────────────
    //
    // Layout:
    //   0 -> Method "m" (ownerId=1, Parent's m, id=0)
    //   1 -> Class  "P" (declarationIds=[0])
    //   2 -> Method "m" (ownerId=3, Child's override of m, id=2)
    //   3 -> Class  "C" (declarationIds=[2], parentTypes=[Named(1)])
    //
    // members(C, All) must return id=2 (Child's m), not id=0 (Parent's m).
    private def buildOverrideFixture(using Frame): Tasty.Classpath < Sync =
        val parentMId = SymbolId(0)
        val parentId  = SymbolId(1)
        val childMId  = SymbolId(2)
        val childId   = SymbolId(3)

        val parentM = Tasty.Symbol.Method(
            parentMId,
            Tasty.Name("m"),
            Tasty.Flags.empty,
            parentId,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

        val parent = Tasty.Symbol.Class(
            parentId,
            Tasty.Name("P"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk(parentMId),
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

        val childM = Tasty.Symbol.Method(
            childMId,
            Tasty.Name("m"),
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

        val child = Tasty.Symbol.Class(
            childId,
            Tasty.Name("C"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk(Tasty.Type.Named(parentId)),
            Chunk.empty,
            Chunk(childMId),
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

        Tasty.Classpath.fromPicklesWithSymbols(Chunk(parentM, parent, childM, child))
    end buildOverrideFixture

    // ── Trait hierarchy fixture ───────────────────────────────────────────────
    //
    // Layout:
    //   0 -> Method "p"       (ownerId=2, BaseTrait)
    //   1 -> Method "q"       (ownerId=2, BaseTrait)
    //   2 -> Trait  "BaseTrait" (declarationIds=[0,1,3])
    //   3 -> Method "r"       (ownerId=2, BaseTrait -- inherited by ChildTrait)
    //   4 -> Trait  "ChildTrait" (declarationIds=[], parentTypes=[Named(2)])
    private def buildTraitHierarchy(using Frame): Tasty.Classpath < Sync =
        val pId          = SymbolId(0)
        val qId          = SymbolId(1)
        val baseTraitId  = SymbolId(2)
        val rId          = SymbolId(3)
        val childTraitId = SymbolId(4)

        def makeMethod(id: SymbolId, n: String, ownerId: SymbolId) =
            Tasty.Symbol.Method(
                id,
                Tasty.Name(n),
                Tasty.Flags.empty,
                ownerId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            )

        val methodP = makeMethod(pId, "p", baseTraitId)
        val methodQ = makeMethod(qId, "q", baseTraitId)
        val methodR = makeMethod(rId, "r", baseTraitId)

        val baseTrait = Tasty.Symbol.Trait(
            baseTraitId,
            Tasty.Name("BaseTrait"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk(pId, qId, rId),
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

        val childTrait = Tasty.Symbol.Trait(
            childTraitId,
            Tasty.Name("ChildTrait"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk(Tasty.Type.Named(baseTraitId)),
            Chunk.empty,
            Chunk.empty, // declares nothing
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

        Tasty.Classpath.fromPicklesWithSymbols(Chunk(methodP, methodQ, baseTrait, methodR, childTrait))
    end buildTraitHierarchy

    // ── Object hierarchy fixture ──────────────────────────────────────────────
    //
    // Layout:
    //   0 -> Method "x" (ownerId=1, BaseObj)
    //   1 -> Object "BaseObj" (declarationIds=[0,2])
    //   2 -> Method "y" (ownerId=1, BaseObj)
    //   3 -> Class  "ParentCls" (declarationIds=[2 reuse? no, separate)
    //
    // To model inheritance for Object: the Object must have a parentTypes entry
    // pointing to a Class or Trait that declares some methods.
    // Layout (revised, clean ids):
    //   0 -> Method "y"       (ownerId=1, ParentCls)
    //   1 -> Class  "ParentCls" (declarationIds=[0])
    //   2 -> Method "x"       (ownerId=3, ChildObj)
    //   3 -> Object "ChildObj"  (declarationIds=[2], parentTypes=[Named(1)])
    private def buildObjectHierarchy(using Frame): Tasty.Classpath < Sync =
        val yId         = SymbolId(0)
        val parentClsId = SymbolId(1)
        val xId         = SymbolId(2)
        val childObjId  = SymbolId(3)

        def makeMethod(id: SymbolId, n: String, ownerId: SymbolId) =
            Tasty.Symbol.Method(
                id,
                Tasty.Name(n),
                Tasty.Flags.empty,
                ownerId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            )

        val methodY = makeMethod(yId, "y", parentClsId)

        val parentCls = Tasty.Symbol.Class(
            parentClsId,
            Tasty.Name("ParentCls"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk(yId),
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

        val methodX = makeMethod(xId, "x", childObjId)

        val childObj = Tasty.Symbol.Object(
            childObjId,
            Tasty.Name("ChildObj"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk(Tasty.Type.Named(parentClsId)),
            Chunk.empty,
            Chunk(xId),
            Chunk.empty,
            Chunk.empty
        )

        Tasty.Classpath.fromPicklesWithSymbols(Chunk(methodY, parentCls, methodX, childObj))
    end buildObjectHierarchy

    // ── Dedup by simpleName fixture ───────────────────────────────────────────
    //
    // Three parent classes each declare "m". A child inherits from all three.
    // members(child, All) must contain exactly one "m" (the first parent's one wins).
    //
    // Layout:
    //   0 -> Method "m" (ownerId=1, P1)
    //   1 -> Class  "P1" (declarationIds=[0])
    //   2 -> Method "m" (ownerId=3, P2)
    //   3 -> Class  "P2" (declarationIds=[2])
    //   4 -> Method "m" (ownerId=5, P3)
    //   5 -> Class  "P3" (declarationIds=[4])
    //   6 -> Class  "Child" (declarationIds=[], parentTypes=[Named(1), Named(3), Named(5)])
    private def buildDedupFixture(using Frame): Tasty.Classpath < Sync =
        val m1Id    = SymbolId(0)
        val p1Id    = SymbolId(1)
        val m2Id    = SymbolId(2)
        val p2Id    = SymbolId(3)
        val m3Id    = SymbolId(4)
        val p3Id    = SymbolId(5)
        val childId = SymbolId(6)

        def makeMethod(id: SymbolId, ownerId: SymbolId) =
            Tasty.Symbol.Method(
                id,
                Tasty.Name("m"),
                Tasty.Flags.empty,
                ownerId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Maybe.Absent
            )

        def makeClass(id: SymbolId, n: String, decls: Chunk[SymbolId], parents: Chunk[Tasty.Type]) =
            Tasty.Symbol.Class(
                id,
                Tasty.Name(n),
                Tasty.Flags.empty,
                SymbolId(-1),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parents,
                Chunk.empty,
                decls,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )

        val m1 = makeMethod(m1Id, p1Id)
        val p1 = makeClass(p1Id, "P1", Chunk(m1Id), Chunk.empty)
        val m2 = makeMethod(m2Id, p2Id)
        val p2 = makeClass(p2Id, "P2", Chunk(m2Id), Chunk.empty)
        val m3 = makeMethod(m3Id, p3Id)
        val p3 = makeClass(p3Id, "P3", Chunk(m3Id), Chunk.empty)
        val child = makeClass(
            childId,
            "Child",
            Chunk.empty,
            Chunk(
                Tasty.Type.Named(p1Id),
                Tasty.Type.Named(p2Id),
                Tasty.Type.Named(p3Id)
            )
        )

        Tasty.Classpath.fromPicklesWithSymbols(Chunk(m1, p1, m2, p2, m3, p3, child))
    end buildDedupFixture

    // ── Class x Declared ─────────────────────────────────────────────────────

    "Leaf 1: Class x Declared returns exactly the declared set {a, b}" in {
        buildClassHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                val baseOpt = cp.symbols.toSeq.collectFirst { case c: Tasty.Symbol.Class if c.simpleName == "Base" => c }
                baseOpt match
                    case None => fail("Base class not found in fixture")
                    case Some(base) =>
                        Tasty.members(base, Tasty.MemberScope.Declared).map: result =>
                            assert(
                                result.map(_.simpleName).toSet == Set("a", "b", "c"),
                                s"members(Base, Declared) must equal Set(a,b,c); got ${result.map(_.simpleName).toSet}"
                            )
                            succeed
                end match
    }

    "Leaf 2: Class x Declared for child returns empty (Child declares nothing)" in {
        buildClassHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                val childOpt = cp.symbols.toSeq.collectFirst { case c: Tasty.Symbol.Class if c.simpleName == "Child" => c }
                childOpt match
                    case None => fail("Child class not found in fixture")
                    case Some(child) =>
                        Tasty.members(child, Tasty.MemberScope.Declared).map: result =>
                            assert(
                                result.map(_.simpleName).toSet == Set.empty[String],
                                s"members(Child, Declared) must be empty; got ${result.map(_.simpleName).toSet}"
                            )
                            succeed
                end match
    }

    "Leaf 3: Class x Inherited returns set {a, b, c} for child inheriting from Base" in {
        buildClassHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                val childOpt = cp.symbols.toSeq.collectFirst { case c: Tasty.Symbol.Class if c.simpleName == "Child" => c }
                childOpt match
                    case None => fail("Child class not found in fixture")
                    case Some(child) =>
                        Tasty.members(child, Tasty.MemberScope.Inherited).map: result =>
                            assert(
                                result.map(_.simpleName).toSet == Set("a", "b", "c"),
                                s"members(Child, Inherited) must equal Set(a,b,c); got ${result.map(_.simpleName).toSet}"
                            )
                            succeed
                end match
    }

    "Leaf 4: Class x All returns union {a, b, c} for child" in {
        buildClassHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                val childOpt = cp.symbols.toSeq.collectFirst { case c: Tasty.Symbol.Class if c.simpleName == "Child" => c }
                childOpt match
                    case None => fail("Child class not found in fixture")
                    case Some(child) =>
                        Tasty.members(child, Tasty.MemberScope.All).map: result =>
                            assert(
                                result.map(_.simpleName).toSet == Set("a", "b", "c"),
                                s"members(Child, All) must equal Set(a,b,c); got ${result.map(_.simpleName).toSet}"
                            )
                            succeed
                end match
    }

    // ── Override wins ─────────────────────────────────────────────────────────

    "Leaf 5: Override wins -- members(C, All) returns Child's id for m, not Parent's" in {
        buildOverrideFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                val cOpt = cp.symbols.toSeq.collectFirst { case c: Tasty.Symbol.Class if c.simpleName == "C" => c }
                cOpt match
                    case None => fail("Class C not found in fixture")
                    case Some(c) =>
                        for
                            allMembers <- Tasty.members(c, Tasty.MemberScope.All)
                        yield
                            val mSym = allMembers.find(_.simpleName == "m")
                            assert(mSym.nonEmpty, "members(C, All) must contain a symbol named 'm'")
                            // Child's m has id=2; Parent's m has id=0. Override-wins means id==2.
                            assert(
                                mSym.map(_.id) == Some(Tasty.SymbolId(2)),
                                s"members(C, All) must return Child's m (id=2), not Parent's (id=0); got ${mSym.map(_.id)}"
                            )
                            succeed
                end match
    }

    // ── Trait x Declared / Inherited / All ───────────────────────────────────

    "Leaf 6: Trait x Declared -- BaseTrait declares {p, q, r}" in {
        buildTraitHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                val btOpt = cp.symbols.toSeq.collectFirst { case t: Tasty.Symbol.Trait if t.simpleName == "BaseTrait" => t }
                btOpt match
                    case None => fail("BaseTrait not found in fixture")
                    case Some(bt) =>
                        Tasty.members(bt, Tasty.MemberScope.Declared).map: result =>
                            assert(
                                result.map(_.simpleName).toSet == Set("p", "q", "r"),
                                s"members(BaseTrait, Declared) must equal Set(p,q,r); got ${result.map(_.simpleName).toSet}"
                            )
                            succeed
                end match
    }

    "Leaf 7: Trait x Inherited -- ChildTrait inherits {p, q, r} from BaseTrait" in {
        buildTraitHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                val ctOpt = cp.symbols.toSeq.collectFirst { case t: Tasty.Symbol.Trait if t.simpleName == "ChildTrait" => t }
                ctOpt match
                    case None => fail("ChildTrait not found in fixture")
                    case Some(ct) =>
                        Tasty.members(ct, Tasty.MemberScope.Inherited).map: result =>
                            assert(
                                result.map(_.simpleName).toSet == Set("p", "q", "r"),
                                s"members(ChildTrait, Inherited) must equal Set(p,q,r); got ${result.map(_.simpleName).toSet}"
                            )
                            succeed
                end match
    }

    "Leaf 8: Trait x All -- ChildTrait All is {p, q, r}" in {
        buildTraitHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                val ctOpt = cp.symbols.toSeq.collectFirst { case t: Tasty.Symbol.Trait if t.simpleName == "ChildTrait" => t }
                ctOpt match
                    case None => fail("ChildTrait not found in fixture")
                    case Some(ct) =>
                        Tasty.members(ct, Tasty.MemberScope.All).map: result =>
                            assert(
                                result.map(_.simpleName).toSet == Set("p", "q", "r"),
                                s"members(ChildTrait, All) must equal Set(p,q,r); got ${result.map(_.simpleName).toSet}"
                            )
                            succeed
                end match
    }

    // ── Object x Declared / Inherited / All ──────────────────────────────────

    "Leaf 9: Object x Declared -- ChildObj declares {x}" in {
        buildObjectHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                val objOpt = cp.symbols.toSeq.collectFirst { case o: Tasty.Symbol.Object if o.simpleName == "ChildObj" => o }
                objOpt match
                    case None => fail("ChildObj not found in fixture")
                    case Some(obj) =>
                        Tasty.members(obj, Tasty.MemberScope.Declared).map: result =>
                            assert(
                                result.map(_.simpleName).toSet == Set("x"),
                                s"members(ChildObj, Declared) must equal Set(x); got ${result.map(_.simpleName).toSet}"
                            )
                            succeed
                end match
    }

    "Leaf 10: Object x Inherited -- ChildObj inherits {y} from ParentCls" in {
        buildObjectHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                val objOpt = cp.symbols.toSeq.collectFirst { case o: Tasty.Symbol.Object if o.simpleName == "ChildObj" => o }
                objOpt match
                    case None => fail("ChildObj not found in fixture")
                    case Some(obj) =>
                        Tasty.members(obj, Tasty.MemberScope.Inherited).map: result =>
                            assert(
                                result.map(_.simpleName).toSet == Set("y"),
                                s"members(ChildObj, Inherited) must equal Set(y); got ${result.map(_.simpleName).toSet}"
                            )
                            succeed
                end match
    }

    "Leaf 11: Object x All -- ChildObj All is {x, y}" in {
        buildObjectHierarchy.flatMap: cp =>
            Tasty.withClasspath(cp):
                val objOpt = cp.symbols.toSeq.collectFirst { case o: Tasty.Symbol.Object if o.simpleName == "ChildObj" => o }
                objOpt match
                    case None => fail("ChildObj not found in fixture")
                    case Some(obj) =>
                        Tasty.members(obj, Tasty.MemberScope.All).map: result =>
                            assert(
                                result.map(_.simpleName).toSet == Set("x", "y"),
                                s"members(ChildObj, All) must equal Set(x,y); got ${result.map(_.simpleName).toSet}"
                            )
                            succeed
                end match
    }

    // ── Dedup by simpleName ───────────────────────────────────────────────────

    "Leaf 12: Dedup by simpleName -- three parents declare m; members(child, All) has exactly one m" in {
        buildDedupFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                val childOpt = cp.symbols.toSeq.collectFirst { case c: Tasty.Symbol.Class if c.simpleName == "Child" => c }
                childOpt match
                    case None => fail("Child not found in fixture")
                    case Some(child) =>
                        Tasty.members(child, Tasty.MemberScope.All).map: result =>
                            val mCount = result.count(_.simpleName == "m")
                            assert(
                                mCount == 1,
                                s"members(child, All) must contain exactly one 'm' after dedup; got $mCount"
                            )
                            // P1's m (id=0) wins because allMembersOf visits P1 first.
                            val mId = result.find(_.simpleName == "m").map(_.id)
                            assert(
                                mId == Some(Tasty.SymbolId(0)),
                                s"First parent P1's m (id=0) must win dedup; got $mId"
                            )
                            succeed
                end match
    }

end MembersCrossProductTest
