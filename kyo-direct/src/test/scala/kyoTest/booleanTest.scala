package kyoTest

import kyo._
import kyo.ios._
import kyo.direct._
import kyo.TestSupport._
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class booleanTest extends AnyFreeSpec with Assertions {

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
          True && await(IOs(True))
        }
      }
      "True/False" - {
        runLiftTest(False) {
          True && await(IOs(False))
        }
      }
      "False/NotExpected" - {
        runLiftTest(False) {
          False && await(IOs(NotExpected))
        }
      }
    }
    "impure/pure" - {
      "True/True" in {
        runLiftTest(True) {
          await(IOs(True)) && True
        }
      }
      "True/False" in {
        runLiftTest(False) {
          await(IOs(True)) && False
        }
      }
      "False/NotExpected" in {
        runLiftTest(False) {
          await(IOs(False)) && NotExpected
        }
      }
    }
    "impure/impure" - {
      "True/True" in {
        runLiftTest(True) {
          await(IOs(True)) && await(IOs(True))
        }
      }
      "True/False" in {
        runLiftTest(False) {
          await(IOs(True)) && await(IOs(False))
        }
      }
      "False/NotExpected" in {
        runLiftTest(False) {
          await(IOs(False)) && await(IOs(NotExpected))
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
          False || await(IOs(False))
        }
      }
      "False/True" in {
        runLiftTest(True) {
          False || await(IOs(True))
        }
      }
      "True/NotExpected" in {
        runLiftTest(True) {
          True || await(IOs(NotExpected))
        }
      }
    }
    "impure/pure" - {
      "False/False" in {
        runLiftTest(False) {
          await(IOs(False)) || False
        }
      }
      "False/True" in {
        runLiftTest(True) {
          await(IOs(False)) || True
        }
      }
      "True/NotExpected" in {
        runLiftTest(True) {
          await(IOs(True)) || NotExpected
        }
      }
    }
    "impure/impure" - {
      "False/False" in {
        runLiftTest(False) {
          await(IOs(False)) || await(IOs(False))
        }
      }
      "False/True" in {
        runLiftTest(True) {
          await(IOs(False)) || await(IOs(True))
        }
      }
      "True/NotExpected" in {
        runLiftTest(True) {
          await(IOs(True)) || await(IOs(NotExpected))
        }
      }
    }
  }
}
