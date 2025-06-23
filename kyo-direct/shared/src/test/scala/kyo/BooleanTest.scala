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
                    True && Sync(True).now
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    True && Sync(False).now
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    False && Sync(NotExpected).now
                }
            }
        }
        "impure/pure" - {
            "True/True" in {
                runLiftTest(True) {
                    Sync(True).now && True
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    Sync(True).now && False
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    Sync(False).now && NotExpected
                }
            }
        }
        "impure/impure" - {
            "True/True" in {
                runLiftTest(True) {
                    Sync(True).now && Sync(True).now
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    Sync(True).now && Sync(False).now
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    Sync(False).now && Sync(NotExpected).now
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
                    False || Sync(False).now
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    False || Sync(True).now
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    True || Sync(NotExpected).now
                }
            }
        }
        "impure/pure" - {
            "False/False" in {
                runLiftTest(False) {
                    Sync(False).now || False
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    Sync(False).now || True
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    Sync(True).now || NotExpected
                }
            }
        }
        "impure/impure" - {
            "False/False" in {
                runLiftTest(False) {
                    Sync(False).now || Sync(False).now
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    Sync(False).now || Sync(True).now
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    Sync(True).now || Sync(NotExpected).now
                }
            }
        }
    }
end BooleanTest
