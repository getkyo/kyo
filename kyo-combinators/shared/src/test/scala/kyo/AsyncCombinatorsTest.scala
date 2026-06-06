package kyo

class AsyncCombinatorsTest extends kyo.test.Test[Any]:

    // The `Kyo.fromFuture` / `Kyo.fromPromiseScala` construction tests build scala.concurrent.Futures, which need an
    // ExecutionContext. The ScalaTest base provided one; kyo-test does not, so supply the same cross-platform EC.
    given scala.concurrent.ExecutionContext = kyo.internal.Platform.executionContext

    "async" - {
        "construct" - {
            "should generate Async effect from async" in {
                var state: Int = 0
                val effect = Kyo.async[Int, Nothing]((continuation) =>
                    val cont = Sync.defer { state = state + 1; state }
                    continuation(cont)
                )
                effect.map { v =>
                    assert(state == 1 && v == 1)
                }
            }

            "should generate failing Async effect from async" in {
                var state: Int = 0
                val effect = Kyo.async[Int, String]((continuation) =>
                    continuation(Abort.fail("failed"))
                )
                Abort.run(effect).map:
                    case Result.Success(value) => fail(s"Unexpectedly succeeded with value $value")
                    case Result.Failure(err)   => assert(err == "failed")
                    case Result.Panic(thr)     => fail(s"Unexpectedly panic with exception $thr")
            }

            "should construct from Future" in {
                val future = scala.concurrent.Future(100)
                val effect = Kyo.fromFuture(future)
                effect.map(v =>
                    assert(v == 100)
                )
            }

            "should construct from Promise" in {
                val promise = scala.concurrent.Promise[Int]()
                val effect  = Kyo.fromPromiseScala(promise)
                scala.concurrent.Future {
                    promise.complete(scala.util.Success(100))
                }
                effect.map(v => assert(v == 100))
            }

            "should construct from foreachPar" in {
                val effect = Kyo.foreachPar(Seq(1, 2, 3))(v => v * 2)
                effect.map(v => assert(v == Seq(2, 4, 6)))
            }

            "should construct from collectAllPar" in {
                val effect = Kyo.collectAllPar(Seq(Sync.defer(1), Sync.defer(2), Sync.defer(3)))
                effect.map(v => assert(v == Seq(1, 2, 3)))
            }

            "should generate a fiber that doesn't complete using never".onlyJvm in {
                val effect = Kyo.never
                Abort.run[Throwable] {
                    val r = KyoApp.runAndBlock(5.millis)(effect)
                    Abort.catching[Throwable](r)
                }.map { handledEffect =>
                    assert(handledEffect match
                        case Result.Failure(_: Timeout) => true
                        case _                          => false)
                }
            }
        }

        "forkUnscoped" - {
            "should fork a fibers effect" in {
                val effect       = Async.sleep(100.millis) *> 10
                val forkedEffect = effect.forkUnscoped
                val joinedEffect = forkedEffect.map(_.get)
                joinedEffect.map(v => assert(v == 10))
            }

            "should join a forked effect" in {
                val effect       = Async.sleep(100.millis) *> 10
                val forkedEffect = Fiber.initUnscoped(effect)
                val joinedEffect = forkedEffect.join
                joinedEffect.map(v => assert(v == 10))
            }
        }

        "zip par" - {
            "should zip right par" in {
                val e1     = Sync.defer(1)
                val e2     = Sync.defer(2)
                val effect = e1 &> e2
                effect.map(v =>
                    assert(v == 2)
                )
            }

            "should zip left par" in {
                val e1     = Sync.defer(1)
                val e2     = Sync.defer(2)
                val effect = e1 <& e2
                effect.map(v =>
                    assert(v == 1)
                )
            }

            "should zip par" in {
                val e1     = Sync.defer(1)
                val e2     = Sync.defer(2)
                val effect = e1 <&> e2
                effect.map(v =>
                    assert(v == (1, 2))
                )
            }
        }
        "fork" - {
            "should fork a fiber and manage its lifecycle" in {
                var state = 0
                val effect = Kyo.async[Int, Nothing]((continuation) =>
                    state = state + 1
                    continuation(state)
                )

                val program =
                    for
                        fiber  <- effect.fork
                        result <- fiber.join
                    yield result

                Scope.run(program).map(v =>
                    assert(state == 1 && v == 1)
                )
            }

            "should clean up resources when scope is closed" in {
                var cleanedUp = false
                val effect = Kyo.async[Int, Nothing]((continuation) =>
                    continuation(42)
                )

                val program =
                    for
                        fiber  <- effect.fork
                        _      <- Scope.acquireRelease(())(_ => cleanedUp = true)
                        result <- fiber.join
                    yield result

                Scope.run(program).map { v =>
                    assert(v == 42 && cleanedUp)
                }
            }
        }

        "await" - {

            "should wait for fiber completion" in {
                var completed = false
                val effect = Kyo.async[Int, Nothing](continuation =>
                    completed = true
                    continuation(42)
                )

                val program =
                    for
                        fiber  <- effect.forkUnscoped
                        result <- fiber.await
                    yield result

                program.map { v =>
                    assert(v == Result.succeed(42) && completed)
                }
            }
        }
    }
end AsyncCombinatorsTest
