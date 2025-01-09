package kyo

class CheckTest extends Test:

    "apply" - {
        "with message" - {
            "passes when condition is true" in run {
                Check.runDiscard(Check.require(true, "This should pass").map(_ => succeed))
            }

            "fails when condition is false" in run {
                Abort.run(Check.runAbort(Check.require(false, "This should fail"))).map { r =>
                    assert(r.failure.get.asInstanceOf[CheckFailed].message == "This should fail")
                }
            }
        }
        "no message" - {
            "passes when condition is true" in run {
                Check.runDiscard(Check.require(true).map(_ => succeed))
            }

            "fails when condition is false" in run {
                Abort.run(Check.runAbort(Check.require(false))).map { r =>
                    assert(r.failure.get.asInstanceOf[CheckFailed].message == "")
                }
            }
        }
    }

    "runAbort" - {
        "returns success for passing checks" in run {
            val result = Check.runAbort {
                for
                    _ <- Check.require(true, "This should pass")
                    _ <- Check.require(1 + 1 == 2, "Basic math works")
                yield "All checks passed"
            }
            Abort.run(result).map(r => assert(r == Result.success("All checks passed")))
        }

        "returns failure for failing checks" in run {
            val result = Check.runAbort {
                for
                    _ <- Check.require(true, "This should pass")
                    _ <- Check.require(false, "This should fail")
                    _ <- Check.require(true, "This won't be reached")
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
                    _ <- Check.require(false, "First failure")
                    _ <- Check.require(true, "This passes")
                    _ <- Check.require(false, "Second failure")
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
                    _ <- Check.require(false, "This failure is discarded")
                    _ <- Check.require(true, "This passes")
                yield "Execution completed"
            }
            result.map(r => assert(r == "Execution completed"))
        }
    }

    "multiple checks" in run {
        val result = Check.runChunk {
            for
                _ <- Check.require(true, "This should pass")
                _ <- Check.require(false, "This should fail")
                _ <- Check.require(true, "This should pass too")
                _ <- Check.require(false, "This should also fail")
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
                    _   <- Check.require(env > 0, "Env should be positive")
                    _   <- Check.require(env < 10, "Env should be less than 10")
                    _   <- Check.require(env % 2 != 0, "Env should be odd")
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
                    _ <- Check.require(true, "Initial check")
                    _ <- Var.update[Int](_ + 1)
                    v <- Var.get[Int]
                    _ <- Check.require(v == 1, "Var should be updated")
                yield v
            }
        }
        result.map { case (failures, value) =>
            assert(failures.isEmpty)
            assert(value == 1)
        }
    }

    "isolate" - {
        "merge" - {
            "combines failures from isolated and outer scopes" in run {
                val result = Check.runChunk {
                    for
                        _ <- Check.require(false, "Outer failure 1")
                        isolated <- Check.isolate.merge.run {
                            for
                                _ <- Check.require(false, "Inner failure 1")
                                _ <- Check.require(true, "Inner success")
                                _ <- Check.require(false, "Inner failure 2")
                            yield "inner"
                        }
                        _ <- Check.require(false, "Outer failure 2")
                    yield (isolated)
                }
                result.map { case (failures, value) =>
                    assert(failures.size == 4)
                    assert(failures(0).message == "Outer failure 1")
                    assert(failures(1).message == "Inner failure 1")
                    assert(failures(2).message == "Inner failure 2")
                    assert(failures(3).message == "Outer failure 2")
                    assert(value == "inner")
                }
            }

            "proper state restoration after nested isolations" in run {
                val result = Check.runChunk {
                    for
                        _ <- Check.require(false, "Start failure")
                        v1 <- Check.isolate.merge.run {
                            for
                                _ <- Check.require(false, "Inner failure 1")
                                v2 <- Check.isolate.merge.run {
                                    Check.require(false, "Nested failure").map(_ => "nested-result")
                                }
                            yield v2
                        }
                        _ <- Check.require(false, "End failure")
                    yield v1
                }
                result.map { case (failures, value) =>
                    assert(failures.size == 4)
                    assert(failures(0).message == "Start failure")
                    assert(failures(1).message == "Inner failure 1")
                    assert(failures(2).message == "Nested failure")
                    assert(failures(3).message == "End failure")
                    assert(value == "nested-result")
                }
            }
        }

        "discard" - {
            "inner failures don't affect outer scope" in run {
                val result = Check.runChunk {
                    for
                        _ <- Check.require(false, "Outer failure 1")
                        isolated <- Check.isolate.discard.run {
                            for
                                _ <- Check.require(false, "Inner failure 1")
                                _ <- Check.require(false, "Inner failure 2")
                            yield "inner"
                        }
                        _ <- Check.require(false, "Outer failure 2")
                    yield isolated
                }
                result.map { case (failures, value) =>
                    assert(failures.size == 2)
                    assert(failures(0).message == "Outer failure 1")
                    assert(failures(1).message == "Outer failure 2")
                    assert(value == "inner")
                }
            }

            "nested discards maintain isolation" in run {
                val result = Check.runChunk {
                    for
                        _ <- Check.require(false, "Outer failure")
                        v1 <- Check.isolate.discard.run {
                            for
                                _ <- Check.require(false, "Discarded failure 1")
                                v2 <- Check.isolate.discard.run {
                                    Check.require(false, "Discarded failure 2").map(_ => "nested-result")
                                }
                            yield v2
                        }
                        _ <- Check.require(false, "Final failure")
                    yield v1
                }
                result.map { case (failures, value) =>
                    assert(failures.size == 2)
                    assert(failures(0).message == "Outer failure")
                    assert(failures(1).message == "Final failure")
                    assert(value == "nested-result")
                }
            }
        }

        "composition" - {
            "can combine multiple isolates" in run {
                val i1 = Check.isolate.merge
                val i2 = Emit.isolate.merge[String]

                val combined = i1.andThen(i2)

                val result = Check.runChunk {
                    Emit.run {
                        combined.run {
                            for
                                _ <- Check.require(false, "Check failure")
                                _ <- Emit.value("Emitted value")
                            yield "done"
                        }
                    }
                }
                result.map { case (failures, (emitted, value)) =>
                    assert(failures.size == 1)
                    assert(failures(0).message == "Check failure")
                    assert(emitted == Chunk("Emitted value"))
                    assert(value == "done")
                }
            }

            "preserves individual isolation behaviors when composed" in run {
                val i1 = Check.isolate.discard
                val i2 = Check.isolate.merge

                val result = Check.runChunk {
                    for
                        _ <- Check.require(false, "Start failure")
                        _ <- i1.run {
                            Check.require(false, "Discarded failure")
                        }
                        middle <- Check.require(false, "Middle failure")
                        _ <- i2.run {
                            Check.require(false, "Merged failure")
                        }
                        _ <- Check.require(false, "End failure")
                    yield "done"
                }
                result.map { case (failures, value) =>
                    assert(failures.size == 4)
                    assert(failures(0).message == "Start failure")
                    assert(failures(1).message == "Middle failure")
                    assert(failures(2).message == "Merged failure")
                    assert(failures(3).message == "End failure")
                    assert(value == "done")
                }
            }

            "with Var isolate" in run {
                val checkIsolate = Check.isolate.discard
                val varIsolate   = Var.isolate.discard[Int]

                val combined = checkIsolate.andThen(varIsolate)

                val result = Check.runChunk {
                    Var.runTuple(1) {
                        combined.run {
                            for
                                _ <- Check.require(false, "Check failure")
                                _ <- Var.update[Int](_ + 1)
                                v <- Var.get[Int]
                            yield v
                        }
                    }
                }
                result.map { case (failures, (finalVar, value)) =>
                    assert(failures.isEmpty)
                    assert(finalVar == 1)
                    assert(value == 2)
                }
            }
        }
    }

end CheckTest
