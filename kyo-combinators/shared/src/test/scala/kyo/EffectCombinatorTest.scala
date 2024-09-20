package kyo

class EffectCombinatorTest extends Test:

    "all effects" - {
        "as" - {
            "with string value" in {
                val effect         = IO(23)
                val effectAsString = effect.as("hello")
                val handled        = IO.run(effectAsString)
                assert(handled.eval == "hello")
            }
            "with integer value" in {
                val effect      = IO("test")
                val effectAsInt = effect.as(42)
                val handled     = IO.run(effectAsInt)
                assert(handled.eval == 42)
            }
        }

        "debug" - {
            "with string value" in {
                val effect  = IO("Hello World").debugValue
                val handled = IO.run(effect)
                assert(handled.eval == "Hello World")
            }
            "with integer value" in {
                val effect  = IO(42).debugValue
                val handled = IO.run(effect)
                assert(handled.eval == 42)
            }
        }

        "debug(prefix)" - {
            "with boolean value" in {
                val effect  = IO(true).debugTrace
                val handled = IO.run(effect)
                assert(handled.eval == true)
            }
            "with string value" in {
                val effect  = IO("test").debugTrace
                val handled = IO.run(effect)
                assert(handled.eval == "test")
            }
        }

        "discard" - {
            "with integer value" in {
                val effect          = IO(23)
                val effectDiscarded = effect.unit
                val handled         = IO.run(effectDiscarded)
                assert(handled.eval == ())
            }
            "with string value" in {
                val effect          = IO("hello")
                val effectDiscarded = effect.unit
                val handled         = IO.run(effectDiscarded)
                assert(handled.eval == ())
            }
        }

        "*>" - {
            "with string values" in {
                val eff1    = IO("hello")
                val eff2    = IO("world")
                val zipped  = eff1 *> eff2
                val handled = IO.run(zipped)
                assert(handled.eval == "world")
            }
            "with mixed types" in {
                val eff1    = IO(42)
                val eff2    = IO("answer")
                val zipped  = eff1 *> eff2
                val handled = IO.run(zipped)
                assert(handled.eval == "answer")
            }
        }

        "<*" - {
            "with string values" in {
                val eff1    = IO("hello")
                val eff2    = IO("world")
                val zipped  = eff1 <* eff2
                val handled = IO.run(zipped)
                assert(handled.eval == "hello")
            }
            "with mixed types" in {
                val eff1    = IO("answer")
                val eff2    = IO(42)
                val zipped  = eff1 <* eff2
                val handled = IO.run(zipped)
                assert(handled.eval == "answer")
            }
        }

        "<*>" - {
            "with string values" in {
                val eff1    = IO("hello")
                val eff2    = IO("world")
                val zipped  = eff1 <*> eff2
                val handled = IO.run(zipped)
                assert(handled.eval == ("hello", "world"))
            }
            "with mixed types" in {
                val eff1    = IO(42)
                val eff2    = IO("answer")
                val zipped  = eff1 <*> eff2
                val handled = IO.run(zipped)
                assert(handled.eval == (42, "answer"))
            }
        }

        "when" - {
            "condition is false" in {
                var state: Boolean = false
                val toggleState = IO {
                    state = !state
                }
                val getState          = IO(state)
                val effectWhen        = (toggleState *> getState).when(getState)
                val handledEffectWhen = IO.run(Abort.run(effectWhen))
                assert(handledEffectWhen.eval == Result.error(Maybe.Empty))
            }
            "condition is true" in {
                var state: Boolean = true
                val toggleState = IO {
                    state = !state
                }
                val getState          = IO(state)
                val effectWhen        = (toggleState *> getState).when(getState)
                val handledEffectWhen = IO.run(Abort.run(effectWhen))
                assert(handledEffectWhen.eval == Result.succeed(false))
            }
        }

        "unless" - {
            "condition is true" in {
                val effect = IO("value").unless(Env.get[Boolean])
                val result = IO.run {
                    Env.run(true) {
                        Abort.run {
                            effect
                        }
                    }
                }.eval
                assert(result == Result.error(Maybe.Empty))
            }
            "condition is false" in {
                val effect = IO("value").unless(Env.get[Boolean])
                val result = IO.run {
                    Env.run(false) {
                        Abort.run {
                            effect
                        }
                    }
                }.eval
                assert(result == Result.succeed("value"))
            }
        }

        "tap" - {
            "with integer value" in {
                val effect: Int < IO = IO(42).tap(v => assert(42 == v))
                val handled          = IO.run(effect)
                assert(handled.eval == 42)
            }
            "with string value" in {
                val effect: String < IO = IO("test").tap(v => assert("test" == v))
                val handled             = IO.run(effect)
                assert(handled.eval == "test")
            }
        }

        "delayed" - {
            "with short delay" in {
                val effect  = IO(42).delayed(IO(1.millis))
                val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                handled.map(v => assert(v == 42))
            }
            "with zero delay" in {
                val effect  = IO("test").delayed(IO(0.millis))
                val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                handled.map(v => assert(v == "test"))
            }
        }

        "repeat" - {
            "repeat with fixed number" - {
                "repeat 3 times" in {
                    var count   = 0
                    val effect  = IO { count += 1; count }.repeat(3)
                    val handled = IO.run(effect)
                    assert(handled.eval == 4)
                    assert(count == 4)
                }
                "repeat 0 times" in {
                    var count   = 0
                    val effect  = IO { count += 1; count }.repeat(0)
                    val handled = IO.run(effect)
                    assert(handled.eval == 1)
                    assert(count == 1)
                }
            }

            "repeat with policy" - {
                "repeat with custom policy" in {
                    var count   = 0
                    val policy  = Retry.Policy(_ => Duration.Zero, 3)
                    val effect  = IO { count += 1; count }.repeat(policy)
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.map { v =>
                        assert(v == 4)
                        assert(count == 4)
                    }
                }
            }

            "repeat with backoff and limit" - {
                "repeat with exponential backoff" in {
                    var count   = 0
                    val backoff = (i: Int) => Math.pow(2, i).toLong.millis
                    val effect  = IO { count += 1; count }.repeat(backoff, IO(3))
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.map { v =>
                        assert(v == 4)
                        assert(count == 4)
                    }
                }
            }
        }

        "repeatWhile" - {
            "repeatWhile with simple condition" - {
                "condition becomes false" in {
                    var count   = 0
                    val effect  = IO { count += 1; count }.repeatWhile(_ < 3)
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.map(v => assert(v == 3))
                }
                "condition is initially false" in {
                    var count   = 5
                    val effect  = IO { count += 1; count }.repeatWhile(_ < 3)
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.map(v => assert(v == 6))
                }
            }

            "repeatWhile with condition and duration" - {
                "condition becomes false with delay" in {
                    var count   = 0
                    val effect  = IO { count += 1; count }.repeatWhile((v, i) => (v < 3, 10.millis))
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.map(v => assert(v == 3))
                }
            }
        }

        "repeatUntil" - {
            "repeatUntil with simple condition" - {
                "condition becomes true" in {
                    var count   = 0
                    val effect  = IO { count += 1; count }.repeatUntil(_ == 3)
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.map(v => assert(v == 3))
                }
                "condition is initially true" in {
                    var count   = 0
                    val effect  = IO { count += 1; count }.repeatUntil(_ => true)
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.map(v => assert(v == 1))
                }
            }

            "repeatUntil with condition and duration" - {
                "condition becomes true with delay" in {
                    var count   = 0
                    val effect  = IO { count += 1; count }.repeatUntil((v, i) => (v == 3, 10.millis))
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.map(v => assert(v == 3))
                }
            }
        }

        "retry" - {
            "retry with fixed number" - {
                "successful after retries" in {
                    var count = 0
                    val effect = IO {
                        count += 1
                        if count < 3 then throw new Exception("Retry")
                        else count
                    }.retry(3)
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.map(v => assert(v == 3))
                }
                "fails after max retries" in {
                    var count = 0
                    val effect = IO {
                        count += 1
                        (throw new Exception("Always fails")): Int
                    }.retry(2)
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.failed.map(e => assert(e.getMessage == "Always fails"))
                }
            }

            "retry with policy" - {
                "successful after retries with custom policy" in {
                    var count  = 0
                    val policy = Retry.Policy(_ => 10.millis, 3)
                    val effect = IO {
                        count += 1
                        if count < 3 then throw new Exception("Retry")
                        else count
                    }.retry(policy)
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.map(v => assert(v == 3))
                }
            }

            "retry with backoff and limit" - {
                "successful after retries with exponential backoff" in {
                    var count   = 0
                    val backoff = (i: Int) => Math.pow(2, i).toLong.millis
                    val effect = IO {
                        count += 1
                        if count < 3 then throw new Exception("Retry")
                        else count
                    }.retry(backoff, IO(3))
                    val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                    handled.map(v => assert(v == 3))
                }
            }
        }

        "explicitThrowable" - {
            "with exception" in {
                val effect  = IO { throw new Exception("Test") }.explicitThrowable
                val handled = IO.run(Abort.run[Throwable](effect))
                assert(handled.eval.isFail)
            }
            "without exception" in {
                val effect  = IO("Success").explicitThrowable
                val handled = IO.run(Abort.run[Throwable](effect))
                assert(handled.eval.isSuccess)
            }
        }
    }
end EffectCombinatorTest
