package kyo.debug

import java.io.ByteArrayOutputStream
import kyo.*

class DebugTest extends kyo.test.Test[Any]:

    def pureValueComputation = Debug(42)

    def effectsComputation =
        Env.run(10) {
            val x = Env.use[Int](_ + 1)
            val y = Env.use[Int](_ * 2)
            val z = Kyo.zip(x, y).map(_ + _).handle(Debug(_))
            z
        }

    def handleComputation =
        Env.use[Int](_ + 1).handle(Env.run(10), Debug(_))

    def nestedDebugComputation = Debug(Debug(42))

    def traceComputation =
        Env.run(5) {
            Var.run(0) {
                Debug.trace {
                    for
                        env <- Env.get[Int]
                        _   <- Var.update[Int](_ + env)
                        v   <- Var.get[Int]
                    yield v * 2
                }
            }
        }

    def memoEffectComputation =
        val memoizedFn = Memo[Int, Int, Any](x => x * 2)
        Memo.run {
            Debug.trace {
                for
                    a <- memoizedFn(5)
                    b <- memoizedFn(5)
                    c <- memoizedFn(6)
                yield (a, b, c)
            }
        }
    end memoEffectComputation

    def streamComputation =
        Debug.trace {
            Stream.init(1 to 5)
                .map(_ * 2)
                .filter(_ % 3 == 0)
                .run
        }

    def choiceComputation =
        Debug.trace {
            Choice.run {
                for
                    x <- Choice.eval(1, 2, 3)
                    y <- Choice.eval(4, 5, 6)
                yield x + y
            }
        }

    def largeValueComputation = Debug(List.fill(100)("a"))

    def valuesComputation = Debug.values(1, "test")

    def complexValuesComputation = Debug.values(List(1, 2, 3), Env.get[Int])

    def parameterValuesComputation(param1: Int, param2: String) = Debug.values(param1, param2)

    def internalTransformationComputation =
        def doIt(using Frame) = Debug.trace(Env.use[Int](_ + 1).map(_ + 2))
        doIt

    inline def testOutput(fragments: String*)(code: => Any)(using kyo.test.AssertScope): Unit =
        import kyo.Ansi.*
        val outContent = new ByteArrayOutputStream()
        scala.Console.withOut(outContent)(code)
        val out = outContent.toString.trim.stripAnsi
        fragments.foldLeft(out) { (out, fragment) =>
            val idx = out.indexOf(fragment)
            assert(idx >= 0, "Fragment not found: " + fragment + " Output: \n" + out)
            out.drop(idx + fragment.size)
        }
        ()
    end testOutput

    "apply" - {
        "pure value" in
            testOutput(
                "DebugTest.scala:8:41",
                "42"
            ) {
                pureValueComputation.eval
            }

        "with effects" in
            testOutput(
                "DebugTest.scala:14:61",
                "31"
            ) {
                effectsComputation.eval
            }

        "with pipe" in
            testOutput(
                "DebugTest.scala:19:57",
                "11"
            ) {
                handleComputation.eval
            }

        "nested Debug calls" in
            testOutput(
                "DebugTest.scala:21:49",
                "DebugTest.scala:21:50",
                "42"
            ) {
                nestedDebugComputation.eval
            }

        "value truncation" in
            testOutput(
                "DebugTest.scala:67:59",
                "... (truncated)"
            ) {
                largeValueComputation.eval
            }
    }

    "trace" - {
        "simple computation" in
            testOutput(
                "DebugTest.scala:28:44",
                "Env.get[Int]",
                "5",
                "Var.update[Int]",
                "5",
                "Var.get[Int]",
                "10"
            ) {
                traceComputation.eval
            }

        "with Memo effect" in
            testOutput(
                "DebugTest.scala:37:57",
                "10",
                "DebugTest.scala:37:57",
                "12",
                "memoizedFn(6)",
                "(10, 10, 12)"
            ) {
                memoEffectComputation.eval
            }

        "with Stream JVM".onlyJvm in
            testOutput(
                "DebugTest.scala:53:36",
                "()",
                "DebugTest.scala:55:10",
                "Seq(6)"
            ) {
                streamComputation.eval
            }

        "with Stream JS".onlyJs in
            testOutput(
                "DebugTest.scala:52:28",
                "undefined",
                "DebugTest.scala:55:10",
                "Seq(6)"
            ) {
                streamComputation.eval
            }

        "with Choice" in
            testOutput(
                "DebugTest.scala:63:28",
                "(4, 5, 6)",
                "DebugTest.scala:63:28",
                "6",
                "DebugTest.scala:64:14",
                "Seq(Seq(Seq(7)))"
            ) {
                choiceComputation.eval
            }

        "hides internal frame transformations" in
            testOutput(
                "DebugTest.scala:77:13",
                "8"
            ) {
                Env.run(5)(internalTransformationComputation).eval
            }
    }

    "values" - {
        "pure values" in
            testOutput(
                "DebugTest.scala:69:52",
                """Params("1" -> 1, "\"test\"" -> "test")"""
            ) {
                valuesComputation
            }

        "complex values" in
            testOutput(
                "DebugTest.scala:71:77",
                """"List(1, 2, 3)" -> List(1, 2, 3)""",
                """"Env.get[Int]" -> Kyo("""
            ) {
                complexValuesComputation
            }

        "parameter values" in
            testOutput(
                "DebugTest.scala:73:95",
                """Params("param1" -> 1, "param2" -> "test")"""
            ) {
                parameterValuesComputation(1, "test")
            }
    }

end DebugTest
