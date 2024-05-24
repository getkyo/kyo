package kyoTest.internal

import kyo.internal.Trace
import kyoTest.KyoTest

class TraceTest extends KyoTest:

    "show" in {
        def test(i: Int)(using t: Trace) = t.show
        assert(test(42) ==
            """TraceTest.scala:10
            |test(42)""".stripMargin)
    }

    "no param" in {
        def test(using t: Trace) = t.snippet
        assert(test == "test")
    }

    "one pram" in {
        def test(i: Int)(using t: Trace) = t.snippet
        assert(test(42) == "test(42)")
    }

    "two params" in {
        def test(a: Int, b: String)(using t: Trace) = t.snippet
        assert(test(1, "hello") == "test(1, \"hello\")")
    }

    "multiple param lists" in {
        def test(a: Int)(b: String)(using t: Trace) = t.snippet
        assert(test(1)("hello") == "test(1)(\"hello\")")
    }

    "infix method" in pendingUntilFixed {
        object Test:
            infix def test(v: Int)(using t: Trace) = t.snippet

        assert((Test test 42) == "Test test 42")
        ()
    }

    "default parameter" in {
        def test(a: Int = 0)(using t: Trace) = t.snippet
        assert(test() == "test()")
        assert(test(1) == "test(1)")
    }

    "implicit parameter" in {
        def test(a: Int)(implicit b: String, t: Trace) = t.snippet
        implicit val str: String                       = "hello"
        assert(test(1) == "test(1)")
    }

    "type parameter" in {
        def test[T](a: T)(using t: Trace) = t.snippet
        assert(test(1) == "test(1)")
        assert(test("hello") == "test(\"hello\")")
    }

    "named arguments" in {
        def test(a: Int, b: String)(using t: Trace) = t.snippet
        assert(test(a = 1, b = "hello") == "test(a = 1, b = \"hello\")")
    }

    "varargs" in {
        def test(a: Int*)(using t: Trace) = t.snippet
        assert(test(1, 2, 3) == "test(1, 2, 3)")
    }

    "method in a class" in {
        class TestClass:
            def test(a: Int)(using t: Trace) = t.snippet

        val obj = TestClass()
        assert(obj.test(1) == "test(1)")
    }

    "method in an object" in {
        object TestObject:
            def test(a: Int)(using t: Trace) = t.snippet

        assert(TestObject.test(1) == "test(1)")
    }

    "method with backticks" in {
        def `test-method`(a: Int)(using t: Trace) = t.snippet
        assert(`test-method`(1) == "`test-method`(1)")
    }

    "parameter with backticks" in {
        def test(`a-param`: Int)(using t: Trace) = t.snippet
        assert(test(1) == "test(1)")
    }

    "type parameter with backticks" in {
        def test[`T-Type`](a: `T-Type`)(using t: Trace) = t.snippet
        assert(test(1) == "test(1)")
    }

    "mixed backticks and regular identifiers" in {
        def `test-method`(`a-param`: Int, bParam: String)(using t: Trace) = t.snippet
        assert(`test-method`(1, "hello") == "`test-method`(1, \"hello\")")
    }

    "unicode identifiers" in {
        def αβγ(δεζ: Int)(using t: Trace) = t.snippet
        assert(αβγ(1) == "αβγ(1)")
    }

    "operator method" in {
        def ++(a: Int)(using t: Trace) = t.snippet
        assert(++(1) == "++(1)")
    }

    "method with keyword identifiers" in {
        def `val`(`def`: Int, `type`: String)(using t: Trace) = t.snippet
        assert(`val`(1, "hello") == "`val`(1, \"hello\")")
    }

    "nested method calls" in {
        def outer(a: String)(using t: Trace) = t.snippet
        def inner(b: String)(using t: Trace) = t.snippet
        assert(outer(inner("hello")) == "outer(inner(\"hello\"))")
    }

    "method with complex expressions" in {
        def test(a: Int, b: String)(using t: Trace) = t.snippet
        assert(test(1 + 2 * 3, s"hello ${1 + 1}") == "test(1 + 2 * 3, s\"hello ${1 + 1}\")")
    }

    "method with default and non-default parameters" in {
        def test(a: Int, b: String = "world")(using t: Trace) = t.snippet
        assert(test(1) == "test(1)")
        assert(test(1, "hello") == "test(1, \"hello\")")
    }

    "method with repeated parameters" in {
        def test(a: Int, b: String*)(using t: Trace) = t.snippet
        assert(test(1) == "test(1)")
        assert(test(1, "hello") == "test(1, \"hello\")")
        assert(test(1, "hello", "world") == "test(1, \"hello\", \"world\")")
    }

    "method with by-name parameters" in {
        def test(a: => Int)(using t: Trace) = t.snippet
        assert(test(1) == "test(1)")
        assert(test(1 + 2) == "test(1 + 2)")
    }

    "method inside a trait" in {
        trait TestTrait:
            def test(a: Int)(using t: Trace) = t.snippet
        class TestClass extends TestTrait
        val obj = TestClass()
        assert(obj.test(1) == "test(1)")
    }

    "method with a type parameter bounded by a complex type" in {
        def test[T <: Either[String, Int]](a: T)(using t: Trace) = t.snippet
        assert(test(Left("hello")) == "test(Left(\"hello\"))")
        assert(test(Right(1)) == "test(Right(1))")
    }

    "explicit type parameter" in {
        def test[T](a: T)(using t: Trace) = t.snippet
        assert(test[Int](1) == "test[Int](1)")
        assert(test[String]("hello") == "test[String](\"hello\")")
    }

    "explicit type parameter with a value type" in {
        def test[T](a: T)(using t: Trace) = t.snippet
        assert(test[Int](1) == "test[Int](1)")
        assert(test[Boolean](true) == "test[Boolean](true)")
        assert(test[Char]('a') == "test[Char]('a')")
    }

    "explicit type parameter with a reference type" in {
        def test[T](a: T)(using t: Trace) = t.snippet
        assert(test[String]("hello") == "test[String](\"hello\")")
        assert(test[List[Int]](List(1, 2, 3)) == "test[List[Int]](List(1, 2, 3))")
    }

    "explicit type parameter with a generic type" in {
        def test[T](a: T)(using t: Trace) = t.snippet
        assert(test[Option[Int]](Some(1)) == "test[Option[Int]](Some(1))")
        assert(test[Either[String, Int]](Left("hello")) == "test[Either[String, Int]](Left(\"hello\"))")
    }

    "explicit type parameter with a tuple type" in {
        def test[T](a: T)(using t: Trace) = t.snippet
        assert(test[(Int, String)]((1, "hello")) == "test[(Int, String)]((1, \"hello\"))")
        assert(test[(Boolean, Char, Double)]((true, 'a', 3.14)) == "test[(Boolean, Char, Double)]((true, 'a', 3.14))")
    }

    "explicit type parameter with a function type" in {
        def test[T](a: T)(using t: Trace) = t.snippet
        assert(test[Int => String](i => s"$i") == "test[Int => String](i => s\"$i\")")
        assert(test[(String, Int) => Boolean]((s, i) => s.length == i) == "test[(String, Int) => Boolean]((s, i) => s.length == i)")
    }

    "explicit type parameter with a nested type" in {
        def test[T](a: T)(using t: Trace) = t.snippet
        type NestedType = Option[Either[String, List[Int]]]
        assert(test[NestedType](Some(Right(List(1, 2, 3)))) == "test[NestedType](Some(Right(List(1, 2, 3))))")
    }

    "extension method on a value type" in {
        extension (i: Int)
            def square(using t: Trace) =
                t.snippet
        assert(3.square == "square")
    }

    "extension method on a reference type" in {
        extension (s: String)
            def duplicate(using t: Trace): String =
                t.snippet
        assert("hello".duplicate == "duplicate")
    }

    "extension method with a parameter" in {
        extension (i: Int)
            def add(j: Int)(using t: Trace) =
                t.snippet
        assert(1.add(2) == "add(2)")
    }

    "extension method with multiple parameter lists" in {
        extension (i: Int)
            def multiply(j: Int)(k: Int)(using t: Trace) =
                t.snippet
        assert(2.multiply(3)(4) == "multiply(3)(4)")
    }

    "extension method with a generic type" in {
        extension [T](o: Option[T])
            def getOrDefault(default: T)(using t: Trace) =
                t.snippet
        assert(Some(1).getOrDefault(0) == "getOrDefault(0)")
        assert(None.getOrDefault(0) == "getOrDefault(0)")
    }

    "method with a by-name parameter with {}" in {
        def test(a: => Int)(using t: Trace) =
            t.snippet
        assert(test { 1 } == "test { 1 }")
        assert(test { 1 + 2 } == "test { 1 + 2 }")
    }

    "method with multiple by-name parameters with {}" in {
        def test(a: => Int)(b: => String)(using t: Trace) =
            t.snippet
        assert(test(1) { "hello" } == "test(1) { \"hello\" }")
        assert(test(1 + 2) { s"hello" } == "test(1 + 2) { s\"hello\" }")
    }

    "extension method with a by-name parameter with {}" in {
        extension (i: Int)
            def add(j: => Int)(using t: Trace) =
                t.snippet
        assert(1.add { 2 } == "add { 2 }")
    }

    "explicit type parameter and by-name parameter with {}" in {
        def test[T](a: => T)(using t: Trace) =
            t.snippet
        assert(test[Int] { 1 } == "test[Int] { 1 }")
        assert(test[String] { s"hello" } == "test[String] { s\"hello\" }")
    }

    "identation" in {
        def test(f: Int)(using t: Trace) = t.snippet
        assert(
            test {
                1 + 2
            } ==
                """|test {
                |    1 + 2
                |}""".stripMargin
        )
    }

end TraceTest
