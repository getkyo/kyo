package kyo.compat

import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
class PromiseTest extends CompatTest:
    "succeed then get returns the completed value" in run {
        val c =
            CPromise.init[Int].flatMap { p =>
                p.succeed(42).flatMap { _ =>
                    p.get.liftToTry
                }
            }
        c.map {
            case Success(42) => succeed
            case other       => fail(s"expected Success(42), got: $other")
        }
    }
    "the second succeed call returns false" in run {
        val c =
            CPromise.init[Int].flatMap { p =>
                p.succeed(1).flatMap { first =>
                    p.succeed(2).flatMap { second =>
                        CIO.defer((first, second))
                    }
                }
            }
        c.map {
            case (true, false) => succeed
            case other         => fail(s"expected (true, false), got: $other")
        }
    }
    "fail then get surfaces the error" in run {
        val err = new RuntimeException("p-err")
        val c =
            CPromise.init[Int].flatMap { p =>
                p.fail(err).flatMap { _ =>
                    p.get.liftToTry
                }
            }
        c.map {
            case Failure(t: RuntimeException) => assert(t.getMessage == "p-err")
            case other                        => fail(s"expected Failure(RuntimeException), got: $other")
        }
    }
    "poll returns None before completion, Some(Success) after succeed, Some(Failure) after fail" in run {
        val t = new RuntimeException("poll-err")
        val c =
            CPromise.init[Int].flatMap { p =>
                p.poll.flatMap { before =>
                    p.succeed(7).flatMap { _ =>
                        p.poll.flatMap { afterSucceed =>
                            CPromise.init[Int].flatMap { p2 =>
                                p2.fail(t).flatMap { _ =>
                                    p2.poll.flatMap { afterFail =>
                                        CIO.defer((before, afterSucceed, afterFail))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        c.map {
            case (None, Some(Success(7)), Some(Failure(e))) =>
                assert(e eq t, s"expected same throwable, got: $e")
            case other => fail(s"expected (None, Some(Success(7)), Some(Failure(t))), got: $other")
        }
    }
    "done reports the promise's completion state" in run {
        val c =
            CPromise.init[Int].flatMap { p =>
                p.done.flatMap { before =>
                    p.succeed(5).flatMap { _ =>
                        p.done.flatMap { after =>
                            CIO.defer((before, after))
                        }
                    }
                }
            }
        c.map {
            case (false, true) => succeed
            case other         => fail(s"expected (false, true), got: $other")
        }
    }
    "multiple gets on a completed promise all observe the value" in run {
        // Two get calls awaiting the same promise must both observe the
        // value once the promise is completed.
        val ctr = new AtomicInteger(0)
        val c =
            CPromise.init[Int].flatMap { p =>
                // Complete the promise first; then issue two sequential gets.
                p.succeed(99).flatMap { _ =>
                    p.get.liftToTry.flatMap { a =>
                        p.get.liftToTry.flatMap { b =>
                            CIO.defer {
                                val _ = ctr.incrementAndGet()
                                (a, b)
                            }
                        }
                    }
                }
            }
        c.map {
            case (Success(99), Success(99)) => assert(ctr.get == 1)
            case other                      => fail(s"expected both Success(99), got: $other")
        }
    }

    // Multiple concurrent gets, then succeed → all observe value
    "multiple concurrent gets all observe the value when promise is succeeded" in run {
        val c =
            CPromise.init[String].flatMap { p =>
                CIO.foreach(1 to 5)(_ => CFiber.init(p.get)).flatMap { fibers =>
                    CIO.sleep(50.millis).flatMap { _ =>
                        p.succeed("v").flatMap { _ =>
                            CIO.collectAll(fibers.toSeq.map(_.get))
                        }
                    }
                }
            }
        c.map { results =>
            assert(results.size == 5, s"expected 5 results, got ${results.size}")
            assert(results.toSeq.forall(_ == "v"), s"expected all \"v\", got ${results.toSeq}")
        }
    }

    // Multiple concurrent gets, then fail → all observe failure
    "multiple concurrent gets all observe failure when promise is failed" in run {
        val err = TestError("err")
        val c =
            CPromise.init[String].flatMap { p =>
                CIO.foreach(1 to 5)(_ => CFiber.init(p.get.liftToTry)).flatMap { fibers =>
                    CIO.sleep(50.millis).flatMap { _ =>
                        p.fail(err).flatMap { _ =>
                            CIO.collectAll(fibers.toSeq.map(_.get))
                        }
                    }
                }
            }
        c.map { results =>
            assert(results.size == 5, s"expected 5 results, got ${results.size}")
            results.toSeq.foreach { r =>
                assert(r.isFailure, s"expected Failure, got $r")
                r match
                    case Failure(e: TestError) => assert(e.msg == "err", s"wrong error msg: ${e.msg}")
                    case other                 => fail(s"expected Failure(TestError(\"err\")), got $other")
            }
            succeed
        }
    }

    // fail after succeed → fail returns false
    "fail after succeed returns false (already settled)" in run {
        val c =
            CPromise.init[Int].flatMap { p =>
                p.succeed(1).flatMap { firstResult =>
                    p.fail(TestError("e")).flatMap { secondResult =>
                        CIO.defer((firstResult, secondResult))
                    }
                }
            }
        c.map { case (firstResult, secondResult) =>
            assert(firstResult == true, s"expected succeed to return true, got $firstResult")
            assert(secondResult == false, s"expected fail after succeed to return false, got $secondResult")
        }
    }

    // CPromise round-trip lift/lower
    // Init promise, lift its lower, succeed via the lifted view, get returns the value.
    "CPromise round-trip lift/lower: succeed via lifted view, get returns value" in run {
        val c =
            CPromise.init[Int].flatMap { p =>
                val lifted = CPromise.lift(p.lower)
                lifted.succeed(77).flatMap { ok =>
                    lifted.get.flatMap { v =>
                        CIO.defer((ok, v))
                    }
                }
            }
        c.map { case (ok, v) =>
            assert(ok == true, s"expected succeed to return true, got $ok")
            assert(v == 77, s"expected get to return 77, got $v")
        }
    }

end PromiseTest
