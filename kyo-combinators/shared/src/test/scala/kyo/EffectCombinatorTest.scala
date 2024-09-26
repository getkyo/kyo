package kyo

class EffectCombinatorTest extends Test:

    "all effects" - {
        "as" - {
            "with string value" in run {
                val effect         = IO(23)
                val effectAsString = effect.as("hello")
                effectAsString.map { handled =>
                    assert(handled == "hello")
                }
            }
            "with integer value" in run {
                val effect      = IO("test")
                val effectAsInt = effect.as(42)
                effectAsInt.map { handled =>
                    assert(handled == 42)
                }
            }
        }

        "debug" - {
            "with string value" in run {
                val effect = IO("Hello World").debugValue
                effect.map { handled =>
                    assert(handled == "Hello World")
                }
            }
            "with integer value" in run {
                val effect = IO(42).debugValue
                effect.map { handled =>
                    assert(handled == 42)
                }
            }
        }

        "debug(prefix)" - {
            "with boolean value" in run {
                val effect = IO(true).debugTrace
                effect.map { handled =>
                    assert(handled == true)
                }
            }
            "with string value" in run {
                val effect = IO("test").debugTrace
                effect.map { handled =>
                    assert(handled == "test")
                }
            }
        }

        "discard" - {
            "with integer value" in run {
                val effect          = IO(23)
                val effectDiscarded = effect.unit
                effectDiscarded.map { handled =>
                    assert(handled == ())
                }
            }
            "with string value" in run {
                val effect          = IO("hello")
                val effectDiscarded = effect.unit
                effectDiscarded.map { handled =>
                    assert(handled == ())
                }
            }
        }

        "*>" - {
            "with string values" in run {
                val eff1   = IO("hello")
                val eff2   = IO("world")
                val zipped = eff1 *> eff2
                zipped.map { handled =>
                    assert(handled == "world")
                }
            }
            "with mixed types" in run {
                val eff1   = IO(42)
                val eff2   = IO("answer")
                val zipped = eff1 *> eff2
                zipped.map { handled =>
                    assert(handled == "answer")
                }
            }
        }

        "<*" - {
            "with string values" in run {
                val eff1   = IO("hello")
                val eff2   = IO("world")
                val zipped = eff1 <* eff2
                zipped.map { handled =>
                    assert(handled == "hello")
                }
            }
            "with mixed types" in run {
                val eff1   = IO("answer")
                val eff2   = IO(42)
                val zipped = eff1 <* eff2
                zipped.map { handled =>
                    assert(handled == "answer")
                }
            }
        }

        "<*>" - {
            "with string values" in run {
                val eff1   = IO("hello")
                val eff2   = IO("world")
                val zipped = eff1 <*> eff2
                zipped.map { handled =>
                    assert(handled == ("hello", "world"))
                }
            }
            "with mixed types" in run {
                val eff1   = IO(42)
                val eff2   = IO("answer")
                val zipped = eff1 <*> eff2
                zipped.map { handled =>
                    assert(handled == (42, "answer"))
                }
            }
        }

        "when" - {
            "condition is false" in run {
                var state: Boolean = false
                val toggleState = IO {
                    state = !state
                }
                val getState   = IO(state)
                val effectWhen = (toggleState *> getState).when(getState)
                Abort.run(effectWhen).map { handledEffectWhen =>
                    assert(handledEffectWhen == Result.fail(Maybe.Empty))
                }
            }
            "condition is true" in run {
                var state: Boolean = true
                val toggleState = IO {
                    state = !state
                }
                val getState   = IO(state)
                val effectWhen = (toggleState *> getState).when(getState)
                Abort.run(effectWhen).map { handledEffectWhen =>
                    assert(handledEffectWhen == Result.success(false))
                }
            }
        }

        "unless" - {
            "condition is true" in run {
                val effect = IO("value").unless(Env.get[Boolean])
                Env.run(true) {
                    Abort.run {
                        effect
                    }
                }.map { result =>
                    assert(result == Result.fail(Maybe.Empty))
                }
            }
            "condition is false" in run {
                val effect = IO("value").unless(Env.get[Boolean])
                Env.run(false) {
                    Abort.run {
                        effect
                    }
                }.map { result =>
                    assert(result == Result.success("value"))
                }
            }
        }

        "tap" - {
            "with integer value" in run {
                val effect: Int < IO = IO(42).tap(v => assert(42 == v))
                effect.map { handled =>
                    assert(handled == 42)
                }
            }
            "with string value" in run {
                val effect: String < IO = IO("test").tap(v => assert("test" == v))
                effect.map { handled =>
                    assert(handled == "test")
                }
            }
        }

        "delayed" - {
            "with short delay" in run {
                val effect = IO(42).delayed(IO(1.millis))
                Async.run(effect).map(_.toFuture).map { handled =>
                    handled.map(v => assert(v == 42))
                }
            }
            "with zero delay" in run {
                val effect = IO("test").delayed(IO(0.millis))
                Async.run(effect).map(_.toFuture).map { handled =>
                    handled.map(v => assert(v == "test"))
                }
            }
        }

        "repeat" - {
            "repeat with fixed number" - {
                "repeat 3 times" in run {
                    var count  = 0
                    val effect = IO { count += 1; count }.repeat(3)
                    effect.map { handled =>
                        assert(handled == 4)
                        assert(count == 4)
                    }
                }
                "repeat 0 times" in run {
                    var count  = 0
                    val effect = IO { count += 1; count }.repeat(0)
                    effect.map { handled =>
                        assert(handled == 1)
                        assert(count == 1)
                    }
                }
            }

            "repeat with policy" - {
                "repeat with custom policy" in run {
                    var count  = 0
                    val policy = Retry.Policy(_ => Duration.Zero, 3)
                    val effect = IO { count += 1; count }.repeat(policy)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map { v =>
                            assert(v == 4)
                            assert(count == 4)
                        }
                    }
                }
            }

            "repeat with backoff and limit" - {
                "repeat with exponential backoff" in run {
                    var count   = 0
                    val backoff = (i: Int) => Math.pow(2, i).toLong.millis
                    val effect  = IO { count += 1; count }.repeat(backoff, IO(3))
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map { v =>
                            assert(v == 4)
                            assert(count == 4)
                        }
                    }
                }
            }
        }

        "repeatWhile" - {
            "repeatWhile with simple condition" - {
                "condition becomes false" in run {
                    var count  = 0
                    val effect = IO { count += 1; count }.repeatWhile(_ < 3)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 3))
                    }
                }
                "condition is initially false" in run {
                    var count  = 5
                    val effect = IO { count += 1; count }.repeatWhile(_ < 3)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 6))
                    }
                }
            }

            "repeatWhile with condition and duration" - {
                "condition becomes false with delay" in run {
                    var count  = 0
                    val effect = IO { count += 1; count }.repeatWhile((v, i) => (v < 3, 10.millis))
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 3))
                    }
                }
            }
        }

        "repeatUntil" - {
            "repeatUntil with simple condition" - {
                "condition becomes true" in run {
                    var count  = 0
                    val effect = IO { count += 1; count }.repeatUntil(_ == 3)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 3))
                    }
                }
                "condition is initially true" in run {
                    var count  = 0
                    val effect = IO { count += 1; count }.repeatUntil(_ => true)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 1))
                    }
                }
            }

            "repeatUntil with condition and duration" - {
                "condition becomes true with delay" in run {
                    var count  = 0
                    val effect = IO { count += 1; count }.repeatUntil((v, i) => (v == 3, 10.millis))
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 3))
                    }
                }
            }
        }

        "retry" - {
            "retry with fixed number" - {
                "successful after retries" in run {
                    var count = 0
                    val effect = IO {
                        count += 1
                        if count < 3 then throw new Exception("Retry")
                        else count
                    }.retry(3)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 3))
                    }
                }
                "fails after max retries" in run {
                    var count = 0
                    val effect = IO {
                        count += 1
                        (throw new Exception("Always fails")): Int
                    }.retry(2)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.failed.map(e => assert(e.getMessage == "Always fails"))
                    }
                }
            }

            "retry with policy" - {
                "successful after retries with custom policy" in run {
                    var count  = 0
                    val policy = Retry.Policy(_ => 10.millis, 3)
                    val effect = IO {
                        count += 1
                        if count < 3 then throw new Exception("Retry")
                        else count
                    }.retry(policy)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 3))
                    }
                }
            }

            "retry with backoff and limit" - {
                "successful after retries with exponential backoff" in run {
                    var count   = 0
                    val backoff = (i: Int) => Math.pow(2, i).toLong.millis
                    val effect = IO {
                        count += 1
                        if count < 3 then throw new Exception("Retry")
                        else count
                    }.retry(backoff, IO(3))
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 3))
                    }
                }
            }
        }

        "explicitThrowable" - {
            "with exception" in run {
                val effect = IO { throw new Exception("Test") }.explicitThrowable
                Abort.run[Throwable](effect).map { handled =>
                    assert(handled.isFail)
                }
            }
            "without exception" in run {
                val effect = IO("Success").explicitThrowable
                Abort.run[Throwable](effect).map { handled =>
                    assert(handled.isSuccess)
                }
            }
        }
    }
end EffectCombinatorTest
