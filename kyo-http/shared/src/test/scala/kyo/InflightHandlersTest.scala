package kyo

import kyo.internal.server.InflightHandlers

class InflightHandlersTest extends BaseHttpTest:

    "initializing handlers requires unsafe permission at the call site" in {
        typeCheckFailure("""InflightHandlers.init()""")("AllowUnsafe")
        typeCheck("""Sync.Unsafe.defer(InflightHandlers.init())""")
    }

    "starting a handler requires unsafe permission at the call site" in {
        typeCheckFailure("""
            def start(owner: InflightHandlers)(using Frame) = owner.start((), false)
        """)("AllowUnsafe")
        typeCheck("""
            def start(owner: InflightHandlers)(using Frame, AllowUnsafe) = owner.start((), false)
        """)
    }

    "closing handlers requires unsafe permission at the call site" in {
        typeCheckFailure("""
            def close(owner: InflightHandlers)(using Frame) = owner.close()
        """)("AllowUnsafe")
        typeCheck("""
            def close(owner: InflightHandlers)(using Frame, AllowUnsafe) = owner.close()
        """)
    }

    "closed owner refuses a handler without evaluating it" in {
        for
            owner  <- Sync.Unsafe.defer(InflightHandlers.init())
            _      <- Sync.Unsafe.defer(owner.close().safe).map(_.get)
            result <- Sync.Unsafe.defer(owner.start(Sync.defer(assert(false, "refused handler ran")), false))
        yield assert(result.isEmpty)
    }

    "close interrupts sessions and is idempotent" in {
        for
            entered   <- Latch.init(1)
            finalized <- Fiber.Promise.init[Unit, Any]
            owner     <- Sync.Unsafe.defer(InflightHandlers.init())
            handler   <- Sync.Unsafe.defer {
                owner.start(Sync.ensure(finalized.completeUnitDiscard)(entered.release.andThen(Async.never)), true).get.safe
            }
            _         <- Scope.ensure(Sync.Unsafe.defer(owner.close().safe).map(_.get))
            _         <- entered.await
            _         <- Sync.Unsafe.defer(owner.close().safe).map(_.get)
            result    <- handler.getResult
            completed <- finalized.done
            _         <- Sync.Unsafe.defer(owner.close().safe).map(_.get)
        yield
            result match
                case Result.Panic(error) => assert(error eq kyo.internal.server.UnsafeServerDispatch.HandlerConnectionClosed)
                case _                   => fail(s"Expected the server-close interruption, got $result")
            assert(completed)
    }

    "close waits for every connection-owned handler" in {
        Scope.run {
            for
                first                      <- Fiber.Promise.init[Unit, Any]
                second                     <- Fiber.Promise.init[Unit, Any]
                _                          <- Scope.ensure(first.completeUnitDiscard.andThen(second.completeUnitDiscard))
                owner                      <- Sync.Unsafe.defer(InflightHandlers.init())
                one                        <- Sync.Unsafe.defer(owner.start(first.get, false).get.safe)
                two                        <- Sync.Unsafe.defer(owner.start(second.get, false).get.safe)
                closing                    <- Sync.Unsafe.defer(owner.close().safe)
                _                          <- first.completeUnitDiscard
                _                          <- one.get
                completedWithSecondPending <- closing.done
                _                          <- second.completeUnitDiscard
                _                          <- two.get
                _                          <- closing.get
            yield assert(!completedWithSecondPending)
        }
    }

    "a normally completed handler drains its scope before close" in {
        for
            finalized <- Fiber.Promise.init[Unit, Any]
            owner     <- Sync.Unsafe.defer(InflightHandlers.init())
            handler   <- Sync.Unsafe.defer {
                owner.start(Scope.run(Scope.ensure(finalized.completeUnitDiscard)), false).get.safe
            }
            _         <- handler.get
            _         <- Sync.Unsafe.defer(owner.close().safe).map(_.get)
            completed <- finalized.done
        yield assert(completed)
    }

    "close waits for interrupted handler scopes to drain" in {
        Scope.run {
            for
                entered          <- Latch.init(1)
                finalizerEntered <- Latch.init(1)
                release          <- Fiber.Promise.init[Unit, Any]
                finalized        <- Fiber.Promise.init[Unit, Any]
                _                <- Scope.ensure(release.completeUnitDiscard)
                owner            <- Sync.Unsafe.defer(InflightHandlers.init())
                handler          <- Sync.Unsafe.defer {
                    owner.start(
                        Scope.run {
                            Scope.ensure(finalizerEntered.release.andThen(release.get).andThen(finalized.completeUnitDiscard))
                                .andThen(entered.release)
                                .andThen(Async.never)
                        },
                        false
                    ).get.safe
                }
                _                      <- Scope.ensure(handler.interruptAwait.andThen(Sync.Unsafe.defer(owner.close().safe).map(_.get)))
                _                      <- entered.await
                _                      <- handler.interruptAwait
                _                      <- finalizerEntered.await
                closing                <- Sync.Unsafe.defer(owner.close().safe)
                completedBeforeRelease <- closing.done
                _                      <- release.completeUnitDiscard
                _                      <- finalized.get
                _                      <- closing.get
            yield assert(!completedBeforeRelease, "server drain completed while a handler scope was still finalizing")
        }
    }

    "close joins the scopes of owned handler children" in {
        Scope.run {
            for
                entered          <- Latch.init(1)
                finalizerEntered <- Latch.init(1)
                release          <- Fiber.Promise.init[Unit, Any]
                finalized        <- Fiber.Promise.init[Unit, Any]
                _                <- Scope.ensure(release.completeUnitDiscard)
                owner            <- Sync.Unsafe.defer(InflightHandlers.init())
                handler          <- Sync.Unsafe.defer {
                    owner.start(
                        Fiber.init {
                            Scope.run {
                                Scope.ensure(finalizerEntered.release.andThen(release.get).andThen(finalized.completeUnitDiscard))
                                    .andThen(entered.release)
                                    .andThen(Async.never)
                            }
                        }.andThen(Async.never),
                        false
                    ).get.safe
                }
                _                      <- Scope.ensure(handler.interruptAwait.andThen(Sync.Unsafe.defer(owner.close().safe).map(_.get)))
                _                      <- entered.await
                _                      <- handler.interruptAwait
                _                      <- finalizerEntered.await
                closing                <- Sync.Unsafe.defer(owner.close().safe)
                completedBeforeRelease <- closing.done
                _                      <- release.completeUnitDiscard
                _                      <- finalized.get
                _                      <- closing.get
            yield assert(!completedBeforeRelease)
        }
    }

end InflightHandlersTest
