package kyo

import kyo.TestSupport.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class PatMatchTest extends AnyFreeSpec with Assertions:
    "unlifted scrutinee" - {
        "without guards" - {
            "pure cases" in {
                runLiftTest(3) {
                    ~defer("b") match
                        case "a" => 2
                        case "b" => 3
                }
            }
            "pure cases with val" in {
                runLiftTest(3) {
                    val v = ~defer("b")
                    v match
                        case "a" => 2
                        case "b" => 3
                }
            }
            "pure/impure cases" in {
                runLiftTest(2) {
                    ~defer("a") match
                        case "a" => ~defer(2)
                        case "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(3) {
                    ~defer("b") match
                        case "a" => ~defer(2)
                        case "b" => ~defer(3)
                }
            }
        }
        "with guards" - {
            "pure cases" in {
                runLiftTest(3) {
                    ~defer("b") match
                        case s if s == "a" => 2
                        case "b"           => 3
                }
            }
            "pure/impure cases" in {
                runLiftTest(2) {
                    ~defer("a") match
                        case "a"           => ~defer(2)
                        case s if s == "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(2) {
                    ~defer("b") match
                        case s if "1".toInt == 1 => ~defer(2)
                        case "b"                 => ~defer(3)
                }
            }
        }
    }
    "pure scrutinee" - {
        "without guards" - {
            "pure cases" in {
                runLiftTest(3) {
                    ("b": String) match
                        case "a" => 2
                        case "b" => 3
                }
            }
            "pure/impure cases" in {
                runLiftTest(2) {
                    ~defer("a") match
                        case "a" => ~defer(2)
                        case "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(3) {
                    ("b": String) match
                        case "a" => ~defer(2)
                        case "b" => ~defer(3)
                }
            }
        }
        "with guards" - {
            "pure cases" in {
                runLiftTest(3) {
                    "b" match
                        case s if s == "a" => 2
                        case "b"           => 3
                }
            }
            "pure/impure cases" in {
                runLiftTest(2) {
                    ~defer("a") match
                        case "a"           => ~defer(2)
                        case s if s == "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(2) {
                    "b" match
                        case s if "1".toInt == 1 => ~defer(2)
                        case "b"                 => ~defer(3)
                }
            }
        }
    }
    "misc" - {
        "val patmatch" in {
            runLiftTest(1) {
                val Some(a) = ~IO(Some(1))
                a
            }
        }
    }
end PatMatchTest
