package kyo

import kyo.internal.TestSupport.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class PatMatchTest extends AnyFreeSpec with Assertions:
    "unlifted scrutinee" - {
        "without guards" - {
            "pure cases" in {
                runLiftTest(3) {
                    direct("b").now match
                        case "a" => 2
                        case "b" => 3
                }
            }
            "pure cases with val" in {
                runLiftTest(3) {
                    val v = direct("b").now
                    v match
                        case "a" => 2
                        case "b" => 3
                }
            }
            "pure/impure cases" in {
                runLiftTest(2) {
                    direct("a").now match
                        case "a" => direct(2).now
                        case "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(3) {
                    direct("b").now match
                        case "a" => direct(2).now
                        case "b" => direct(3).now
                }
            }
        }
        "with guards" - {
            "pure cases" in {
                runLiftTest(3) {
                    direct("b").now match
                        case s if s == "a" => 2
                        case "b"           => 3
                }
            }
            "pure/impure cases" in {
                runLiftTest(2) {
                    direct("a").now match
                        case "a"           => direct(2).now
                        case s if s == "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(2) {
                    direct("b").now match
                        case s if "1".toInt == 1 => direct(2).now
                        case "b"                 => direct(3).now
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
                    direct("a").now match
                        case "a" => direct(2).now
                        case "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(3) {
                    ("b": String) match
                        case "a" => direct(2).now
                        case "b" => direct(3).now
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
                    direct("a").now match
                        case "a"           => direct(2).now
                        case s if s == "b" => 3
                }
            }
            "impure cases" in {
                runLiftTest(2) {
                    "b" match
                        case s if "1".toInt == 1 => direct(2).now
                        case "b"                 => direct(3).now
                }
            }
        }
    }
    "misc" - {
        "val patmatch" in {
            runLiftTest(1) {
                val Some(a) = Sync.defer(Some(1)).now
                a
            }
        }
        "val binding inside case body" in {
            runLiftTest(10) {
                val opt = Sync.defer(Option(5)).now
                opt match
                    case Some(v) =>
                        val doubled = Sync.defer(v * 2).now
                        doubled
                    case None => 0
                end match
            }
        }
        "pattern-bound effect-typed identifier used via .now" in {
            runLiftTest(14) {
                val opt: Option[Int < Sync] = Option(Sync.defer(7).later)
                opt match
                    case Some(inner) => inner.now * 2
                    case None        => 0
            }
        }
    }
end PatMatchTest
