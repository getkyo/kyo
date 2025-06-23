package kyo

class FiberCombinatorsTest extends Test:

    "fibers" - {
        "construct" - {
            "should generate fibers effect from async" in run {
                var state: Int = 0
                val effect = Kyo.async[Int]((continuation) =>
                    state = state + 1
                    continuation(state)
                )
                Async.run(effect).map(_.toFuture).map { handledEffect =>
                    handledEffect.map(v =>
                        assert(state == 1)
                        assert(v == 1)
                    )
                }
            }

            "should construct from Future" in run {
                val future = scala.concurrent.Future(100)
                val effect = Kyo.fromFuture(future)
                Async.run(effect).map(_.toFuture).map { handledEffect =>
                    handledEffect.map(v =>
                        assert(v == 100)
                    )
                }
            }

            "should construct from Promise" in run {
                val promise = scala.concurrent.Promise[Int]
                val effect  = Kyo.fromPromiseScala(promise)
                scala.concurrent.Future {
                    promise.complete(scala.util.Success(100))
                }
                Async.run(effect).map(_.toFuture).map { handledEffect =>
                    handledEffect.map(v => assert(v == 100))
                }
            }

            "should construct from foreachPar" in run {
                val effect = Kyo.foreachPar(Seq(1, 2, 3))(v => v * 2)
                Async.run(effect).map(_.toFuture).map { handledEffect =>
                    handledEffect.map(v => assert(v == Seq(2, 4, 6)))
                }
            }

            "should construct from traversePar" in run {
                val effect = Kyo.traversePar(Seq(Sync(1), Sync(2), Sync(3)))
                Async.run(effect).map(_.toFuture).map { handledEffect =>
                    handledEffect.map(v => assert(v == Seq(1, 2, 3)))
                }
            }

            "should generate a fiber that doesn't complete using never" in run {
                val effect = Kyo.never
                runJVM {
                    Abort.run[Throwable] {
                        val r = Async.runAndBlock(5.millis)(effect)
                        Abort.catching[Throwable](r)
                    }.map { handledEffect =>
                        assert(handledEffect match
                            case Result.Failure(_: Timeout) => true
                            case _                          => false)
                    }
                }
            }
        }

        "fork" - {
            "should fork a fibers effect" in run {
                val effect       = Async.sleep(100.millis) *> 10
                val forkedEffect = effect.fork
                val joinedEffect = forkedEffect.map(_.get)
                Async.run(joinedEffect).map(_.toFuture).map { handled =>
                    handled.map(v => assert(v == 10))
                }
            }

            "should join a forked effect" in run {
                val effect       = Async.sleep(100.millis) *> 10
                val forkedEffect = Async.run(effect)
                val joinedEffect = forkedEffect.join
                Async.run(joinedEffect).map(_.toFuture).map { handled =>
                    handled.map(v => assert(v == 10))
                }
            }

            "should construct from type and use" in {
                val effect = Kyo.serviceWith[String](_.length)
                assert(Env.run("value")(effect).eval == 5)
            }
        }

        "zip par" - {
            "should zip right par" in run {
                val e1     = Sync(1)
                val e2     = Sync(2)
                val effect = e1 &> e2
                Async.run(effect).map(_.toFuture).map { handled =>
                    handled.map(v =>
                        assert(v == 2)
                    )
                }
            }

            "should zip left par" in run {
                val e1     = Sync(1)
                val e2     = Sync(2)
                val effect = e1 <& e2
                Async.run(effect).map(_.toFuture).map { handled =>
                    handled.map(v =>
                        assert(v == 1)
                    )
                }
            }

            "should zip par" in run {
                val e1     = Sync(1)
                val e2     = Sync(2)
                val effect = e1 <&> e2
                Async.run(effect).map(_.toFuture).map { handled =>
                    handled.map(v =>
                        assert(v == (1, 2))
                    )
                }
            }
        }
        "forkScoped" - {
            "should fork a fiber and manage its lifecycle" in run {
                var state = 0
                val effect = Kyo.async[Int]((continuation) =>
                    state = state + 1
                    continuation(state)
                )

                val program =
                    for
                        fiber  <- effect.forkScoped
                        result <- fiber.join
                    yield result

                Async.run(Resource.run(program)).map(_.toFuture).map { handledEffect =>
                    handledEffect.map(v =>
                        assert(state == 1)
                        assert(v == 1)
                    )
                }
            }

            "should clean up resources when scope is closed" in run {
                var cleanedUp = false
                val effect = Kyo.async[Int]((continuation) =>
                    continuation(42)
                )

                val program =
                    for
                        fiber  <- effect.forkScoped
                        _      <- Resource.acquireRelease(())(_ => cleanedUp = true)
                        result <- fiber.join
                    yield result

                Async.run(Resource.run(program)).map(_.toFuture).map { handledEffect =>
                    handledEffect.map(v =>
                        assert(v == 42)
                        assert(cleanedUp)
                    )
                }
            }
        }

        "awaitCompletion" - {

            "should wait for fiber completion without returning result" in run {
                var completed = false
                val effect = Kyo.async[Int](continuation =>
                    completed = true
                    continuation(42)
                )

                val program =
                    for
                        fiber <- effect.fork
                        _     <- fiber.awaitCompletion
                    yield completed

                Async.run(program).map(_.toFuture).map { handledEffect =>
                    handledEffect.map(v =>
                        assert(v)
                    )
                }
            }

            "should not propagate fiber result" in run {
                val effect = Kyo.async[Int]((continuation) =>
                    continuation(42)
                )

                val program =
                    for
                        fiber <- effect.fork
                        _     <- fiber.awaitCompletion
                    yield ()

                Async.run(program).map(_.toFuture).map { handledEffect =>
                    handledEffect.map(v =>
                        assert(v == ())
                    )
                }
            }
        }
    }
end FiberCombinatorsTest
