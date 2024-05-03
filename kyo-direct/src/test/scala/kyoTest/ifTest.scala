package kyoTest

import kyo.*
import kyo.TestSupport.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class ifTest extends AnyFreeSpec with Assertions:

    "unlifted condition / ifelse" - {
        "pure / pure" in {
            runLiftTest(2) {
                if await(Defers(1)) == 1 then 2 else 3
            }
        }
        "pure / impure" in {
            runLiftTest(2) {
                if await(Defers(1)) == 1 then await(Defers(2)) else 3
            }
        }
        "impure / pure" in {
            runLiftTest(1) {
                await(Defers(1))
            }
        }
        "impure / impure" in {
            runLiftTest(3) {
                if await(Defers(1)) == 2 then await(Defers(2)) else await(Defers(3))
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
                if 1 == 1 then await(Defers(2)) else 3
            }
        }
        "impure / pure" in {
            runLiftTest(2) {
                if 1 == 1 then 2 else await(Defers(3))
            }
        }
        "impure / impure" in {
            runLiftTest(3) {
                if 1 == 2 then await(Defers(2)) else await(Defers(3))
            }
        }
    }
end ifTest
