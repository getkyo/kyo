package kyo

class CheckTest extends Test:

    "apply" - {
        "with message" - {
            "passes when condition is true" in run {
                Check.runDiscard(Check(true, "This should pass").map(_ => succeed))
            }

            "fails when condition is false" in run {
                Abort.run(Check.runAbort(Check(false, "This should fail"))).map { r =>
                    assert(r.failure.get.asInstanceOf[CheckFailed].message == "This should fail")
                }
            }
        }
        "no message" - {
            "passes when condition is true" in run {
                Check.runDiscard(Check(true).map(_ => succeed))
            }

            "fails when condition is false" in run {
                Abort.run(Check.runAbort(Check(false))).map { r =>
                    assert(r.failure.get.asInstanceOf[CheckFailed].message == "")
                }
            }
        }
    }

    "runAbort" - {
        "returns success for passing checks" in run {
            val result = Check.runAbort {
                for
                    _ <- Check(true, "This should pass")
                    _ <- Check(1 + 1 == 2, "Basic math works")
                yield "All checks passed"
            }
            Abort.run(result).map(r => assert(r == Result.succeed("All checks passed")))
        }

        "returns failure for failing checks" in run {
            val result = Check.runAbort {
                for
                    _ <- Check(true, "This should pass")
                    _ <- Check(false, "This should fail")
                    _ <- Check(true, "This won't be reached")
                yield "Shouldn't get here"
            }
            Abort.run(result).map { r =>
                assert(r.failure.get.asInstanceOf[CheckFailed].message == "This should fail")
            }
        }
    }

    "runChunk" - {
        "collects all check failures" in run {
            val result = Check.runChunk {
                for
                    _ <- Check(false, "First failure")
                    _ <- Check(true, "This passes")
                    _ <- Check(false, "Second failure")
                yield "Done"
            }
            result.map { case (failures, value) =>
                assert(failures.size == 2)
                assert(failures(0).message == "First failure")
                assert(failures(1).message == "Second failure")
                assert(value == "Done")
            }
        }
    }

    "runDiscard" - {
        "discards check failures and continues execution" in run {
            val result = Check.runDiscard {
                for
                    _ <- Check(false, "This failure is discarded")
                    _ <- Check(true, "This passes")
                yield "Execution completed"
            }
            result.map(r => assert(r == "Execution completed"))
        }
    }

    "multiple checks" in run {
        val result = Check.runChunk {
            for
                _ <- Check(true, "This should pass")
                _ <- Check(false, "This should fail")
                _ <- Check(true, "This should pass too")
                _ <- Check(false, "This should also fail")
            yield "Done"
        }
        result.map { case (failures, value) =>
            assert(failures.size == 2)
            assert(failures(0).message == "This should fail")
            assert(failures(1).message == "This should also fail")
            assert(value == "Done")
        }
    }

    "checks with effects" in run {
        val result = Env.run(5) {
            Check.runChunk {
                for
                    env <- Env.get[Int]
                    _   <- Check(env > 0, "Env should be positive")
                    _   <- Check(env < 10, "Env should be less than 10")
                    _   <- Check(env % 2 != 0, "Env should be odd")
                yield env
            }
        }
        result.map { case (failures, value) =>
            assert(failures.isEmpty)
            assert(value == 5)
        }
    }

    "combining with other effects" in run {
        val result = Var.run(0) {
            Check.runChunk {
                for
                    _ <- Check(true, "Initial check")
                    _ <- Var.update[Int](_ + 1)
                    v <- Var.get[Int]
                    _ <- Check(v == 1, "Var should be updated")
                yield v
            }
        }
        result.map { case (failures, value) =>
            assert(failures.isEmpty)
            assert(value == 1)
        }
    }

end CheckTest
