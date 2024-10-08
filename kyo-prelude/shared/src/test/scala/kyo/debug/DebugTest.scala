package kyo.debug

import java.io.ByteArrayOutputStream
import kyo.*

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

    def testOutput(fragments: String*)(code: => Any): Assertion =
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
                "DebugTest.scala:8:41",
                "42"
            ) {
                pureValueComputation.eval
            }

        "with effects" in
            testOutput(
                "DebugTest.scala:14:59",
                "31"
            ) {
                effectsComputation.eval
            }

        "with pipe" in
            testOutput(
                "DebugTest.scala:19:60",
                "11"
            ) {
                pipeComputation.eval
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

        "with Stream" in
            testOutput(
                "DebugTest.scala:53:36",
                "2147483647",
                "DebugTest.scala:52:28",
                "2",
                "DebugTest.scala:52:28",
                "Seq(6)"
            ) {
                streamComputation.eval
            }

        "with Choice" in
            testOutput(
                "DebugTest.scala:63:28",
                "List(4, 5, 6)",
                "DebugTest.scala:63:28",
                "6",
                "DebugTest.scala:64:14",
                "Seq(Seq(Seq(7)))"
            ) {
                choiceComputation.eval
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
                """"Env.get[Int]" -> Kyo(Tag"""
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
