package kyo.fixtures

import kyo.fixtures.*

// Confirms that the fixture module compiles and all fixture classes are importable.
class FixtureCompilationTest extends kyo.test.Test[Any]:

    "fixture module compiles cross-platform" - {
        "all fixture classes are importable" in {
            // Instantiate one representative from each fixture category to confirm
            // the compilation produced valid bytecode/IR.
            val plain: PlainClass       = new PlainClass(1)
            val box: GenericBox[String] = new GenericBox("hi")
            val cc: SomeCaseClass       = SomeCaseClass("x", 2)
            val outer: Outer            = new Outer
            val inner: outer.Inner      = new outer.Inner(3.0)
            val color: Color            = Color.Red
            val meters: Meters          = Meters(1.5)
            val alias: StringList       = List("a")
            val inlined: Int            = inlineAdd(1, 2)
            val defaulted: Int          = methodWithDefaults()
            val identity: String        = identityMethod("test")
            assert(plain.x == 1)
            assert(box.content == "hi")
            assert(cc.name == "x")
            assert(inner.z == 3.0)
            assert(color == Color.Red)
            assert(meters.value == 1.5)
            assert(alias == List("a"))
            assert(inlined == 3)
            assert(defaulted == 3)
            assert(identity == "test")
        }
    }

end FixtureCompilationTest
