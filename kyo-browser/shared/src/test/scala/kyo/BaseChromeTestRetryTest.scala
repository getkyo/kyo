package kyo

import kyo.test.AssertionFailed

/** Pins [[BaseChromeTest.retryTransient]], the retry every Chrome-driven leaf runs under, with an immediate schedule, a local failure
  * counter and a recording logger.
  */
class BaseChromeTestRetryTest extends kyo.test.Test[Any]:

    private val suite = "kyo.BaseChromeTestRetryTest"

    private case class Outcome(result: Result[Throwable, Int], attempts: Int, failures: Int, lines: Chunk[String])

    private def recordingLog(lines: AtomicRef[Chunk[String]]): Log =
        Log(new Log.Unsafe:
            val level: Log.Level                                                       = Log.Level.trace
            val name: String                                                           = suite
            def withName(n: String): Log.Unsafe                                        = this
            private def record(level: String, msg: => String)(using AllowUnsafe): Unit =
                discard(lines.unsafe.getAndUpdate(_ :+ s"$level: $msg"))
            def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("trace", msg)
            def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("trace", msg)
            def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("debug", msg)
            def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("debug", msg)
            def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = record("info", msg)
            def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record("info", msg)
            def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = record("warn", msg)
            def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = record("warn", msg)
            def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = record("error", msg)
            def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = record("error", msg))

    /** Fails attempts 1 to `last` with `ex` and returns the attempt number from then on. */
    private def failThrough(ex: Throwable, last: Int)(using Frame): Int => Int < Abort[Throwable] =
        n => if n <= last then Abort.fail(ex) else n

    private def retried(
        attempt: Int => Int < (Async & Abort[Any] & Scope),
        onRetry: Unit < Sync = Kyo.unit
    )(using Frame): Outcome < (Async & Abort[Any] & Scope) =
        for
            attempts <- AtomicInt.init(0)
            counter  <- AtomicInt.init(0)
            lines    <- AtomicRef.init(Chunk.empty[String])
            result   <- Log.let(recordingLog(lines)) {
                Abort.run[Throwable] {
                    BaseChromeTest.retryTransient(suite, Schedule.repeat(2), counter, onRetry) {
                        attempts.incrementAndGet.map(attempt)
                    }
                }
            }
            _        <- Log.flush
            count    <- attempts.get
            failures <- counter.get
            logged   <- lines.get
        yield Outcome(result, count, failures, logged)

    "a lost connection on the first attempt is retried and the second attempt's value is returned" in {
        retried(failThrough(BrowserConnectionLostException("lost"), 1)).map { o =>
            assert(o.result == Result.Success(2))
            assert(o.attempts == 2)
            assert(o.failures == 1)
            assert(o.lines.size == 1)
            assert(o.lines.head.startsWith("warn: transient browser failure BrowserConnectionLostException at "))
            assert(o.lines.head.endsWith(s" on attempt 1 of $suite (process total 1): lost"))
        }
    }

    "a navigation that failed below HTTP on the first attempt is retried" in {
        val ex = BrowserNavigationTransportFailedException("chrome-error://chromewebdata/")
        retried(failThrough(ex, 1)).map { o =>
            assert(o.result == Result.Success(2))
            assert(o.attempts == 2)
            assert(o.failures == 1)
            assert(o.lines.size == 1)
            assert(o.lines.head.startsWith("warn: transient browser failure BrowserNavigationTransportFailedException at "))
            assert(o.lines.head.endsWith(
                s" on attempt 1 of $suite (process total 1): navigation failed below HTTP on chrome-error://chromewebdata/"
            ))
        }
    }

    "a failed Chrome launch on the first attempt is retried" in {
        val ex = BrowserSetupFailedException("launch failed", new java.io.IOException("no space left"))
        retried(failThrough(ex, 1)).map { o =>
            assert(o.result == Result.Success(2))
            assert(o.attempts == 2)
            assert(o.failures == 1)
            assert(o.lines.size == 1)
            assert(o.lines.head.startsWith("warn: transient browser failure BrowserSetupFailedException at "))
            assert(o.lines.head.endsWith(s" on attempt 1 of $suite (process total 1): launch failed (IOException: no space left)"))
        }
    }

    "a failed navigation with an HTTP status is not retried" in {
        val ex = BrowserNavigationFailedException("http://127.0.0.1/", "net::ERR_ABORTED")
        retried(failThrough(ex, 1)).map { o =>
            assert(o.result == Result.Failure(ex))
            assert(o.attempts == 1)
            assert(o.failures == 0)
            assert(o.lines == Chunk.empty[String])
        }
    }

    "an assertion failure is not retried" in {
        val ex = AssertionFailed.make("expected 1, got 2", summon[Frame], Present("expected 1, got 2"), Absent)
        retried(failThrough(ex, 1)).map { o =>
            assert(o.result == Result.Failure(ex))
            assert(o.attempts == 1)
            assert(o.failures == 0)
            assert(o.lines == Chunk.empty[String])
        }
    }

    "a failure on every attempt is retried until the schedule is spent" in {
        val ex = BrowserConnectionLostException("lost")
        retried(failThrough(ex, Int.MaxValue)).map { o =>
            assert(o.result == Result.Failure(ex))
            assert(o.attempts == 3)
            assert(o.failures == 3)
            assert(o.lines.size == 3)
            assert(o.lines(0).endsWith(s" on attempt 1 of $suite (process total 1): lost"))
            assert(o.lines(1).endsWith(s" on attempt 2 of $suite (process total 2): lost"))
            assert(o.lines(2).endsWith(s" on attempt 3 of $suite (process total 3): lost"))
        }
    }

    "onRetry runs before each retry attempt and never before the first" in {
        val fail = failThrough(BrowserConnectionLostException("lost"), 2)
        for
            events <- AtomicRef.init(Chunk.empty[String])
            o      <- retried(
                n => events.updateAndGet(_ :+ s"attempt $n").andThen(fail(n)),
                onRetry = events.updateAndGet(_ :+ "retry").unit
            )
            seen <- events.get
        yield
            assert(o.result == Result.Success(3))
            assert(o.attempts == 3)
            assert(seen == Chunk("attempt 1", "retry", "attempt 2", "retry", "attempt 3"))
        end for
    }

    "a resource acquired by a failed attempt is released before the next attempt starts" in {
        val fail = failThrough(BrowserConnectionLostException("lost"), 1)
        for
            events <- AtomicRef.init(Chunk.empty[String])
            o      <- retried { n =>
                events.updateAndGet(_ :+ s"acquire $n")
                    .andThen(Scope.ensure(events.updateAndGet(_ :+ s"release $n").unit))
                    .andThen(fail(n))
            }
            seen <- events.get
        yield
            assert(o.result == Result.Success(2))
            assert(seen == Chunk("acquire 1", "release 1", "acquire 2", "release 2"))
        end for
    }

end BaseChromeTestRetryTest
