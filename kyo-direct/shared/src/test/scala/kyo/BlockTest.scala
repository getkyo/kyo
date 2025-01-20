package kyo

import kyo.TestSupport.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class BlockTest extends AnyFreeSpec with Assertions:

    "assigned run" - {
        "only" in {
            val i = IO(1)
            runLiftTest(1) {
                val v = i.now
                v
            }
        }
        "followed by pure expression" in {
            val i = IO(1)
            runLiftTest(2) {
                val v = i.now
                v + 1
            }
        }
        "followed by impure expression" in {
            val i = IO(1)
            val j = IO(2)
            runLiftTest(3) {
                val v = i.now
                v + j.now
            }
        }
        "nested" in {
            val i = IO(1)
            runLiftTest(3) {
                val v =
                    val r = i.now
                    r + 1
                v + 1
            }
        }
    }
    "unassigned run" - {
        "only" in {
            val i = IO(1)
            runLiftTest(1) {
                i.now
            }
        }
        "followed by pure expression" in {
            val i = IO(1)
            runLiftTest(2) {
                i.now
                2
            }
        }
        "followed by impure expression" in {
            val i = IO(1)
            val j = IO(2)
            runLiftTest(2) {
                i.now
                j.now
            }
        }
    }
    "pure expression" - {
        "only" in {
            runLiftTest(1) {
                1
            }
        }
        "followed by pure expression" in {
            def a = 1
            runLiftTest(2) {
                a
                2
            }
        }
        "followed by impure expression" in {
            val i = IO(1)
            def a = 2
            runLiftTest(1) {
                a
                i.now
            }
        }
        "using previous defers" in {
            val i = IO(1)
            val j = IO(2)
            runLiftTest(3) {
                val v = i.now
                v + j.now
            }
        }
        "using external function" in {
            def a(i: Int, s: String) = i + s.toInt
            runLiftTest(4) {
                IO(a(1, "2")).now + a(0, "1")
            }
        }
    }
    "complex" - {
        "tuple val pattern" in {
            runLiftTest(3) {
                val (a, b) = (IO(1).now, IO(2).now)
                a + b
            }
        }
        "block in tuple" in {
            runLiftTest((1, 2, 3)) {
                val x = 1
                (
                    IO(x).now, {
                        val a = IO(2).now
                        a
                    },
                    IO(3).now
                )
            }
        }
    }
end BlockTest
