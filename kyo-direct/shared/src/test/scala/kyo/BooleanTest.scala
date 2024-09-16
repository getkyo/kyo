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
                    True && await(IO(True))
                }
            }
            "True/False" - {
                runLiftTest(False) {
                    True && await(IO(False))
                }
            }
            "False/NotExpected" - {
                runLiftTest(False) {
                    False && await(IO(NotExpected))
                }
            }
        }
        "impure/pure" - {
            "True/True" in {
                runLiftTest(True) {
                    await(IO(True)) && True
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    await(IO(True)) && False
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    await(IO(False)) && NotExpected
                }
            }
        }
        "impure/impure" - {
            "True/True" in {
                runLiftTest(True) {
                    await(IO(True)) && await(IO(True))
                }
            }
            "True/False" in {
                runLiftTest(False) {
                    await(IO(True)) && await(IO(False))
                }
            }
            "False/NotExpected" in {
                runLiftTest(False) {
                    await(IO(False)) && await(IO(NotExpected))
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
                    False || await(IO(False))
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    False || await(IO(True))
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    True || await(IO(NotExpected))
                }
            }
        }
        "impure/pure" - {
            "False/False" in {
                runLiftTest(False) {
                    await(IO(False)) || False
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    await(IO(False)) || True
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    await(IO(True)) || NotExpected
                }
            }
        }
        "impure/impure" - {
            "False/False" in {
                runLiftTest(False) {
                    await(IO(False)) || await(IO(False))
                }
            }
            "False/True" in {
                runLiftTest(True) {
                    await(IO(False)) || await(IO(True))
                }
            }
            "True/NotExpected" in {
                runLiftTest(True) {
                    await(IO(True)) || await(IO(NotExpected))
                }
            }
        }
    }
end BooleanTest
