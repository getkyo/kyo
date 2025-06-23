package kyo

class KyoCombinatorsTest extends Test:

    "all effects" - {

        "debug" - {
            "with string value" in run {
                val effect = Sync("Hello World")
                effect.map { handled =>
                    assert(handled == "Hello World")
                }
            }
            "with integer value" in run {
                val effect = Sync(42)
                effect.map { handled =>
                    assert(handled == 42)
                }
            }
        }

        "debug(prefix)" - {
            "with boolean value" in run {
                val effect = Sync(true)
                effect.map { handled =>
                    assert(handled == true)
                }
            }
            "with string value" in run {
                val effect = Sync("test")
                effect.map { handled =>
                    assert(handled == "test")
                }
            }
        }

        "discard" - {
            "with integer value" in run {
                val effect          = Sync(23)
                val effectDiscarded = effect.unit
                effectDiscarded.map { handled =>
                    assert(handled == ())
                }
            }
            "with string value" in run {
                val effect          = Sync("hello")
                val effectDiscarded = effect.unit
                effectDiscarded.map { handled =>
                    assert(handled == ())
                }
            }
        }

        "*>" - {
            "with string values" in run {
                val eff1   = Sync("hello")
                val eff2   = Sync("world")
                val zipped = eff1 *> eff2
                zipped.map { handled =>
                    assert(handled == "world")
                }
            }
            "with mixed types" in run {
                val eff1   = Sync(42)
                val eff2   = Sync("answer")
                val zipped = eff1 *> eff2
                zipped.map { handled =>
                    assert(handled == "answer")
                }
            }
        }

        "<*" - {
            "with string values" in run {
                val eff1   = Sync("hello")
                val eff2   = Sync("world")
                val zipped = eff1 <* eff2
                zipped.map { handled =>
                    assert(handled == "hello")
                }
            }
            "with mixed types" in run {
                val eff1   = Sync("answer")
                val eff2   = Sync(42)
                val zipped = eff1 <* eff2
                zipped.map { handled =>
                    assert(handled == "answer")
                }
            }
        }

        "<*>" - {
            "with string values" in run {
                val eff1   = Sync("hello")
                val eff2   = Sync("world")
                val zipped = eff1 <*> eff2
                zipped.map { handled =>
                    assert(handled == ("hello", "world"))
                }
            }
            "with mixed types" in run {
                val eff1   = Sync(42)
                val eff2   = Sync("answer")
                val zipped = eff1 <*> eff2
                zipped.map { handled =>
                    assert(handled == (42, "answer"))
                }
            }
        }

        "when" - {
            "condition is false" in run {
                var state: Boolean = false
                val toggleState = Sync {
                    state = !state
                }
                val getState   = Sync(state)
                val effectWhen = (toggleState *> getState).when(getState)
                effectWhen.map { handledEffectWhen =>
                    assert(handledEffectWhen == Absent)
                }
            }
            "condition is true" in run {
                var state: Boolean = true
                val toggleState = Sync {
                    state = !state
                }
                val getState   = Sync(state)
                val effectWhen = (toggleState *> getState).when(getState)
                effectWhen.map { handledEffectWhen =>
                    assert(handledEffectWhen == Present(false))
                }
            }
        }

        "unless" - {
            "condition is true" in run {
                val effect = Sync("value").unless(Env.get[Boolean])
                Env.run(true) {
                    effect
                }.map { result =>
                    assert(result == Absent)
                }
            }
            "condition is false" in run {
                val effect = Sync("value").unless(Env.get[Boolean])
                Env.run(false) {
                    effect
                }.map { result =>
                    assert(result == Present("value"))
                }
            }
        }

        "tap" - {
            "with integer value" in run {
                val effect: Int < Sync = Sync(42).tap(v => assert(42 == v))
                effect.map { handled =>
                    assert(handled == 42)
                }
            }
            "with string value" in run {
                val effect: String < Sync = Sync("test").tap(v => assert("test" == v))
                effect.map { handled =>
                    assert(handled == "test")
                }
            }
        }

        "delay" - {
            "with short delay" in run {
                val effect = Sync(42).delay(1.millis)
                Async.run(effect).map(_.toFuture).map { handled =>
                    handled.map(v => assert(v == 42))
                }
            }
            "with zero delay" in run {
                val effect = Sync("test").delay(0.millis)
                Async.run(effect).map(_.toFuture).map { handled =>
                    handled.map(v => assert(v == "test"))
                }
            }
        }

        "repeat" - {
            "repeat with fixed number" - {
                "repeat 3 times" in run {
                    var count  = 0
                    val effect = Sync { count += 1; count }.repeat(3)
                    effect.map { handled =>
                        assert(handled == 4)
                        assert(count == 4)
                    }
                }
                "repeat 0 times" in run {
                    var count  = 0
                    val effect = Sync { count += 1; count }.repeat(0)
                    effect.map { handled =>
                        assert(handled == 1)
                        assert(count == 1)
                    }
                }
            }

            "repeat with policy" - {
                "repeat with custom policy" in run {
                    var count    = 0
                    val schedule = Schedule.repeat(3)
                    val effect   = Sync { count += 1; count }.repeatAtInterval(schedule)
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
                    val effect  = Sync { count += 1; count }.repeatAtInterval(backoff, 3)
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
                    val effect = Sync { count += 1; count }.repeatWhile(_ < 3)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 3))
                    }
                }
                "condition is initially false" in run {
                    var count  = 5
                    val effect = Sync { count += 1; count }.repeatWhile(_ < 3)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 6))
                    }
                }
            }

            "repeatWhile with condition and duration" - {
                "condition becomes false with delay" in run {
                    var count  = 0
                    val effect = Sync { count += 1; count }.repeatWhile((v, i) => (v < 3, 10.millis))
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
                    val effect = Sync { count += 1; count }.repeatUntil(_ == 3)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 3))
                    }
                }
                "condition is initially true" in run {
                    var count  = 0
                    val effect = Sync { count += 1; count }.repeatUntil(_ => true)
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 1))
                    }
                }
            }

            "repeatUntil with condition and duration" - {
                "condition becomes true with delay" in run {
                    var count  = 0
                    val effect = Sync { count += 1; count }.repeatUntil((v, i) => (v == 3, 10.millis))
                    Async.run(effect).map(_.toFuture).map { handled =>
                        handled.map(v => assert(v == 3))
                    }
                }
            }
        }

        "unpanic" - {
            "with throwable" in run {
                val effect: Nothing < (Abort[Throwable] & Sync)     = Sync { Abort.fail(Exception("failure")) }
                val panicked: Nothing < Sync                        = effect.orPanic
                val unpanicked: Nothing < (Abort[Throwable] & Sync) = panicked.unpanic
                Abort.run[Throwable](unpanicked).map: handled =>
                    val msg = handled.failure.collect:
                        case thr: Throwable => thr.getMessage()
                    assert(msg.contains("failure"))
            }

            "with non-throwable failure" in run {
                val effect: Nothing < (Abort[String] & Sync)        = Sync { Abort.fail("failure") }
                val panicked: Nothing < Sync                        = effect.orPanic
                val unpanicked: Nothing < (Abort[Throwable] & Sync) = panicked.unpanic
                Abort.run[Throwable](unpanicked).map: handled =>
                    assert(handled == Result.Failure(PanicException("failure")))
            }
        }

        "ensuring" in run {
            var finalizerCalled = false
            Resource.run(Sync(()).ensuring(Sync { finalizerCalled = true }))
                .andThen(assert(finalizerCalled))
        }
    }
end KyoCombinatorsTest
