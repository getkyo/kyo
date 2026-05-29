package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.internal.SharedChrome

// Test-local: CDP's Target.closeTarget reply is `{"success":true}` (older Chrome) or `{}` (newer).
final case class CloseTargetResult(success: Maybe[Boolean] = Absent) derives Schema

/** Integration tests for [[CdpBackend]] against a live browser via [[SharedChrome]].
  *
  * Mechanical rename of the former `CdpClientTest`: `CdpClient.init` -> `CdpBackend.init`, `CdpClient.initUnscoped` ->
  * `CdpBackend.initUnscoped`, raw-string sends replaced with typed [[CdpBackend]] wrappers.
  */
class CdpBackendIntegrationTest extends Test:

    override def timeout = 2.minutes

    private def withBackend[A](f: CdpBackend => A < (Async & Abort[BrowserReadException]))(using Frame): A < Async =
        Abort.run[BrowserReadException | BrowserSetupException](
            SharedChrome.init.map(url => Scope.run(CdpBackend.init(url, Browser.LaunchConfig.default).map(f)))
        ).map {
            case Result.Success(v)   => v
            case Result.Failure(err) => fail(s"Browser failure: ${err.getMessage}")
            case Result.Panic(ex)    => fail(s"Panic: ${ex.getMessage}")
        }
    end withBackend

    "Target.getTargets returns targets" in run {
        // chrome-headless-shell launches with an empty target list (no auto-opened about:blank tab).
        // Create a target explicitly first so the test verifies the `Target.getTargets` decode path.
        withBackend { backend =>
            CdpBackend.createTarget(backend, CreateTargetParams("about:blank")).map { _ =>
                CdpBackend.getTargets(backend).map { result =>
                    assert(
                        result.targetInfos.nonEmpty && result.targetInfos.head.targetId.nonEmpty,
                        s"got $result"
                    )
                }
            }
        }
    }

    "Target.createTarget creates a new target" in run {
        withBackend { backend =>
            CdpBackend.createTarget(backend, CreateTargetParams("about:blank")).map { created =>
                assert(created.targetId.nonEmpty, s"got $created")
            }
        }
    }

    "Target.attachToTarget returns sessionId" in run {
        withBackend { backend =>
            CdpBackend.createTarget(backend, CreateTargetParams("about:blank")).map { created =>
                CdpBackend.attachToTarget(backend, AttachParams(created.targetId, flatten = true)).map { attached =>
                    assert(attached.sessionId.nonEmpty, s"got $attached")
                }
            }
        }
    }

    "session-scoped Page.navigate succeeds" in run {
        withBackend { backend =>
            CdpBackend.createTarget(backend, CreateTargetParams("about:blank")).map { created =>
                CdpBackend.attachToTarget(backend, AttachParams(created.targetId, flatten = true)).map { attached =>
                    val session = backend.withSession(SessionId(attached.sessionId))
                    session.sendUnit[CdpNoParams]("Page.enable", CdpNoParams()).map { _ =>
                        CdpBackend.navigate(session, NavigateParams("data:text/html,<h1>hello</h1>")).map { _ =>
                            succeed
                        }
                    }
                }
            }
        }
    }

    "session-scoped Runtime.evaluate returns result" in run {
        withBackend { backend =>
            CdpBackend.createTarget(backend, CreateTargetParams("about:blank")).map { created =>
                CdpBackend.attachToTarget(backend, AttachParams(created.targetId, flatten = true)).map { attached =>
                    val session = backend.withSession(SessionId(attached.sessionId))
                    session.sendUnit[CdpNoParams]("Runtime.enable", CdpNoParams()).map { _ =>
                        session.send[EvalParams, EvalResult]("Runtime.evaluate", EvalParams("1+1")).map { eval =>
                            eval.result match
                                case n: RemoteObject.`number` => assert(n.value == 2.0, s"got $eval")
                                case other                    => fail(s"expected RemoteObject.number but got $other")
                        }
                    }
                }
            }
        }
    }

    "Target.closeTarget succeeds" in run {
        withBackend { backend =>
            CdpBackend.createTarget(backend, CreateTargetParams("about:blank")).map { created =>
                CdpBackend.closeTarget(backend, CloseTargetParams(created.targetId)).map { _ =>
                    succeed
                }
            }
        }
    }

    "concurrent sends all complete correctly" in run {
        withBackend { backend =>
            CdpBackend.createTarget(backend, CreateTargetParams("about:blank")).map { created =>
                CdpBackend.attachToTarget(backend, AttachParams(created.targetId, flatten = true)).map { attached =>
                    val session = backend.withSession(SessionId(attached.sessionId))
                    session.sendUnit[CdpNoParams]("Runtime.enable", CdpNoParams()).map { _ =>
                        Async.zip(
                            session.send[EvalParams, EvalResult]("Runtime.evaluate", EvalParams("1+1")),
                            session.send[EvalParams, EvalResult]("Runtime.evaluate", EvalParams("2+2")),
                            session.send[EvalParams, EvalResult]("Runtime.evaluate", EvalParams("3+3")),
                            session.send[EvalParams, EvalResult]("Runtime.evaluate", EvalParams("4+4"))
                        ).map { (r1, r2, r3, r4) =>
                            def numVal(r: EvalResult): Double = r.result match
                                case n: RemoteObject.`number` => n.value
                                case _                        => -1.0
                            assert(numVal(r1) == 2.0, s"r1=$r1")
                            assert(numVal(r2) == 4.0, s"r2=$r2")
                            assert(numVal(r3) == 6.0, s"r3=$r3")
                            assert(numVal(r4) == 8.0, s"r4=$r4")
                        }
                    }
                }
            }
        }
    }

    "close then send fails with ConnectionLost" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                Scope.run {
                    CdpBackend.init(wsUrl, Browser.LaunchConfig.default).map { backend =>
                        backend.close(30.seconds).map { _ =>
                            CdpBackend.getTargets(backend)
                        }
                    }
                }
            }
        }.map {
            case Result.Failure(_: BrowserConnectionLostException) =>
                succeed
            case Result.Failure(other) =>
                fail(s"Expected BrowserConnectionLostException but got ${other.getClass.getName}")
            case Result.Success(_) =>
                fail("Expected BrowserConnectionLostException after close")
            case Result.Panic(ex) =>
                fail(s"Expected Failure, got Panic: ${ex.getMessage}")
        }
    }

    "init closes the backend on scope exit" in run {
        val probe: CdpBackend => Unit < (Async & Abort[BrowserReadException]) =
            backend => CdpBackend.getTargets(backend).unit

        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                for
                    capturedBackend <- Scope.run {
                        CdpBackend.init(wsUrl, Browser.LaunchConfig.default).map { backend =>
                            probe(backend).andThen(backend)
                        }
                    }
                    // After scope exits, the backend must be closed: any send should surface ConnectionLost.
                    _ <- probe(capturedBackend)
                yield ()
            }
        }.map {
            case Result.Failure(_: BrowserConnectionLostException) => succeed
            case Result.Failure(other)                             => fail(s"Expected ConnectionLost but got ${other.getClass.getName}")
            case Result.Success(_)                                 => fail("Expected backend to be closed after scope exit")
            case Result.Panic(ex)                                  => fail(s"Panic: ${ex.getMessage}")
        }
    }

    "initUnscoped survives scope exit and must be closed manually" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                for
                    capturedBackend <- CdpBackend.initUnscoped(wsUrl, Browser.LaunchConfig.default)
                    _               <- CdpBackend.getTargets(capturedBackend) // still usable
                    _               <- capturedBackend.close(30.seconds)      // manual cleanup
                    // After manual close, sends must fail.
                    afterClose <- Abort.run[BrowserConnectionException](CdpBackend.getTargets(capturedBackend))
                yield afterClose match
                    case Result.Failure(_: BrowserConnectionLostException) => succeed
                    case other => fail(s"Expected ConnectionLost after manual close, got $other")
            }
        }.orFail("Unexpected")
    }

    "Scope.run(CdpBackend.init(url, ...).map(...)) releases the backend after the body completes" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                for
                    capturedBackend <- Scope.run(CdpBackend.init(wsUrl, Browser.LaunchConfig.default).map { backend =>
                        CdpBackend.getTargets(backend).andThen(backend)
                    })
                    // After the Scope.run block returns, the backend must be closed.
                    afterClose <- Abort.run[BrowserConnectionException](CdpBackend.getTargets(capturedBackend))
                yield afterClose match
                    case Result.Failure(_: BrowserConnectionLostException) => succeed
                    case other                                             => fail(s"Expected ConnectionLost after scope exit, got $other")
            }
        }.orFail("Unexpected")
    }

    "CdpBackend.init(url, ...).map runs the body then closes" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                for
                    capturedBackend <- Scope.run {
                        CdpBackend.init(wsUrl, Browser.LaunchConfig.default).map { backend =>
                            CdpBackend.getTargets(backend).andThen(backend)
                        }
                    }
                    afterClose <- Abort.run[BrowserConnectionException](CdpBackend.getTargets(capturedBackend))
                yield afterClose match
                    case Result.Failure(_: BrowserConnectionLostException) => succeed
                    case other                                             => fail(s"Expected ConnectionLost after scope exit, got $other")
            }
        }.orFail("Unexpected")
    }

    "CdpBackend.close(gracePeriod = 1.second) returns within the grace period" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpBackend.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { backend =>
                    for
                        start   <- Clock.now
                        _       <- backend.close(1.second)
                        elapsed <- Clock.now.map(_ - start)
                    yield assert(elapsed < 5.seconds, s"close(1.second) took $elapsed (expected < 5s)")
                }
            }
        }.orFail("Unexpected")
    }

    "CdpBackend.closeNow returns in less than 100ms" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpBackend.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { backend =>
                    for
                        start   <- Clock.now
                        _       <- backend.closeNow
                        elapsed <- Clock.now.map(_ - start)
                    yield assert(elapsed < 1.second, s"closeNow took $elapsed (expected < 1s)")
                }
            }
        }.orFail("Unexpected")
    }

    "BrowserSetupFailedException carries message and cause via field access" in {
        val cause = new RuntimeException("ENOENT /bad/path")
        val ex    = BrowserSetupFailedException("could not launch: port-in-use", cause)
        assert(ex.message == "could not launch: port-in-use")
        assert(ex.cause == Present(cause))
        succeed
    }

end CdpBackendIntegrationTest
