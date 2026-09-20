package kyo

class AsyncCombinatorsTest extends kyo.test.Test[Any]:

    // The `Kyo.fromFuture` / `Kyo.fromPromiseScala` construction tests build scala.concurrent.Futures, which need an
    // ExecutionContext. The ScalaTest base provided one; kyo-test does not, so supply the same cross-platform EC.
    given scala.concurrent.ExecutionContext = kyo.internal.Platform.executionContext

    "async" - {
        "construct" - {
            "should generate Async effect from async" in {
                var state: Int = 0
                val effect     = Kyo.async[Int, Nothing]((continuation) =>
                    val cont = Sync.defer { state = state + 1; state }
                    continuation(cont)
                )
                effect.map { v =>
                    assert(state == 1 && v == 1)
                }
            }

            "should generate failing Async effect from async" in {
                var state: Int = 0
                val effect     = Kyo.async[Int, String]((continuation) =>
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

        "async" - {
            // `Kyo.async` spawns the effect handed to its continuation with Fiber.initUnscoped, which parents nothing,
            // and the caller parks on a promise that fiber completes. An interrupt of the caller abandons it and
            // nothing reaches the spawned fiber: it runs on, holding whatever it acquired, until it ends by itself.
            "interrupting the caller of async interrupts the effect it registered".pendingUntilFixed(
                "Kyo.async spawns the registered effect with Fiber.initUnscoped and never links it to the caller, so an interrupted caller leaves it running unowned"
            ) in {
                for
                    gate     <- Latch.init(1)
                    entered  <- Latch.init(1)
                    released <- AtomicBoolean.init(false)
                    fiber    <- Fiber.initUnscoped {
                        Kyo.async[Int, Nothing] { register =>
                            Sync.defer(register(Sync.ensure(released.set(true))(entered.release.andThen(gate.await).andThen(1))))
                        }
                    }
                    _ <- entered.await
                    _ <- fiber.interrupt
                    _ <- fiber.getResult
                    r <- Abort.run[Timeout](Async.timeout(2.seconds)(assertEventually(released.get)))
                    _ <- gate.release
                yield assert(r.isSuccess, "the registered effect was orphaned: its finalizer did not run once the caller was interrupted")
                end for
            }
        }
        "fork" - {
            "should fork a fiber and manage its lifecycle" in {
                var state  = 0
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
                val effect    = Kyo.async[Int, Nothing]((continuation) =>
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
                val effect    = Kyo.async[Int, Nothing](continuation =>
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
