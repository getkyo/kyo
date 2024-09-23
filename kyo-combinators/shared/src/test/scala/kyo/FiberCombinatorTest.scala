package kyo

class FiberCombinatorTest extends Test:

    "fibers" - {
        "construct" - {
            "should generate fibers effect from async" in {
                var state: Int = 0
                val effect = Kyo.async[Int]((continuation) =>
                    state = state + 1
                    continuation(state)
                )
                val handledEffect = IO.run(Async.run(effect).map(_.toFuture)).eval
                handledEffect.map(v =>
                    assert(state == 1)
                    assert(v == 1)
                )
            }

            "should construct from Future" in {
                val future        = scala.concurrent.Future(100)
                val effect        = Kyo.fromFuture(future)
                val handledEffect = IO.run(Async.run(effect).map(_.toFuture)).eval
                handledEffect.map(v =>
                    assert(v == 100)
                )
            }

            "should construct from Promise" in {
                val promise = scala.concurrent.Promise[Int]
                val effect  = Kyo.fromPromiseScala(promise)
                scala.concurrent.Future {
                    promise.complete(scala.util.Success(100))
                }
                val handledEffect = IO.run(Async.run(effect).map(_.toFuture))
                handledEffect.eval.map(v => assert(v == 100))
            }

            "should construct from foreachPar" in {
                val effect        = Kyo.foreachPar(Seq(1, 2, 3))(v => v * 2)
                val handledEffect = IO.run(Async.run(effect).map(_.toFuture)).eval
                handledEffect.map(v => assert(v == Seq(2, 4, 6)))
            }

            "should construct from traversePar" in {
                val effect        = Kyo.traversePar(Seq(IO(1), IO(2), IO(3)))
                val handledEffect = IO.run(Async.run(effect).map(_.toFuture)).eval
                handledEffect.map(v => assert(v == Seq(1, 2, 3)))
            }

            "should generate a fiber that doesn't complete using never" in {
                val effect = Kyo.never
                runJVM {
                    val handledEffect = IO.run(Abort.run[Throwable] {
                        val r = Async.runAndBlock(5.millis)(effect)
                        Abort.catching[Throwable](r)
                    })
                    assert(handledEffect.eval match
                        case Result.Fail(_: Timeout) => true
                        case _                       => false
                    )
                }
            }
        }

        "fork" - {
            "should fork a fibers effect" in {
                val effect       = Async.sleep(100.millis) *> 10
                val forkedEffect = effect.fork
                val joinedEffect = forkedEffect.map(_.get)
                val handled      = IO.run(Async.run(joinedEffect).map(_.toFuture)).eval
                handled.map(v => assert(v == 10))
            }

            "should join a forked effect" in {
                val effect       = Async.sleep(100.millis) *> 10
                val forkedEffect = Async.run(effect)
                val joinedEffect = forkedEffect.join
                val handled      = IO.run(Async.run(joinedEffect).map(_.toFuture)).eval
                handled.map(v => assert(v == 10))
            }

            "should construct from type and use" in {
                val effect = Kyo.serviceWith[String](_.length)
                assert(Env.run("value")(effect).eval == 5)
            }
        }

        "zip par" - {
            "should zip right par" in {
                val e1      = IO(1)
                val e2      = IO(2)
                val effect  = e1 &> e2
                val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                handled.map(v =>
                    assert(v == 2)
                )
            }

            "should zip left par" in {
                val e1      = IO(1)
                val e2      = IO(2)
                val effect  = e1 <& e2
                val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                handled.map(v =>
                    assert(v == 1)
                )
            }

            "should zip par" in {
                val e1      = IO(1)
                val e2      = IO(2)
                val effect  = e1 <&> e2
                val handled = IO.run(Async.run(effect).map(_.toFuture)).eval
                handled.map(v =>
                    assert(v == (1, 2))
                )
            }
        }
        "forkScoped" - {
            "should fork a fiber and manage its lifecycle" in {
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

                val handledEffect = IO.run(Async.run(Resource.run(program)).map(_.toFuture)).eval
                handledEffect.map(v =>
                    assert(state == 1)
                    assert(v == 1)
                )
            }

            "should clean up resources when scope is closed" in {
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

                val handledEffect = IO.run(Async.run(Resource.run(program)).map(_.toFuture)).eval
                handledEffect.map(v =>
                    assert(v == 42)
                    assert(cleanedUp)
                )
            }
        }

        "awaitCompletion" - {

            "should wait for fiber completion without returning result" in {
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

                val handledEffect = IO.run(Async.run(program).map(_.toFuture)).eval
                handledEffect.map(v =>
                    assert(v)
                )
            }

            "should not propagate fiber result" in {
                val effect = Kyo.async[Int]((continuation) =>
                    continuation(42)
                )

                val program =
                    for
                        fiber <- effect.fork
                        _     <- fiber.awaitCompletion
                    yield ()

                val handledEffect = IO.run(Async.run(program).map(_.toFuture)).eval
                handledEffect.map(v =>
                    assert(v == ())
                )
            }
        }
    }
end FiberCombinatorTest
