package kyoTest

import kyo.*
import kyo.TestSupport.*
import kyo.direct.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class patMatchTest extends AnyFreeSpec with Assertions:
    "unlifted scrutinee" - {
        "without guards" - {
            "pure cases" in {
                runLiftTest(3) {
                    await(defer("b")) match
                        case "a" => 2
                        case "b" => 3
                }
            }
            "pure cases with val" in {
                runLiftTest(3) {
                    val v = await(defer("b"))
                    v match
                        case "a" => 2
                        case "b" => 3
                }
            }
            "pure/impure cases" in {
                runLiftTest(2) {
                    await(defer("a")) match
                        case "a" => await(defer(2))
                        case "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(3) {
                    await(defer("b")) match
                        case "a" => await(defer(2))
                        case "b" => await(defer(3))
                }
            }
        }
        "with guards" - {
            "pure cases" in {
                runLiftTest(3) {
                    await(defer("b")) match
                        case s if s == "a" => 2
                        case "b"           => 3
                }
            }
            "pure/impure cases" in {
                runLiftTest(2) {
                    await(defer("a")) match
                        case "a"           => await(defer(2))
                        case s if s == "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(2) {
                    await(defer("b")) match
                        case s if "1".toInt == 1 => await(defer(2))
                        case "b"                 => await(defer(3))
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
                    await(defer("a")) match
                        case "a" => await(defer(2))
                        case "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(3) {
                    ("b": String) match
                        case "a" => await(defer(2))
                        case "b" => await(defer(3))
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
                    await(defer("a")) match
                        case "a"           => await(defer(2))
                        case s if s == "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(2) {
                    "b" match
                        case s if "1".toInt == 1 => await(defer(2))
                        case "b"                 => await(defer(3))
                }
            }
        }
    }
    "misc" - {
        "val patmatch" in {
            runLiftTest(1) {
                val Some(a) = await(IOs(Some(1)))
                a
            }
        }

        // "unlifted guard" in {
        //   runLiftTest(2) {
        //     "b" match {
        //       case s if await(defer(true)) => 2
        //       case "b"                     => await(defer(3))
        //     }
        //   }
        // }
    }
end patMatchTest
