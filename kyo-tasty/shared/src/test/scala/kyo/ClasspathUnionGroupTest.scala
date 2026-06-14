package kyo

import kyo.Tasty.SymbolId
import scala.compiletime.testing.typeCheckErrors

/** Tests for the ClassLike|Package union-narrowed declarations, members, findMember,
  * and findDeclaredMember methods on both Classpath instance and companion object Tasty.
  *
  * Fixture layout:
  *   0  -> Symbol.Package "pkg"     (memberIds = [1,2,3,4,5,6,7])
  *   1  -> Symbol.Class   "Base"    (declarationIds = [8])
  *   2  -> Symbol.Trait   "Mixin"   (declarationIds = [9], parentTypes = [Named(1)])
  *   3  -> Symbol.Object  "Util$"   (declarationIds = [10])
  *   4  -> Symbol.EnumCase "Color"  (declarationIds = [11])
  *   5  -> Symbol.Class   "Child"   (declarationIds = [12], parentTypes = [Named(1)])
  *   6  -> Symbol.Package "sub"     (memberIds = [])
  *   7  -> Symbol.Class   "Root"    (declarationIds = [])
  *   8  -> Symbol.Method  "run"     (owner = 1)
  *   9  -> Symbol.Val     "x"       (owner = 2)
  *   10 -> Symbol.Val     "util"    (owner = 3)
  *   11 -> Symbol.Val     "color"   (owner = 4)
  *   12 -> Symbol.Method  "run"     (owner = 5, overrides Base.run by name)
  */
class ClasspathUnionGroupTest extends kyo.test.Test[Any]:

    private val pkgId      = SymbolId(0)
    private val baseId     = SymbolId(1)
    private val mixinId    = SymbolId(2)
    private val utilId     = SymbolId(3)
    private val colorId    = SymbolId(4)
    private val childId    = SymbolId(5)
    private val subPkgId   = SymbolId(6)
    private val rootId     = SymbolId(7)
    private val runId      = SymbolId(8)
    private val xId        = SymbolId(9)
    private val utilValId  = SymbolId(10)
    private val colorValId = SymbolId(11)
    private val childRunId = SymbolId(12)

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            val pkg = Tasty.Symbol.Package(
                pkgId,
                Tasty.Name("pkg"),
                Tasty.Flags.empty,
                SymbolId(-1),
                memberIds = Chunk(baseId, mixinId, utilId, colorId, childId, subPkgId, rootId)
            )
            val runMethod = Tasty.Symbol.Method(
                runId,
                Tasty.Name("run"),
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
            val xVal = Tasty.Symbol.Val(
                xId,
                Tasty.Name("x"),
                Tasty.Flags.empty,
                mixinId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty
            )
            val utilVal = Tasty.Symbol.Val(
                utilValId,
                Tasty.Name("util"),
                Tasty.Flags.empty,
                utilId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty
            )
            val colorVal = Tasty.Symbol.Val(
                colorValId,
                Tasty.Name("color"),
                Tasty.Flags.empty,
                colorId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty
            )
            val childRunMethod = Tasty.Symbol.Method(
                childRunId,
                Tasty.Name("run"),
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
            val baseClass = Tasty.Symbol.Class(
                baseId,
                Tasty.Name("Base"),
                Tasty.Flags.empty,
                pkgId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk.empty,
                Chunk.empty,
                declarationIds = Chunk(runId),
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val mixinTrait = Tasty.Symbol.Trait(
                mixinId,
                Tasty.Name("Mixin"),
                Tasty.Flags.empty,
                pkgId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk(Tasty.Type.Named(baseId)),
                Chunk.empty,
                declarationIds = Chunk(xId),
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val utilObject = Tasty.Symbol.Object(
                utilId,
                Tasty.Name("Util$"),
                Tasty.Flags.empty,
                pkgId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk.empty,
                Chunk.empty,
                declarationIds = Chunk(utilValId),
                Chunk.empty,
                Chunk.empty
            )
            val colorEnumCase = Tasty.Symbol.EnumCase(
                colorId,
                Tasty.Name("Color"),
                Tasty.Flags.empty,
                pkgId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk.empty,
                Chunk.empty,
                declarationIds = Chunk(colorValId),
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val childClass = Tasty.Symbol.Class(
                childId,
                Tasty.Name("Child"),
                Tasty.Flags.empty,
                pkgId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk(Tasty.Type.Named(baseId)),
                Chunk.empty,
                declarationIds = Chunk(childRunId),
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val subPkg = Tasty.Symbol.Package(
                subPkgId,
                Tasty.Name("sub"),
                Tasty.Flags.empty,
                pkgId,
                memberIds = Chunk.empty
            )
            val rootClass = Tasty.Symbol.Class(
                rootId,
                Tasty.Name("Root"),
                Tasty.Flags.empty,
                pkgId,
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                parentTypes = Chunk.empty,
                Chunk.empty,
                declarationIds = Chunk.empty,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            Tasty.Classpath.make(
                symbols = Chunk(
                    pkg,
                    baseClass,
                    mixinTrait,
                    utilObject,
                    colorEnumCase,
                    childClass,
                    subPkg,
                    rootClass,
                    runMethod,
                    xVal,
                    utilVal,
                    colorVal,
                    childRunMethod
                ),
                rootSymbolId = pkgId,
                topLevelClassIds = Chunk(baseId, mixinId, utilId, colorId, childId, rootId),
                packageIds = Chunk(pkgId, subPkgId),
                fullNameIndex = Dict(
                    "pkg.Base"  -> baseId,
                    "pkg.Mixin" -> mixinId,
                    "pkg.Util$" -> utilId,
                    "pkg.Color" -> colorId,
                    "pkg.Child" -> childId,
                    "pkg.Root"  -> rootId
                ),
                packageIndex = Dict("pkg" -> pkgId, "pkg.sub" -> subPkgId),
                subclassIndex = Dict(baseId -> Chunk(childId)),
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }

    // declarations on Symbol.Class returns direct declaration ids resolved to symbols
    "Classpath.declarations returns declared members for Symbol.Class" in {
        buildFixture.map { classpath =>
            classpath.symbol(baseId) match
                case Maybe.Present(base: Tasty.Symbol.Class) =>
                    val result = classpath.declarations(base)
                    assert(result.size == 1, s"Base must have 1 declaration; got $result")
                    result(0) match
                        case m: Tasty.Symbol.Method =>
                            assert(m.name == Tasty.Name("run"), s"Expected method 'run'; got ${m.name}")
                            succeed
                        case other => fail(s"expected Symbol.Method for declaration, got $other")
                    end match
                case other => fail(s"expected Symbol.Class at baseId, got $other")
        }
    }

    // declarations on Symbol.Trait returns direct declaration ids
    "Classpath.declarations returns declared members for Symbol.Trait" in {
        buildFixture.map { classpath =>
            classpath.symbol(mixinId) match
                case Maybe.Present(mixin: Tasty.Symbol.Trait) =>
                    val result = classpath.declarations(mixin)
                    assert(result.size == 1, s"Mixin must have 1 declaration; got $result")
                    result(0) match
                        case v: Tasty.Symbol.Val =>
                            assert(v.name == Tasty.Name("x"), s"Expected val 'x'; got ${v.name}")
                            succeed
                        case other => fail(s"expected Symbol.Val for Mixin declaration, got $other")
                    end match
                case other => fail(s"expected Symbol.Trait at mixinId, got $other")
        }
    }

    // declarations on Symbol.Object returns direct declaration ids
    "Classpath.declarations returns declared members for Symbol.Object" in {
        buildFixture.map { classpath =>
            classpath.symbol(utilId) match
                case Maybe.Present(util: Tasty.Symbol.Object) =>
                    val result = classpath.declarations(util)
                    assert(result.size == 1, s"Util$$ must have 1 declaration; got $result")
                    result(0) match
                        case v: Tasty.Symbol.Val =>
                            assert(v.name == Tasty.Name("util"), s"Expected val 'util'; got ${v.name}")
                            succeed
                        case other => fail(s"expected Symbol.Val for Util$$ declaration, got $other")
                    end match
                case other => fail(s"expected Symbol.Object at utilId, got $other")
        }
    }

    // declarations on Symbol.Package returns memberIds resolved to symbols
    "Classpath.declarations returns member symbols for Symbol.Package" in {
        buildFixture.map { classpath =>
            classpath.symbol(pkgId) match
                case Maybe.Present(pkg: Tasty.Symbol.Package) =>
                    val result = classpath.declarations(pkg)
                    val names  = result.map(_.simpleName).toSet
                    assert(names.contains("Base"), s"pkg declarations must include 'Base'; got $names")
                    assert(names.contains("Mixin"), s"pkg declarations must include 'Mixin'; got $names")
                    assert(names.contains("sub"), s"pkg declarations must include 'sub'; got $names")
                    succeed
                case other => fail(s"expected Symbol.Package at pkgId, got $other")
        }
    }

    // declarations on Symbol.EnumCase returns direct declaration ids
    "Classpath.declarations returns declared members for Symbol.EnumCase" in {
        buildFixture.map { classpath =>
            classpath.symbol(colorId) match
                case Maybe.Present(color: Tasty.Symbol.EnumCase) =>
                    val result = classpath.declarations(color)
                    assert(result.size == 1, s"Color must have 1 declaration; got $result")
                    result(0) match
                        case v: Tasty.Symbol.Val =>
                            assert(v.name == Tasty.Name("color"), s"Expected val 'color'; got ${v.name}")
                            succeed
                        case other => fail(s"expected Symbol.Val for Color declaration, got $other")
                    end match
                case other => fail(s"expected Symbol.EnumCase at colorId, got $other")
        }
    }

    // compile-time: Symbol.Parameter is not a member of ClassLike | Package
    "Classpath.declarations rejects Symbol.Parameter at compile time" in {
        val errors = typeCheckErrors(
            "(??? : kyo.Tasty.Classpath).declarations(??? : kyo.Tasty.Symbol.Parameter)"
        )
        assert(errors.nonEmpty, "Symbol.Parameter must not be accepted by declarations; expected a compile error")
        succeed
    }

    // members with MemberScope.Declared returns the same set as declarations
    "Classpath.members with MemberScope.Declared equals Classpath.declarations" in {
        buildFixture.map { classpath =>
            classpath.symbol(baseId) match
                case Maybe.Present(base: Tasty.Symbol.Class) =>
                    val declared = classpath.members(base, Tasty.MemberScope.Declared)
                    val decls    = classpath.declarations(base)
                    assert(declared == decls, s"members(Declared) must equal declarations; got $declared vs $decls")
                    succeed
                case other => fail(s"expected Symbol.Class at baseId, got $other")
        }
    }

    // members with MemberScope.Inherited returns parent members not redeclared on symbol
    "Classpath.members with MemberScope.Inherited returns parent members not on symbol" in {
        buildFixture.map { classpath =>
            classpath.symbol(childId) match
                case Maybe.Present(child: Tasty.Symbol.Class) =>
                    val inherited = classpath.members(child, Tasty.MemberScope.Inherited)
                    // Child declares its own "run"; Base also declares "run". Inherited excludes "run".
                    val names = inherited.map(_.simpleName)
                    assert(!names.contains("run"), s"inherited must exclude redeclared 'run'; got $names")
                    succeed
                case other => fail(s"expected Symbol.Class at childId, got $other")
        }
    }

    // members with MemberScope.All returns union of Declared and Inherited
    "Classpath.members with MemberScope.All contains both declared and inherited members" in {
        buildFixture.map { classpath =>
            classpath.symbol(mixinId) match
                case Maybe.Present(mixin: Tasty.Symbol.Trait) =>
                    val all   = classpath.members(mixin, Tasty.MemberScope.All)
                    val names = all.map(_.simpleName).toSet
                    // Mixin declares 'x' directly; inherits 'run' from Base via parentTypes
                    assert(names.contains("x"), s"All scope must include declared 'x'; got $names")
                    assert(names.contains("run"), s"All scope must include inherited 'run'; got $names")
                    succeed
                case other => fail(s"expected Symbol.Trait at mixinId, got $other")
        }
    }

    // Package.Inherited scope returns empty (packages do not inherit)
    "Classpath.members with MemberScope.Inherited on Symbol.Package returns empty" in {
        buildFixture.map { classpath =>
            classpath.symbol(pkgId) match
                case Maybe.Present(pkg: Tasty.Symbol.Package) =>
                    val inherited = classpath.members(pkg, Tasty.MemberScope.Inherited)
                    assert(inherited.isEmpty, s"Package Inherited scope must be empty; got $inherited")
                    succeed
                case other => fail(s"expected Symbol.Package at pkgId, got $other")
        }
    }

    // compile-time: Symbol.Method is not a member of ClassLike | Package
    "Classpath.members rejects Symbol.Method at compile time" in {
        val errors = typeCheckErrors(
            "(??? : kyo.Tasty.Classpath).members(??? : kyo.Tasty.Symbol.Method)"
        )
        assert(errors.nonEmpty, "Symbol.Method must not be accepted by members; expected a compile error")
        succeed
    }

    // findMember returns Present when member exists under the given scope
    "Classpath.findMember returns Present when a declared member matches the name" in {
        buildFixture.map { classpath =>
            classpath.symbol(baseId) match
                case Maybe.Present(base: Tasty.Symbol.Class) =>
                    val result = classpath.findMember(base, "run")
                    result match
                        case Maybe.Present(m: Tasty.Symbol.Method) =>
                            assert(m.name == Tasty.Name("run"), s"findMember must return the 'run' method; got ${m.name}")
                            succeed
                        case Maybe.Absent => fail("findMember must return Present for existing member 'run'")
                        case other        => fail(s"expected Maybe.Present(Symbol.Method), got $other")
                    end match
                case other => fail(s"expected Symbol.Class at baseId, got $other")
        }
    }

    // findMember returns Absent when the name does not match any member
    "Classpath.findMember returns Absent when no member matches the name" in {
        buildFixture.map { classpath =>
            classpath.symbol(baseId) match
                case Maybe.Present(base: Tasty.Symbol.Class) =>
                    val result = classpath.findMember(base, "noSuchMember")
                    assert(result == Maybe.Absent, s"findMember must return Absent for missing name; got $result")
                    succeed
                case other => fail(s"expected Symbol.Class at baseId, got $other")
        }
    }

    // findMember with explicit MemberScope.Inherited finds parent member
    "Classpath.findMember with MemberScope.Inherited finds parent member not on child" in {
        buildFixture.map { classpath =>
            classpath.symbol(mixinId) match
                case Maybe.Present(mixin: Tasty.Symbol.Trait) =>
                    // Mixin inherits 'run' from Base; does not declare it directly
                    val result = classpath.findMember(mixin, "run", Tasty.MemberScope.Inherited)
                    result match
                        case Maybe.Present(m: Tasty.Symbol.Method) =>
                            assert(m.name == Tasty.Name("run"), s"findMember(Inherited) must find inherited 'run'; got ${m.name}")
                            succeed
                        case Maybe.Absent => fail("findMember(Inherited) must find inherited 'run' from Base")
                        case other        => fail(s"expected Maybe.Present(Symbol.Method), got $other")
                    end match
                case other => fail(s"expected Symbol.Trait at mixinId, got $other")
        }
    }

    // findDeclaredMember returns Present for a directly declared member
    "Classpath.findDeclaredMember returns Present for a directly declared member" in {
        buildFixture.map { classpath =>
            classpath.symbol(baseId) match
                case Maybe.Present(base: Tasty.Symbol.Class) =>
                    val result = classpath.findDeclaredMember(base, "run")
                    result match
                        case Maybe.Present(m: Tasty.Symbol.Method) =>
                            assert(m.name == Tasty.Name("run"), s"findDeclaredMember must return 'run'; got ${m.name}")
                            succeed
                        case Maybe.Absent => fail("findDeclaredMember must return Present for declared 'run'")
                        case other        => fail(s"expected Maybe.Present(Symbol.Method), got $other")
                    end match
                case other => fail(s"expected Symbol.Class at baseId, got $other")
        }
    }

    // findDeclaredMember does not find inherited members
    "Classpath.findDeclaredMember returns Absent for a member only present in parent" in {
        buildFixture.map { classpath =>
            classpath.symbol(mixinId) match
                case Maybe.Present(mixin: Tasty.Symbol.Trait) =>
                    // Mixin inherits 'run' from Base but does not declare it
                    val result = classpath.findDeclaredMember(mixin, "run")
                    assert(result == Maybe.Absent, s"findDeclaredMember must return Absent for inherited 'run'; got $result")
                    succeed
                case other => fail(s"expected Symbol.Trait at mixinId, got $other")
        }
    }

    // cross-platform pure-signature compile gate: instance methods accept ClassLike | Package without Frame
    "Classpath declarations/members/findMember/findDeclaredMember are pure and callable without Frame on all platforms" in {
        buildFixture.map { classpath =>
            classpath.symbol(baseId) match
                case Maybe.Present(base: Tasty.Symbol.Class) =>
                    // Each call below compiles without `using Frame`, confirming pure method shape.
                    val _: Chunk[Tasty.Symbol] = classpath.declarations(base)
                    val _: Chunk[Tasty.Symbol] = classpath.members(base)
                    val _: Chunk[Tasty.Symbol] = classpath.members(base, Tasty.MemberScope.Declared)
                    val _: Chunk[Tasty.Symbol] = classpath.members(base, Tasty.MemberScope.Inherited)
                    val _: Chunk[Tasty.Symbol] = classpath.members(base, Tasty.MemberScope.All)
                    val _: Maybe[Tasty.Symbol] = classpath.findMember(base, "run")
                    val _: Maybe[Tasty.Symbol] = classpath.findDeclaredMember(base, "run")
                    succeed
                case other => fail(s"expected Symbol.Class at baseId, got $other")
        }
    }

end ClasspathUnionGroupTest
