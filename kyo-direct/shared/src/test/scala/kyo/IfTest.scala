package kyoTest

import kyo.*
import kyo.TestSupport.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class IfTest extends AnyFreeSpec with Assertions:

    "unlifted condition / ifelse" - {
        "pure / pure" in {
            runLiftTest(2) {
                if await(IO(1)) == 1 then 2 else 3
            }
        }
        "pure / impure" in {
            runLiftTest(2) {
                if await(IO(1)) == 1 then await(IO(2)) else 3
            }
        }
        "impure / pure" in {
            runLiftTest(1) {
                await(IO(1))
            }
        }
        "impure / impure" in {
            runLiftTest(3) {
                if await(IO(1)) == 2 then await(IO(2)) else await(IO(3))
            }
        }
    }
    "pure condition / ifelse" - {
        "pure / pure" in {
            runLiftTest(2) {
                if 1 == 1 then 2 else 3
            }
        }
        "pure / impure" in {
            runLiftTest(2) {
                if 1 == 1 then await(IO(2)) else 3
            }
        }
        "impure / pure" in {
            runLiftTest(2) {
                if 1 == 1 then 2 else await(IO(3))
            }
        }
        "impure / impure" in {
            runLiftTest(3) {
                if 1 == 2 then await(IO(2)) else await(IO(3))
            }
        }
    }
end IfTest
