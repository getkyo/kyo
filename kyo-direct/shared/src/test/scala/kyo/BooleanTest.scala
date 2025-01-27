package kyo

import kyo.TestSupport.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class BooleanTest extends AnyFreeSpec with Assertions:

    def True                 = "1".toInt == 1
    def False                = "1".toInt == 0
    def NotExpected: Boolean = ???

    "&&" - {
        "pure/pure" in {
            runLiftTest(False) {
                1 == 1 && 2 == 3
            }
        }
        "pure/impure" - {
            "True/True" in {
                runLiftTest(True) {
                    True && IO(True).now
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    True && IO(False).now
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    False && IO(NotExpected).now
                }
            }
        }
        "impure/pure" - {
            "True/True" in {
                runLiftTest(True) {
                    IO(True).now && True
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    IO(True).now && False
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    IO(False).now && NotExpected
                }
            }
        }
        "impure/impure" - {
            "True/True" in {
                runLiftTest(True) {
                    IO(True).now && IO(True).now
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    IO(True).now && IO(False).now
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    IO(False).now && IO(NotExpected).now
                }
            }
        }
    }
    "||" - {
        "pure/pure" in {
            runLiftTest(True) {
                1 == 1 || 2 == 3
            }
        }
        "pure/impure" - {
            "False/False" in {
                runLiftTest(False) {
                    False || IO(False).now
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    False || IO(True).now
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    True || IO(NotExpected).now
                }
            }
        }
        "impure/pure" - {
            "False/False" in {
                runLiftTest(False) {
                    IO(False).now || False
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    IO(False).now || True
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    IO(True).now || NotExpected
                }
            }
        }
        "impure/impure" - {
            "False/False" in {
                runLiftTest(False) {
                    IO(False).now || IO(False).now
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    IO(False).now || IO(True).now
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    IO(True).now || IO(NotExpected).now
                }
            }
        }
    }
end BooleanTest
