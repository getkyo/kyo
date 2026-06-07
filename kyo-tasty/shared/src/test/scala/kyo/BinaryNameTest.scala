package kyo

import kyo.Tasty.SymbolId

/** Tests for BinaryName.compute using synthetic Classpath fixtures.
  *
  * Verifies JVM internal-name format: '/' for package segments, '$' for nested class segments,
  * and trailing '$' for top-level objects. Package symbols use dotted FQN names matching the
  * TASTy-decoded format.
  */
class BinaryNameTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    /** Build a classpath for testing top-level symbols in package "example".
      *
      * Index layout:
      *   0 -> Symbol.Class "Top" (ownerId = 2 = pkgExample)
      *   1 -> Symbol.Object "MyObj" (ownerId = 2 = pkgExample)
      *   2 -> Symbol.Package "example" (ownerId = -1)
      */
    private def buildTopLevelFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val topCls = Tasty.Symbol.Class(
                SymbolId(0),
                Tasty.Name("Top"),
                Tasty.Flags.empty,
                SymbolId(2),
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
            val myObj = Tasty.Symbol.Object(
                SymbolId(1),
                Tasty.Name("MyObj"),
                Tasty.Flags.empty,
                SymbolId(2),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty
            )
            val pkgExample = Tasty.Symbol.Package(
                SymbolId(2),
                Tasty.Name("example"),
                Tasty.Flags.empty,
                SymbolId(-1),
                memberIds = Chunk(SymbolId(0), SymbolId(1))
            )
            Tasty.Classpath.make(
                symbols = Chunk(topCls, myObj, pkgExample),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1)),
                packageIds = Chunk(SymbolId(2)),
                fqnIndex = Dict(
                    "example.Top"   -> SymbolId(0),
                    "example.MyObj" -> SymbolId(1)
                ),
                packageIndex = Dict("example" -> SymbolId(2)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    /** Build a classpath for testing nested symbols in package "example".
      *
      * Index layout:
      *   0 -> Symbol.Class "Outer" (ownerId = 4 = pkgExample; declarationIds = [1, 2])
      *   1 -> Symbol.Class "Inner" (ownerId = 0 = Outer)
      *   2 -> Symbol.Object "InnerObj" (ownerId = 0 = Outer)
      *   3 -> Symbol.Object "TopObj" (ownerId = 4 = pkgExample)
      *   4 -> Symbol.Package "example" (ownerId = -1)
      */
    private def buildNestedFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val outerCls = Tasty.Symbol.Class(
                SymbolId(0),
                Tasty.Name("Outer"),
                Tasty.Flags.empty,
                SymbolId(4),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                declarationIds = Chunk(SymbolId(1), SymbolId(2)),
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val innerCls = Tasty.Symbol.Class(
                SymbolId(1),
                Tasty.Name("Inner"),
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
            val innerObj = Tasty.Symbol.Object(
                SymbolId(2),
                Tasty.Name("InnerObj"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty,
                Chunk.empty
            )
            val topObj = Tasty.Symbol.Object(
                SymbolId(3),
                Tasty.Name("TopObj"),
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
            val pkgExample = Tasty.Symbol.Package(
                SymbolId(4),
                Tasty.Name("example"),
                Tasty.Flags.empty,
                SymbolId(-1),
                memberIds = Chunk(SymbolId(0), SymbolId(3))
            )
            Tasty.Classpath.make(
                symbols = Chunk(outerCls, innerCls, innerObj, topObj, pkgExample),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(3)),
                packageIds = Chunk(SymbolId(4)),
                fqnIndex = Dict(
                    "example.Outer"          -> SymbolId(0),
                    "example.Outer.Inner"    -> SymbolId(1),
                    "example.Outer.InnerObj" -> SymbolId(2),
                    "example.TopObj"         -> SymbolId(3)
                ),
                packageIndex = Dict("example" -> SymbolId(4)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    /** Build a classpath for testing deep nesting: a.b.c.Outer.Mid.Inner.
      *
      * Index layout:
      *   0 -> Symbol.Class "Outer" (ownerId = 3 = pkgABC; declarationIds = [1])
      *   1 -> Symbol.Class "Mid" (ownerId = 0 = Outer; declarationIds = [2])
      *   2 -> Symbol.Class "Inner" (ownerId = 1 = Mid)
      *   3 -> Symbol.Package "a.b.c" (ownerId = -1)
      *
      * Package name "a.b.c" mirrors the TASTy decoded format where full dotted package names are
      * stored in a single Name field.
      */
    private def buildDeepFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val outerCls = Tasty.Symbol.Class(
                SymbolId(0),
                Tasty.Name("Outer"),
                Tasty.Flags.empty,
                SymbolId(3),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                declarationIds = Chunk(SymbolId(1)),
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val midCls = Tasty.Symbol.Class(
                SymbolId(1),
                Tasty.Name("Mid"),
                Tasty.Flags.empty,
                SymbolId(0),
                Maybe.Absent,
                Maybe.Absent,
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty,
                declarationIds = Chunk(SymbolId(2)),
                Maybe.Absent,
                Chunk.empty,
                Chunk.empty
            )
            val innerCls = Tasty.Symbol.Class(
                SymbolId(2),
                Tasty.Name("Inner"),
                Tasty.Flags.empty,
                SymbolId(1),
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
            val pkgABC = Tasty.Symbol.Package(
                SymbolId(3),
                Tasty.Name("a.b.c"),
                Tasty.Flags.empty,
                SymbolId(-1),
                memberIds = Chunk(SymbolId(0))
            )
            Tasty.Classpath.make(
                symbols = Chunk(outerCls, midCls, innerCls, pkgABC),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0)),
                packageIds = Chunk(SymbolId(3)),
                fqnIndex = Dict(
                    "a.b.c.Outer"           -> SymbolId(0),
                    "a.b.c.Outer.Mid"       -> SymbolId(1),
                    "a.b.c.Outer.Mid.Inner" -> SymbolId(2)
                ),
                packageIndex = Dict("a.b.c" -> SymbolId(3)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    "top-level class: example.Top maps to example/Top" in {
        buildTopLevelFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                cp.findClass("example.Top") match
                    case Maybe.Present(sym) =>
                        Tasty.binaryName(sym).map: result =>
                            assert(result == "example/Top", s"expected 'example/Top' but got '$result'")
                            succeed
                    case Maybe.Absent => fail("example.Top must be present in top-level fixture")
    }

    "top-level object: example.MyObj maps to example/MyObj$" in {
        buildTopLevelFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                cp.findObject("example.MyObj") match
                    case Maybe.Present(sym) =>
                        Tasty.binaryName(sym).map: result =>
                            assert(result == "example/MyObj$", s"expected 'example/MyObj$$' but got '$result'")
                            succeed
                    case Maybe.Absent => fail("example.MyObj must be present in top-level fixture")
    }

    "nested class: example.Outer.Inner maps to example/Outer$Inner" in {
        buildNestedFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                cp.findClass("example.Outer.Inner") match
                    case Maybe.Present(sym) =>
                        Tasty.binaryName(sym).map: result =>
                            assert(result == "example/Outer$Inner", s"expected 'example/Outer$$Inner' but got '$result'")
                            succeed
                    case Maybe.Absent => fail("example.Outer.Inner must be present in nested fixture")
    }

    "nested object: example.Outer.InnerObj maps to example/Outer$InnerObj$" in {
        buildNestedFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                cp.findObject("example.Outer.InnerObj") match
                    case Maybe.Present(sym) =>
                        Tasty.binaryName(sym).map: result =>
                            assert(
                                result == "example/Outer$InnerObj$",
                                s"expected 'example/Outer$$InnerObj$$' but got '$result'"
                            )
                            succeed
                    case Maybe.Absent => fail("example.Outer.InnerObj must be present in nested fixture")
    }

    "deeper nesting: a.b.c.Outer.Mid.Inner maps to a/b/c/Outer$Mid$Inner" in {
        buildDeepFixture.flatMap: cp =>
            Tasty.withClasspath(cp):
                cp.findClass("a.b.c.Outer.Mid.Inner") match
                    case Maybe.Present(sym) =>
                        Tasty.binaryName(sym).map: result =>
                            assert(
                                result == "a/b/c/Outer$Mid$Inner",
                                s"expected 'a/b/c/Outer$$Mid$$Inner' but got '$result'"
                            )
                            succeed
                    case Maybe.Absent => fail("a.b.c.Outer.Mid.Inner must be present in deep fixture")
    }

end BinaryNameTest
