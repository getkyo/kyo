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
                    True && Sync.defer(True).now
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    True && Sync.defer(False).now
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    False && Sync.defer(NotExpected).now
                }
            }
        }
        "impure/pure" - {
            "True/True" in {
                runLiftTest(True) {
                    Sync.defer(True).now && True
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    Sync.defer(True).now && False
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    Sync.defer(False).now && NotExpected
                }
            }
        }
        "impure/impure" - {
            "True/True" in {
                runLiftTest(True) {
                    Sync.defer(True).now && Sync.defer(True).now
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    Sync.defer(True).now && Sync.defer(False).now
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    Sync.defer(False).now && Sync.defer(NotExpected).now
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
                    False || Sync.defer(False).now
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    False || Sync.defer(True).now
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    True || Sync.defer(NotExpected).now
                }
            }
        }
        "impure/pure" - {
            "False/False" in {
                runLiftTest(False) {
                    Sync.defer(False).now || False
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    Sync.defer(False).now || True
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    Sync.defer(True).now || NotExpected
                }
            }
        }
        "impure/impure" - {
            "False/False" in {
                runLiftTest(False) {
                    Sync.defer(False).now || Sync.defer(False).now
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    Sync.defer(False).now || Sync.defer(True).now
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    Sync.defer(True).now || Sync.defer(NotExpected).now
                }
            }
        }
    }
end BooleanTest
