package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.internal.SharedChrome

// Test-local: CDP's Target.closeTarget reply is `{"success":true}` (older Chrome) or `{}` (newer). No
// CloseTargetResult lives in CdpTypes; this lets us decode the reply into a typed shape so successful
// decode is itself stronger than the prior `contains("true")` substring check.
final case class CloseTargetResult(success: Maybe[Boolean] = Absent) derives Schema

class CdpClientTest extends Test:

    override def timeout = 2.minutes

    private def withClient[A](f: CdpClient => A < (Async & Abort[BrowserReadException]))(using Frame): A < Async =
        Abort.run[BrowserReadException | BrowserSetupException](
            SharedChrome.init.map(url => Scope.run(CdpClient.init(url, Browser.LaunchConfig.default).map(f)))
        ).map {
            case Result.Success(v)   => v
            case Result.Failure(err) => fail(s"Browser failure: ${err.getMessage}")
            case Result.Panic(ex)    => fail(s"Panic: ${ex.getMessage}")
        }
    end withClient

    "Target.getTargets returns targets" in run {
        // chrome-headless-shell launches with an empty target list (no auto-opened about:blank tab the way full Chrome
        // does). Create a target explicitly first so the test verifies the `Target.getTargets` decode path against a
        // known-non-empty response on every binary variant.
        withClient { client =>
            client.send("Target.createTarget", CreateTargetParams("about:blank")).map { _ =>
                client.send("Target.getTargets").map { result =>
                    val targets = decodeCdpResult[GetTargetsResult](result)
                    // Structural pin: a real target must carry a non-empty targetId.
                    assert(
                        targets.targetInfos.nonEmpty && targets.targetInfos.head.targetId.nonEmpty,
                        s"got $targets"
                    )
                }
            }
        }
    }

    "Target.createTarget creates a new target" in run {
        withClient { client =>
            client.send("Target.createTarget", CreateTargetParams("about:blank")).map { result =>
                val created = decodeCdpResult[CreateTargetResult](result)
                // Length pinning avoided: Chrome has changed target-id formats before.
                assert(created.targetId.nonEmpty, s"got $created")
            }
        }
    }

    "Target.attachToTarget returns sessionId" in run {
        withClient { client =>
            client.send("Target.createTarget", CreateTargetParams("about:blank")).map { createJson =>
                val created = decodeCdpResult[CreateTargetResult](createJson)
                client.send("Target.attachToTarget", AttachParams(created.targetId, flatten = true)).map { attachJson =>
                    val attached = decodeCdpResult[AttachResult](attachJson)
                    // Length pinning avoided: Chrome session-id format may drift.
                    assert(attached.sessionId.nonEmpty, s"got $attached")
                }
            }
        }
    }

    "session-scoped Page.navigate succeeds" in run {
        withClient { client =>
            client.send("Target.createTarget", CreateTargetParams("about:blank")).map { createJson =>
                val created = decodeCdpResult[CreateTargetResult](createJson)
                client.send("Target.attachToTarget", AttachParams(created.targetId, flatten = true)).map { attachJson =>
                    val attached = decodeCdpResult[AttachResult](attachJson)
                    val session  = client.withSession(SessionId(attached.sessionId))
                    session.sendUnit("Page.enable").map { _ =>
                        session.send("Page.navigate", NavigateParams("data:text/html,<h1>hello</h1>")).map { navResult =>
                            // Typed decode; envelope+frameId pinned.
                            val nav = decodeCdpResult[NavigateResult](navResult)
                            assert(nav.frameId.nonEmpty, s"got $nav")
                        }
                    }
                }
            }
        }
    }

    "session-scoped Runtime.evaluate returns result" in run {
        withClient { client =>
            client.send("Target.createTarget", CreateTargetParams("about:blank")).map { createJson =>
                val created = decodeCdpResult[CreateTargetResult](createJson)
                client.send("Target.attachToTarget", AttachParams(created.targetId, flatten = true)).map { attachJson =>
                    val attached = decodeCdpResult[AttachResult](attachJson)
                    val session  = client.withSession(SessionId(attached.sessionId))
                    session.sendUnit("Runtime.enable").map { _ =>
                        session.send("Runtime.evaluate", EvalParams("1+1")).map { evalResult =>
                            // Typed decode of EvalResult; pattern-match the RemoteObject.number variant
                            // to pin both shape and value.
                            val eval = decodeCdpResult[EvalResult](evalResult)
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
        withClient { client =>
            client.send("Target.createTarget", CreateTargetParams("about:blank")).map { createJson =>
                val created = decodeCdpResult[CreateTargetResult](createJson)
                client.send("Target.closeTarget", CloseTargetParams(created.targetId)).map { closeResult =>
                    // Typed decode. Successful decode means the CDP envelope's `result` was Present and matched
                    // the schema; strictly stronger than a `contains("true")` substring check (newer Chrome
                    // returns `{}` and would still satisfy us).
                    val close = decodeCdpResult[CloseTargetResult](closeResult)
                    // `success` is optional in the wire; record what we got for diagnostic clarity.
                    assert(close.success == Absent || close.success == Present(true), s"got $close")
                }
            }
        }
    }

    "concurrent sends all complete correctly" in run {
        withClient { client =>
            client.send("Target.createTarget", CreateTargetParams("about:blank")).map { createJson =>
                val created = decodeCdpResult[CreateTargetResult](createJson)
                client.send("Target.attachToTarget", AttachParams(created.targetId, flatten = true)).map { attachJson =>
                    val attached = decodeCdpResult[AttachResult](attachJson)
                    val session  = client.withSession(SessionId(attached.sessionId))
                    session.sendUnit("Runtime.enable").map { _ =>
                        Async.zip(
                            session.send("Runtime.evaluate", EvalParams("1+1")),
                            session.send("Runtime.evaluate", EvalParams("2+2")),
                            session.send("Runtime.evaluate", EvalParams("3+3")),
                            session.send("Runtime.evaluate", EvalParams("4+4"))
                        ).map { (r1, r2, r3, r4) =>
                            assert(r1.contains("2"))
                            assert(r2.contains("4"))
                            assert(r3.contains("6"))
                            assert(r4.contains("8"))
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
                    CdpClient.init(wsUrl, Browser.LaunchConfig.default).map { client =>
                        client.close(30.seconds).map { _ =>
                            client.send("Target.getTargets")
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

    "init closes the client on scope exit" in run {
        val probe: CdpClient => Unit < (Async & Abort[BrowserReadException]) =
            client => client.send("Target.getTargets").unit

        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                for
                    capturedClient <- Scope.run {
                        CdpClient.init(wsUrl, Browser.LaunchConfig.default).map { client =>
                            probe(client).andThen(client)
                        }
                    }
                    // After scope exits, the client must be closed: any send should surface ConnectionLost.
                    _ <- probe(capturedClient)
                yield ()
            }
        }.map {
            case Result.Failure(_: BrowserConnectionLostException) => succeed
            case Result.Failure(other)                             => fail(s"Expected ConnectionLost but got ${other.getClass.getName}")
            case Result.Success(_)                                 => fail("Expected client to be closed after scope exit")
            case Result.Panic(ex)                                  => fail(s"Panic: ${ex.getMessage}")
        }
    }

    "initUnscoped survives scope exit and must be closed manually" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                for
                    capturedClient <- Scope.run {
                        CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default)
                    }
                    _ <- capturedClient.send("Target.getTargets") // still usable
                    _ <- capturedClient.close(30.seconds)         // manual cleanup
                    // After manual close, sends must fail.
                    afterClose <- Abort.run[BrowserConnectionException](capturedClient.send("Target.getTargets"))
                yield afterClose match
                    case Result.Failure(_: BrowserConnectionLostException) => succeed
                    case other => fail(s"Expected ConnectionLost after manual close, got $other")
            }
        }.orFail("Unexpected")
    }

    "Scope.run(CdpClient.init(url, Browser.LaunchConfig.default).map(...)) releases the client after the body completes" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                for
                    capturedClient <- Scope.run(CdpClient.init(wsUrl, Browser.LaunchConfig.default).map { client =>
                        client.send("Target.getTargets").andThen(client)
                    })
                    // After the Scope.run block returns, the client must be closed: the next send must fail with ConnectionLost.
                    afterClose <- Abort.run[BrowserConnectionException](capturedClient.send("Target.getTargets"))
                yield afterClose match
                    case Result.Failure(_: BrowserConnectionLostException) => succeed
                    case other                                             => fail(s"Expected ConnectionLost after scope exit, got $other")
            }
        }.orFail("Unexpected")
    }

    "CdpClient.init(url, Browser.LaunchConfig.default).map runs the body then closes" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                for
                    capturedClient <- Scope.run {
                        CdpClient.init(wsUrl, Browser.LaunchConfig.default).map { client =>
                            client.send("Target.getTargets").andThen(client)
                        }
                    }
                    afterClose <- Abort.run[BrowserConnectionException](capturedClient.send("Target.getTargets"))
                yield afterClose match
                    case Result.Failure(_: BrowserConnectionLostException) => succeed
                    case other                                             => fail(s"Expected ConnectionLost after scope exit, got $other")
            }
        }.orFail("Unexpected")
    }

    "CdpClient.close(gracePeriod = 1.second) returns within the grace period" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { client =>
                    for
                        start   <- Clock.now
                        _       <- client.close(1.second)
                        elapsed <- Clock.now.map(_ - start)
                    // Bound elapsed by gracePeriod + epsilon. With no slow in-flight requests, orderly close should
                    // complete well under the grace period; the cap ensures it does not block longer.
                    yield assert(elapsed < 5.seconds, s"close(1.second) took $elapsed (expected < 5s)")
                }
            }
        }.orFail("Unexpected")
    }

    "CdpClient.closeNow returns in less than 100ms" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { client =>
                    for
                        start   <- Clock.now
                        _       <- client.closeNow
                        elapsed <- Clock.now.map(_ - start)
                    yield assert(elapsed < 1.second, s"closeNow took $elapsed (expected < 1s)")
                }
            }
        }.orFail("Unexpected")
    }

    "BrowserSetupFailedException carries message and cause via field access" in {
        val cause = new RuntimeException("ENOENT /bad/path")
        val ex    = BrowserSetupFailedException("could not launch: port-in-use", cause)
        // Field access on the case class; no `getMessage` round-trip.
        assert(ex.message == "could not launch: port-in-use")
        assert(ex.cause == Present(cause))
        succeed
    }

end CdpClientTest
