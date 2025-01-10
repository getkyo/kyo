package kyo

class TScheduleTest extends Test:
    "init" - {
        "creates a transactional schedule with initial state" in run {
            for
                ts     <- TSchedule.init(Schedule.fixed(1.second))
                next   <- STM.run(ts.next)
                result <- STM.run(ts.peek)
            yield
                assert(next == Maybe(1.second))
                assert(result == Maybe(1.second))
        }

        "handles never schedule" in run {
            for
                ts     <- TSchedule.init(Schedule.never)
                next   <- STM.run(ts.next)
                result <- STM.run(ts.peek)
            yield
                assert(next.isEmpty)
                assert(result.isEmpty)
        }

        "handles immediate schedule" in run {
            for
                ts     <- TSchedule.init(Schedule.immediate)
                next   <- STM.run(ts.next)
                result <- STM.run(ts.peek)
            yield
                assert(next == Present(Duration.Zero))
                assert(result == Absent)
        }
    }

    "next" - {
        "advances schedule state" in run {
            for
                ts    <- TSchedule.init(Schedule.exponential(1.second, 2.0))
                next1 <- STM.run(ts.next)
                next2 <- STM.run(ts.next)
                next3 <- STM.run(ts.next)
            yield
                assert(next1 == Maybe(1.second))
                assert(next2 == Maybe(2.seconds))
                assert(next3 == Maybe(4.seconds))
        }

        "handles finite schedules" in run {
            for
                ts    <- TSchedule.init(Schedule.fixed(1.second).take(2))
                next1 <- STM.run(ts.next)
                next2 <- STM.run(ts.next)
                next3 <- STM.run(ts.next)
            yield
                assert(next1 == Maybe(1.second))
                assert(next2 == Maybe(1.second))
                assert(next3.isEmpty)
        }

        "maintains consistency across transactions" in run {
            for
                ts <- TSchedule.init(Schedule.fixed(1.second))
                result <- Abort.run {
                    STM.run {
                        for
                            next1 <- ts.next
                            _     <- Abort.fail(new Exception("Test failure"))
                            next2 <- ts.next
                        yield (next1, next2)
                    }
                }
                nextAfterRollback <- STM.run(ts.next)
            yield
                assert(result.isFail)
                assert(nextAfterRollback == Maybe(1.second))
        }
    }

    "peek" - {
        "returns next duration without advancing state" in run {
            for
                ts    <- TSchedule.init(Schedule.fixed(1.second).take(1))
                peek1 <- STM.run(ts.peek)
                peek2 <- STM.run(ts.peek)
                next  <- STM.run(ts.next)
                peek3 <- STM.run(ts.peek)
            yield
                assert(peek1 == Maybe(1.second))
                assert(peek2 == Maybe(1.second))
                assert(next == Maybe(1.second))
                assert(peek3 == Absent)
        }

        "works with complex schedules" in run {
            for
                ts    <- TSchedule.init(Schedule.exponential(1.second, 2.0).take(3))
                peek1 <- STM.run(ts.peek)
                _     <- STM.run(ts.next)
                peek2 <- STM.run(ts.peek)
                _     <- STM.run(ts.next)
                peek3 <- STM.run(ts.peek)
                _     <- STM.run(ts.next)
                peek4 <- STM.run(ts.peek)
            yield
                assert(peek1 == Maybe(1.second))
                assert(peek2 == Maybe(2.seconds))
                assert(peek3 == Maybe(4.seconds))
                assert(peek4.isEmpty)
        }
    }

    "use" - {
        "allows custom operations on schedule state" in run {
            for
                ts     <- TSchedule.init(Schedule.fixed(1.second))
                result <- STM.run(ts.use(dur => dur.map(_.toMillis)))
            yield assert(result == Maybe(1000L))
        }

        "maintains transaction isolation" in run {
            for
                ts <- TSchedule.init(Schedule.fixed(1.second).take(1))
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- ts.use(_ => Abort.fail(new Exception("Test failure")))
                            _ <- ts.next
                        yield ()
                    }
                }
                nextAfterRollback <- STM.run(ts.next)
            yield
                assert(result.isFail)
                assert(nextAfterRollback == Maybe(1.second))
        }
    }

    "error handling" - {
        "rolls back on transaction failure" in run {
            for
                ts <- TSchedule.init(Schedule.fixed(1.second))
                result <- Abort.run {
                    STM.run {
                        for
                            next1 <- ts.next
                            _     <- Abort.fail(new Exception("Test failure"))
                            next2 <- ts.next
                        yield (next1, next2)
                    }
                }
                nextAfterFailure <- STM.run(ts.next)
            yield
                assert(result.isFail)
                assert(nextAfterFailure == Maybe(1.second))
        }

        "handles nested transaction failures" in run {
            for
                ts <- TSchedule.init(Schedule.fixed(1.second))
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- ts.next
                            _ <- STM.run {
                                for
                                    _ <- ts.next
                                    _ <- Abort.fail(new Exception("Nested failure"))
                                yield ()
                            }
                        yield ()
                    }
                }
                nextAfterFailure <- STM.run(ts.next)
            yield
                assert(result.isFail)
                assert(nextAfterFailure == Maybe(1.second))
        }
    }
end TScheduleTest
