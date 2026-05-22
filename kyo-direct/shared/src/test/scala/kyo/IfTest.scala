package kyo

import kyo.TestSupport.*
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec

class IfTest extends AnyFreeSpec with Assertions:

    "unlifted condition / ifelse" - {
        "pure / pure" in {
            runLiftTest(2) {
                if Sync.defer(1).now == 1 then 2 else 3
            }
        }
        "pure / impure" in {
            runLiftTest(2) {
                if Sync.defer(1).now == 1 then Sync.defer(2).now else 3
            }
        }
        "impure / pure" in {
            runLiftTest(1) {
                Sync.defer(1).now
            }
        }
        "impure / impure" in {
            runLiftTest(3) {
                if Sync.defer(1).now == 2 then Sync.defer(2).now else Sync.defer(3).now
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
                if 1 == 1 then Sync.defer(2).now else 3
            }
        }
        "impure / pure" in {
            runLiftTest(2) {
                if 1 == 1 then 2 else Sync.defer(3).now
            }
        }
        "impure / impure" in {
            runLiftTest(3) {
                if 1 == 2 then Sync.defer(2).now else Sync.defer(3).now
            }
        }
    }
    "val binding inside branch" - {
        "then-branch" in {
            runLiftTest(11) {
                val cond = Sync.defer(true).now
                if cond then
                    val x = Sync.defer(10).now
                    x + 1
                else 0
                end if
            }
        }
        "else-branch" in {
            runLiftTest(20) {
                val cond = Sync.defer(false).now
                if cond then 0
                else
                    val x = Sync.defer(20).now
                    x
                end if
            }
        }
    }
end IfTest
