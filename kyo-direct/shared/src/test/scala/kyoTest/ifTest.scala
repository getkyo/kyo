package kyoTest

import kyo.*
import kyo.TestSupport.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class ifTest extends AnyFreeSpec with Assertions:

    "unlifted condition / ifelse" - {
        "pure / pure" in {
            runLiftTest(2) {
                if await(IOs(1)) == 1 then 2 else 3
            }
        }
        "pure / impure" in {
            runLiftTest(2) {
                if await(IOs(1)) == 1 then await(IOs(2)) else 3
            }
        }
        "impure / pure" in {
            runLiftTest(1) {
                await(IOs(1))
            }
        }
        "impure / impure" in {
            runLiftTest(3) {
                if await(IOs(1)) == 2 then await(IOs(2)) else await(IOs(3))
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
                if 1 == 1 then await(IOs(2)) else 3
            }
        }
        "impure / pure" in {
            runLiftTest(2) {
                if 1 == 1 then 2 else await(IOs(3))
            }
        }
        "impure / impure" in {
            runLiftTest(3) {
                if 1 == 2 then await(IOs(2)) else await(IOs(3))
            }
        }
    }
end ifTest
