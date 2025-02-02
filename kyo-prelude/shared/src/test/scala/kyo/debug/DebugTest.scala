package kyo.debug

import java.io.ByteArrayOutputStream
import kyo.*
import kyo.Tagged.jsOnly
import kyo.Tagged.jvmOnly

class DebugTest extends Test:

    def pureValueComputation = Debug(42)

    def effectsComputation =
        Env.run(10) {
            val x = Env.use[Int](_ + 1)
            val y = Env.use[Int](_ * 2)
            val z = Kyo.zip(x, y).map(_ + _).pipe(Debug(_))
            z
        }

    def pipeComputation =
        Env.use[Int](_ + 1).pipe(Env.run(10)).pipe(Debug(_))

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
                    x <- Choice.get(Seq(1, 2, 3))
                    y <- Choice.get(Seq(4, 5, 6))
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

    inline def testOutput(fragments: String*)(code: => Any): Assertion =
        import kyo.Ansi.*
        val outContent = new ByteArrayOutputStream()
        Console.withOut(outContent)(code)
        val out = outContent.toString.trim.stripAnsi
        fragments.foldLeft(out) { (out, fragment) =>
            val idx = out.indexOf(fragment)
            assert(idx >= 0, "Fragment not found: " + fragment + " Output: \n" + out)
            out.drop(idx + fragment.size)
        }
        succeed
    end testOutput

    "apply" - {
        "pure value" in
            testOutput(
                "DebugTest.scala:10:41",
                "42"
            ) {
                pureValueComputation.eval
            }

        "with effects" in
            testOutput(
                "DebugTest.scala:16:59",
                "31"
            ) {
                effectsComputation.eval
            }

        "with pipe" in
            testOutput(
                "DebugTest.scala:21:60",
                "11"
            ) {
                pipeComputation.eval
            }

        "nested Debug calls" in
            testOutput(
                "DebugTest.scala:23:49",
                "DebugTest.scala:23:50",
                "42"
            ) {
                nestedDebugComputation.eval
            }

        "value truncation" in
            testOutput(
                "DebugTest.scala:69:59",
                "... (truncated)"
            ) {
                largeValueComputation.eval
            }
    }

    "trace" - {
        "simple computation" in
            testOutput(
                "DebugTest.scala:30:44",
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
                "DebugTest.scala:39:57",
                "10",
                "DebugTest.scala:39:57",
                "12",
                "memoizedFn(6)",
                "(10, 10, 12)"
            ) {
                memoEffectComputation.eval
            }

        "with Stream JVM" taggedAs jvmOnly in
            testOutput(
                "DebugTest.scala:55:36",
                "()",
                "DebugTest.scala:54:28",
                "()",
                "DebugTest.scala:56:21",
                "Seq(6)"
            ) {
                streamComputation.eval
            }

        "with Stream JS" taggedAs jsOnly in
            testOutput(
                "DebugTest.scala:54:28",
                "undefined",
                "DebugTest.scala:55:36",
                "Seq(Seq(6))",
                "DebugTest.scala:57:10",
                "Seq(6)"
            ) {
                streamComputation.eval
            }

        "with Choice" in
            testOutput(
                "DebugTest.scala:65:28",
                "List(4, 5, 6)",
                "DebugTest.scala:65:28",
                "6",
                "DebugTest.scala:66:14",
                "Seq(Seq(Seq(7)))"
            ) {
                choiceComputation.eval
            }

        "hides internal frame transformations" in
            testOutput(
                "DebugTest.scala:79:13",
                "8"
            ) {
                Env.run(5)(internalTransformationComputation).eval
            }
    }

    "values" - {
        "pure values" in
            testOutput(
                "DebugTest.scala:71:52",
                """Params("1" -> 1, "\"test\"" -> "test")"""
            ) {
                valuesComputation
            }

        "complex values" in
            testOutput(
                "DebugTest.scala:73:77",
                """"List(1, 2, 3)" -> List(1, 2, 3)""",
                """"Env.get[Int]" -> Kyo(Tag"""
            ) {
                complexValuesComputation
            }

        "parameter values" in
            testOutput(
                "DebugTest.scala:75:95",
                """Params("param1" -> 1, "param2" -> "test")"""
            ) {
                parameterValuesComputation(1, "test")
            }
    }

end DebugTest
