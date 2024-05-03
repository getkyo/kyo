package kyoTest

import kyo.*
import kyo.TestSupport.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class booleanTest extends AnyFreeSpec with Assertions:

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
                    True && await(Defers(True))
                }
            }
            "True/False" - {
                runLiftTest(False) {
                    True && await(Defers(False))
                }
            }
            "False/NotExpected" - {
                runLiftTest(False) {
                    False && await(Defers(NotExpected))
                }
            }
        }
        "impure/pure" - {
            "True/True" in {
                runLiftTest(True) {
                    await(Defers(True)) && True
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    await(Defers(True)) && False
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    await(Defers(False)) && NotExpected
                }
            }
        }
        "impure/impure" - {
            "True/True" in {
                runLiftTest(True) {
                    await(Defers(True)) && await(Defers(True))
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    await(Defers(True)) && await(Defers(False))
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    await(Defers(False)) && await(Defers(NotExpected))
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
                    False || await(Defers(False))
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    False || await(Defers(True))
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    True || await(Defers(NotExpected))
                }
            }
        }
        "impure/pure" - {
            "False/False" in {
                runLiftTest(False) {
                    await(Defers(False)) || False
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    await(Defers(False)) || True
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    await(Defers(True)) || NotExpected
                }
            }
        }
        "impure/impure" - {
            "False/False" in {
                runLiftTest(False) {
                    await(Defers(False)) || await(Defers(False))
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    await(Defers(False)) || await(Defers(True))
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    await(Defers(True)) || await(Defers(NotExpected))
                }
            }
        }
    }
end booleanTest
