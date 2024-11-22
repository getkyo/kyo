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
                    True && ~IO(True)
                }
            }
            "True/False" - {
                runLiftTest(False) {
                    True && ~IO(False)
                }
            }
            "False/NotExpected" - {
                runLiftTest(False) {
                    False && ~IO(NotExpected)
                }
            }
        }
        "impure/pure" - {
            "True/True" in {
                runLiftTest(True) {
                    ~IO(True) && True
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    ~IO(True) && False
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    ~IO(False) && NotExpected
                }
            }
        }
        "impure/impure" - {
            "True/True" in {
                runLiftTest(True) {
                    ~IO(True) && ~IO(True)
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    ~IO(True) && ~IO(False)
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    ~IO(False) && ~IO(NotExpected)
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
                    False || ~IO(False)
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    False || ~IO(True)
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    True || ~IO(NotExpected)
                }
            }
        }
        "impure/pure" - {
            "False/False" in {
                runLiftTest(False) {
                    ~IO(False) || False
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    ~IO(False) || True
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    ~IO(True) || NotExpected
                }
            }
        }
        "impure/impure" - {
            "False/False" in {
                runLiftTest(False) {
                    ~IO(False) || ~IO(False)
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    ~IO(False) || ~IO(True)
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    ~IO(True) || ~IO(NotExpected)
                }
            }
        }
    }
end BooleanTest
