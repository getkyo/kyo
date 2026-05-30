package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.BrowserElementNotActionableException.Reason
import kyo.BrowserIFrameInvalidException.Reason as IFrameReason
import kyo.JsonRpcEndpoint.IdStrategy
import kyo.internal.SharedChrome

/** [[CdpBackend]] lifecycle, close, and connection tests.
  *
  * Mechanical rename of the former `CdpClientLifecycleTest`: `CdpClient.init/initUnscoped` -> `CdpBackend.init/initUnscoped`,
  * `tab.client` -> `tab.backend`, raw-string sends replaced with typed [[CdpBackend]] wrappers. Tests that exercised `CdpClient`-specific
  * internals (relay fiber, inFlight counter, drainSignal) are adapted to the equivalent [[CdpBackend]] / [[JsonRpcEndpoint]] APIs.
  */
class CdpBackendLifecycleTest extends kyo.BrowserTest:

    override def timeout = 2.minutes

    // ─────────────────────────────────────────────────────────────────────────
    // initUnscoped(url).map(f) invokes f without auto-closing on exit
    // ─────────────────────────────────────────────────────────────────────────

    "initUnscoped + .map invokes f with the backend" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                for
                    capturedBackend <- CdpBackend.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { backend =>
                        // Verify the backend is live (f is invoked with a working backend).
                        CdpBackend.getTargets(backend).andThen(backend)
                    }
                    // After initUnscoped + .map returns, f has completed but the backend is NOT
                    // automatically closed; initUnscoped does not close on exit.
                    stillAlive <- Abort.run[BrowserConnectionException](CdpBackend.getTargets(capturedBackend))
                    _          <- capturedBackend.close(30.seconds) // manual cleanup
                yield stillAlive match
                    case Result.Success(_) => succeed
                    case Result.Failure(err) =>
                        fail(s"initUnscoped + .map closed the backend unexpectedly: ${err.getMessage}")
                    case Result.Panic(ex) => fail(s"Panic: ${ex.getMessage}")
            }
        }.orFail("Unexpected BrowserConnectionException")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // close() idempotency
    // ─────────────────────────────────────────────────────────────────────────

    "close() is idempotent - second call returns without exception" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpBackend.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { backend =>
                    for
                        // First close; should succeed.
                        _ <- backend.close(30.seconds)
                        // Second close; must not throw, hang, or panic.
                        _ <- backend.close(30.seconds)
                    yield succeed
                }
            }
        }.orFail("Unexpected exception on second close")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WS connection failure on init
    // ─────────────────────────────────────────────────────────────────────────

    "initUnscoped with bad URL fails fast with BrowserSetupException or BrowserConnectionLostException" in run {
        // initUnscoped waits for the WebSocket connect to resolve before returning the backend (the
        // Q-002 probe gate), so an unreachable URL surfaces as Abort failure from init
        // itself rather than blocking the first send. 127.0.0.1:0 is OS-rejected immediately.
        Abort.run[BrowserReadException | BrowserSetupException | BrowserConnectionException] {
            Scope.run(CdpBackend.initUnscoped("ws://127.0.0.1:0/", Browser.LaunchConfig.default).map(_ => ()))
        }.map {
            case Result.Failure(_: BrowserSetupException) =>
                succeed
            case Result.Failure(_: BrowserConnectionException) =>
                succeed
            case Result.Failure(_: BrowserReadException) =>
                succeed
            case other =>
                fail(s"Expected fast Abort.Failure from initUnscoped on unreachable URL, got $other")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // close(Duration.Zero) short-circuit
    // ─────────────────────────────────────────────────────────────────────────

    "close(Duration.Zero) closes immediately and subsequent send raises ConnectionLost" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpBackend.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { backend =>
                    // close(Duration.Zero) hits the zero-duration short-circuit branch.
                    backend.close(Duration.Zero).andThen {
                        // Behavioural pair: subsequent send must fail with ConnectionLost.
                        Abort.run[BrowserConnectionException](CdpBackend.getTargets(backend))
                    }
                }
            }
        }.map {
            case Result.Success(innerResult) =>
                innerResult match
                    case Result.Failure(_: BrowserConnectionLostException) =>
                        succeed
                    case Result.Failure(other) =>
                        fail(s"Expected BrowserConnectionLostException after close(Duration.Zero) but got ${other.getClass.getName}")
                    case Result.Success(_) =>
                        fail("Expected ConnectionLost after close(Duration.Zero) but send succeeded")
                    case Result.Panic(ex) =>
                        fail(s"Panic from inner send: ${ex.getMessage}")
            case Result.Failure(err) =>
                fail(s"Unexpected outer failure: ${err.getMessage}")
            case Result.Panic(ex) =>
                fail(s"Outer panic: ${ex.getMessage}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // closeOrderly drains in-flight
    // ─────────────────────────────────────────────────────────────────────────

    "closeOrderly (close with grace) completes in-flight send before returning" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpBackend.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { backend =>
                    for
                        created  <- CdpBackend.createTarget(backend, CreateTargetParams("about:blank"))
                        attached <- CdpBackend.attachToTarget(backend, AttachParams(created.targetId, flatten = true))
                        session = backend.withSession(SessionId(attached.sessionId))
                        _ <- session.sendUnit[CdpNoParams]("Runtime.enable", CdpNoParams())

                        // Slow in-flight send: 1-second setTimeout Promise + awaitPromise=true.
                        slowFiber <- Fiber.initUnscoped {
                            CdpBackend.runtimeEvaluate(
                                session,
                                EvalParams(
                                    expression = "new Promise((r) => setTimeout(r, 1000))",
                                    awaitPromise = true
                                )
                            )
                        }

                        // Settle so the send is actually registered before close starts polling.
                        _ <- Async.delay(50.millis)(Kyo.unit)

                        // close(5s) must wait for the 1-second send to drain.
                        timedClose <- timed(Async.timeout(10.seconds)(backend.close(5.seconds)))
                        (elapsed, _) = timedClose
                        fiberResult <- slowFiber.getResult
                    yield fiberResult match
                        case Result.Success(_) =>
                            assert(
                                elapsed >= 900.millis,
                                s"close(5s) returned in $elapsed; expected >=900ms - drain did not wait"
                            )
                            succeed
                        case Result.Failure(_: BrowserConnectionLostException) =>
                            fail(
                                "close(5.seconds) did NOT drain in-flight send: slow send received " +
                                    s"BrowserConnectionLostException after $elapsed (expected Result.Success)"
                            )
                        case Result.Failure(other) =>
                            fail(s"Unexpected slow-send failure: ${other.getClass.getName}: ${other.getMessage}")
                        case Result.Panic(ex) =>
                            fail(s"Panic on slow send: ${ex.getMessage}")
                    end for
                }
            }
        }.orFail("Outer connection")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // closeNow with in-flight
    // ─────────────────────────────────────────────────────────────────────────

    "closeNow while a slow in-flight send is pending surfaces ConnectionLost (or transport-closed ProtocolError) to the caller" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpBackend.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { backend =>
                    for
                        // Set up a session so we can issue a Runtime.evaluate with awaitPromise.
                        created  <- CdpBackend.createTarget(backend, CreateTargetParams("about:blank"))
                        attached <- CdpBackend.attachToTarget(backend, AttachParams(created.targetId, flatten = true))
                        session = backend.withSession(SessionId(attached.sessionId))
                        _ <- session.sendUnit[CdpNoParams]("Runtime.enable", CdpNoParams())
                        // Launch the slow in-flight send: a Promise that resolves in 30 seconds.
                        slowFiber <- Fiber.initUnscoped {
                            CdpBackend.runtimeEvaluate(
                                session,
                                EvalParams(
                                    expression = "new Promise((r) => setTimeout(r, 30000))",
                                    awaitPromise = true
                                )
                            )
                        }
                        // The slow send is guaranteed in-flight (30s timeout); call closeNow immediately.
                        _           <- backend.closeNow
                        fiberResult <- slowFiber.getResult
                    yield fiberResult match
                        case Result.Failure(_: BrowserConnectionLostException) =>
                            succeed
                        case Result.Failure(e: BrowserProtocolErrorException)
                            if e.error.contains("transport closed") || e.error.contains("endpoint closed") =>
                            // JS: engine surfaces close as JsonRpcError("transport closed"); JVM: maps to Closed -> ConnectionLost.
                            // Both are valid outcomes for a closeNow racing an in-flight send.
                            succeed
                        case Result.Success(v) =>
                            fail(s"Expected close-induced abort but send succeeded with: $v")
                        case other =>
                            fail(s"Expected ConnectionLost or transport-closed ProtocolError, got: $other")
                }
            }
        }.orFail("Unexpected outer")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // cross-session routing
    // ─────────────────────────────────────────────────────────────────────────

    "concurrent sends on two sessions route to the correct session without cross-talk" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                Scope.run {
                    CdpBackend.init(wsUrl, Browser.LaunchConfig.default).map { backend =>
                        for
                            // Create two targets.
                            t1 <- CdpBackend.createTarget(backend, CreateTargetParams("about:blank"))
                            t2 <- CdpBackend.createTarget(backend, CreateTargetParams("about:blank"))
                            a1 <- CdpBackend.attachToTarget(backend, AttachParams(t1.targetId, flatten = true))
                            a2 <- CdpBackend.attachToTarget(backend, AttachParams(t2.targetId, flatten = true))
                            sessionA = backend.withSession(SessionId(a1.sessionId))
                            sessionB = backend.withSession(SessionId(a2.sessionId))
                            _ <- sessionA.sendUnit[CdpNoParams]("Runtime.enable", CdpNoParams())
                            _ <- sessionB.sendUnit[CdpNoParams]("Runtime.enable", CdpNoParams())
                            // Concurrent sends on both sessions via Async.zip.
                            // runtimeEvaluate returns the CdpReply-wrapped JSON string; check for the value.
                            (rA, rB) <- Async.zip(
                                CdpBackend.runtimeEvaluate(sessionA, EvalParams("'result-A'")),
                                CdpBackend.runtimeEvaluate(sessionB, EvalParams("'result-B'"))
                            )
                            _ <- CdpBackend.closeTarget(backend, CloseTargetParams(t1.targetId))
                            _ <- CdpBackend.closeTarget(backend, CloseTargetParams(t2.targetId))
                        yield
                            assert(rA.contains("result-A"), s"Session A got wrong result: $rA")
                            assert(rB.contains("result-B"), s"Session B got wrong result: $rB")
                            succeed
                    }
                }
            }
        }.orFail("Unexpected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // nested withSession
    // ─────────────────────────────────────────────────────────────────────────

    "nested withSession routes inner sends to inner session and outer sends to outer session" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                Scope.run {
                    CdpBackend.init(wsUrl, Browser.LaunchConfig.default).map { backend =>
                        for
                            // Create two targets.
                            t1 <- CdpBackend.createTarget(backend, CreateTargetParams("about:blank"))
                            t2 <- CdpBackend.createTarget(backend, CreateTargetParams("about:blank"))
                            a1 <- CdpBackend.attachToTarget(backend, AttachParams(t1.targetId, flatten = true))
                            a2 <- CdpBackend.attachToTarget(backend, AttachParams(t2.targetId, flatten = true))
                            outer = backend.withSession(SessionId(a1.sessionId))
                            inner = outer.withSession(SessionId(a2.sessionId))
                            _ <- outer.sendUnit[CdpNoParams]("Runtime.enable", CdpNoParams())
                            _ <- inner.sendUnit[CdpNoParams]("Runtime.enable", CdpNoParams())
                            // inner.send should carry sessionId = a2.sessionId
                            innerResult <- CdpBackend.runtimeEvaluate(inner, EvalParams("'inner-session'"))
                            // outer.send should carry sessionId = a1.sessionId
                            outerResult <- CdpBackend.runtimeEvaluate(outer, EvalParams("'outer-session'"))
                            _           <- CdpBackend.closeTarget(backend, CloseTargetParams(t1.targetId))
                            _           <- CdpBackend.closeTarget(backend, CloseTargetParams(t2.targetId))
                        yield
                            assert(inner.sessionId == Present(SessionId(a2.sessionId)), s"inner.sessionId mismatch: ${inner.sessionId}")
                            assert(outer.sessionId == Present(SessionId(a1.sessionId)), s"outer.sessionId mismatch: ${outer.sessionId}")
                            assert(innerResult.contains("inner-session"), s"inner session returned wrong result: $innerResult")
                            assert(outerResult.contains("outer-session"), s"outer session returned wrong result: $outerResult")
                            succeed
                    }
                }
            }
        }.orFail("Unexpected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // dialog drainer survives queue close on closeNow
    // ─────────────────────────────────────────────────────────────────────────

    "dialogDrainer fiber is done after closeNow" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpBackend.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { backend =>
                    // Capture reference to dialogDrainer fiber before closing.
                    val drainer = backend.dialogDrainer
                    for
                        // Verify drainer is alive before close.
                        aliveBefore <- drainer.done
                        _           <- backend.closeNow
                        // After closeNow, dialogDrainer must be interrupted/done.
                        // Poll briefly to allow fiber to settle (interrupt is async).
                        _          <- Async.delay(50.millis)(Kyo.unit)
                        aliveAfter <- drainer.done
                    yield
                        assert(!aliveBefore, s"dialogDrainer should be running before close, but done=$aliveBefore")
                        assert(aliveAfter, s"dialogDrainer should be done after closeNow, but done=$aliveAfter")
                        succeed
                    end for
                }
            }
        }.orFail("Unexpected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frame-context tracker
    // ─────────────────────────────────────────────────────────────────────────

    "attachTab seeds frameContexts with the root frame's default executionContextId before user code runs" in run {
        Scope.run {
            Abort.run[BrowserConnectionException] {
                withBrowser {
                    Browser.use { tab =>
                        for
                            ctxMap    <- tab.frameContexts.get
                            rootMaybe <- tab.rootFrameId.get
                        yield
                            assert(rootMaybe != Absent, s"rootFrameId should be seeded by attachTab but was Absent")
                            rootMaybe match
                                case Present(rid) =>
                                    assert(
                                        ctxMap.contains(rid),
                                        s"frameContexts must contain the root frame id $rid but had keys ${ctxMap.toMap.keys.toList}"
                                    )
                                case Absent => fail("unreachable")
                            end match
                            succeed
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "iframe injected via document.write triggers Runtime.executionContextCreated and adds an entry to frameContexts" in run {
        Scope.run {
            Abort.run[BrowserConnectionException | BrowserNavigationException | BrowserAssertionException | BrowserScriptException] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            before <- tab.frameContexts.get
                            // Inject an iframe whose document inherits the parent origin (about:blank).
                            _ <- Browser.eval(
                                """(() => {
                                    const f = document.createElement('iframe');
                                    f.id = 'inj';
                                    f.src = 'about:blank';
                                    document.body.appendChild(f);
                                    return 'ok';
                                })()"""
                            )
                            // Poll until frameContexts grows by at least one entry.
                            _ <- Browser.waitFor(
                                "(window.__kyoIframeReady = (document.getElementById('inj') && document.getElementById('inj').contentDocument != null) ? '1' : '')"
                            )
                            after <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                tab.frameContexts.get.map { m =>
                                    if m.size > before.size then m
                                    else
                                        Abort.fail[BrowserAssertionException](
                                            BrowserAssertionTimedOutException("frameContexts grow", "size>before.size", s"size=${m.size}")
                                        )
                                }
                            }
                        yield
                            assert(
                                after.size > before.size,
                                s"Expected frameContexts to grow after iframe insertion. before.size=${before.size} after.size=${after.size}"
                            )
                            succeed
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "iframe removal triggers Runtime.executionContextDestroyed and removes the matching entry" in run {
        Scope.run {
            Abort.run[BrowserConnectionException | BrowserNavigationException | BrowserAssertionException | BrowserScriptException] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            // Insert an iframe and wait for the create event.
                            _ <- Browser.eval(
                                """(() => {
                                    const f = document.createElement('iframe');
                                    f.id = 'rm';
                                    f.src = 'about:blank';
                                    document.body.appendChild(f);
                                    return 'ok';
                                })()"""
                            )
                            withIframe <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                tab.frameContexts.get.map { m =>
                                    if m.size >= 2 then m
                                    else
                                        Abort.fail[BrowserAssertionException](
                                            BrowserAssertionTimedOutException("iframe create", "size>=2", s"size=${m.size}")
                                        )
                                }
                            }
                            // Remove the iframe.
                            _ <- Browser.eval(
                                """(() => {
                                    const f = document.getElementById('rm');
                                    if (f) f.remove();
                                    return 'ok';
                                })()"""
                            )
                            // Wait for the destroyed event to shrink the map.
                            after <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                tab.frameContexts.get.map { m =>
                                    if m.size < withIframe.size then m
                                    else
                                        Abort.fail[BrowserAssertionException](
                                            BrowserAssertionTimedOutException("iframe destroy", "size<withIframe.size", s"size=${m.size}")
                                        )
                                }
                            }
                        yield
                            assert(
                                after.size < withIframe.size,
                                s"Expected frameContexts to shrink after iframe removal. before.size=${withIframe.size} after.size=${after.size}"
                            )
                            succeed
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "isolated-world contexts (auxData.isDefault == false) do not pollute frameContexts" in run {
        Scope.run {
            Abort.run[BrowserConnectionException | BrowserNavigationException | BrowserAssertionException | BrowserScriptException] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            before    <- tab.frameContexts.get
                            rootMaybe <- tab.rootFrameId.get
                            _ <- rootMaybe match
                                case Present(rid) =>
                                    tab.backend.withSession(tab.sessionId).sendUnit(
                                        "Page.createIsolatedWorld",
                                        CreateIsolatedWorldParams(rid.value, "kyo-iso-test")
                                    )
                                case Absent => Kyo.unit
                            // Inject a same-origin iframe.
                            _ <- Browser.eval(
                                """(() => {
                                    const f = document.createElement('iframe');
                                    f.id = 'iso-probe';
                                    f.src = 'about:blank';
                                    document.body.appendChild(f);
                                    return 'ok';
                                })()"""
                            )
                            after <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                tab.frameContexts.get.map { m =>
                                    if m.size > before.size then m
                                    else
                                        Abort.fail[BrowserAssertionException](
                                            BrowserAssertionTimedOutException(
                                                "iframe main-world create",
                                                "size>before.size",
                                                s"size=${m.size}"
                                            )
                                        )
                                }
                            }
                        yield
                            assert(
                                after.size == before.size + 1,
                                s"frameContexts must grow by exactly one after iframe insertion " +
                                    s"(isolated-world contexts must NOT enter the map). " +
                                    s"before=$before after=$after"
                            )
                            rootMaybe match
                                case Present(rid) =>
                                    assert(
                                        after.get(rid) == before.get(rid),
                                        s"root frame's executionContextId must be unchanged. rid=$rid before=${before.get(rid)} after=${after.get(rid)}"
                                    )
                                case Absent => fail("rootFrameId must be seeded by attachTab")
                            end match
                            succeed
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "Page.getFrameTree returns the root frame plus every same-origin child frame after page settle" in run {
        Scope.run {
            Abort.run[BrowserConnectionException | BrowserNavigationException | BrowserAssertionException | BrowserScriptException] {
                withBrowserOnLocalhost {
                    // Insert two iframes and wait for them to settle.
                    Browser.eval(
                        """(() => {
                            const f1 = document.createElement('iframe');
                            f1.src = 'about:blank';
                            document.body.appendChild(f1);
                            const f2 = document.createElement('iframe');
                            f2.src = 'about:blank';
                            document.body.appendChild(f2);
                            return 'ok';
                        })()"""
                    ).andThen {
                        Browser.use { tab =>
                            for
                                _ <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                    tab.frameContexts.get.map { m =>
                                        if m.size >= 3 then m
                                        else
                                            Abort.fail[BrowserAssertionException](
                                                BrowserAssertionTimedOutException("two iframes", "size>=3", s"size=${m.size}")
                                            )
                                    }
                                }
                                tree <- CdpBackend.getFrameTree(tab.backend.withSession(tab.sessionId))
                            yield
                                val rootId   = tree.frameTree.frame.id
                                val children = tree.frameTree.childFrames.getOrElse(Seq.empty)
                                assert(rootId.nonEmpty, "root frame id must be non-empty")
                                assert(
                                    children.size == 2,
                                    s"Expected exactly 2 child frames in tree but got ${children.size}: $children"
                                )
                                children.foreach { c =>
                                    assert(
                                        c.frame.parentId.contains(rootId),
                                        s"child frame ${c.frame.id} must have parentId == $rootId but was ${c.frame.parentId}"
                                    )
                                }
                                succeed
                            end for
                        }
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "iframe added after page load and then removed leaves no leaked map keys" in run {
        Scope.run {
            Abort.run[BrowserConnectionException | BrowserNavigationException | BrowserAssertionException | BrowserScriptException] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            initial <- tab.frameContexts.get
                            // Insert an iframe.
                            _ <- Browser.eval(
                                """(() => {
                                    const f = document.createElement('iframe');
                                    f.id = 'leak';
                                    f.src = 'about:blank';
                                    document.body.appendChild(f);
                                    return 'ok';
                                })()"""
                            )
                            // Wait for the create.
                            withIframe <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                tab.frameContexts.get.map { m =>
                                    if m.size > initial.size then m
                                    else
                                        Abort.fail[BrowserAssertionException](
                                            BrowserAssertionTimedOutException("iframe create", "size>initial", s"size=${m.size}")
                                        )
                                }
                            }
                            _ <- Browser.eval(
                                """(() => {
                                    document.getElementById('leak').remove();
                                    return 'ok';
                                })()"""
                            )
                            // Wait for the destroy.
                            after <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                tab.frameContexts.get.map { m =>
                                    if m.size <= initial.size then m
                                    else
                                        Abort.fail[BrowserAssertionException](
                                            BrowserAssertionTimedOutException("iframe destroy", "size<=initial", s"size=${m.size}")
                                        )
                                }
                            }
                        yield
                            assert(
                                after.toMap.keySet == initial.toMap.keySet,
                                s"After create+destroy the frameContexts keys must match initial. initial=${initial.toMap.keySet} after=${after.toMap.keySet}"
                            )
                            succeed
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "concurrent tabs share a connection but maintain independent frameContexts maps" in run {
        Scope.run {
            Abort.run[BrowserConnectionException | BrowserNavigationException | BrowserAssertionException | BrowserScriptException] {
                withBrowserOnLocalhost {
                    // First, inject an iframe into the parent tab so its frameContexts grows.
                    Browser.eval(
                        """(() => {
                            const f = document.createElement('iframe');
                            f.id = 'tab1-iframe';
                            f.src = 'about:blank';
                            document.body.appendChild(f);
                            return 'ok';
                        })()"""
                    ).andThen {
                        Browser.use { firstTab =>
                            for
                                _ <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                    firstTab.frameContexts.get.map { m =>
                                        if m.size >= 2 then m
                                        else
                                            Abort.fail[BrowserAssertionException](
                                                BrowserAssertionTimedOutException("tab1 iframe", "size>=2", s"size=${m.size}")
                                            )
                                    }
                                }
                                // Open a second tab in the same connection.
                                tabsCompared <- Browser.withNewTab {
                                    Browser.use { secondTab =>
                                        for
                                            // Wait for tab2's tracker to observe its own root context create event.
                                            _ <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                                secondTab.frameContexts.get.map { m =>
                                                    if m.nonEmpty then m
                                                    else
                                                        Abort.fail[BrowserAssertionException](
                                                            BrowserAssertionTimedOutException("tab2 root ctx", "nonEmpty", "empty")
                                                        )
                                                }
                                            }
                                            firstMap  <- firstTab.frameContexts.get
                                            secondMap <- secondTab.frameContexts.get
                                        yield
                                            assert(
                                                firstTab.sessionId.value != secondTab.sessionId.value,
                                                "tabs must have distinct session ids"
                                            )
                                            assert(
                                                firstMap.nonEmpty,
                                                s"first tab must have at least one frame context, got ${firstMap.size}"
                                            )
                                            assert(
                                                secondMap.nonEmpty,
                                                s"second tab must have at least one frame context, got ${secondMap.size}"
                                            )
                                            val firstStd  = firstMap.toMap
                                            val secondStd = secondMap.toMap
                                            assert(
                                                firstStd.values.toSet.intersect(secondStd.values.toSet).isEmpty,
                                                s"tabs must have disjoint executionContextIds. first=${firstStd.values.toSet} second=${secondStd.values.toSet}"
                                            )
                                            assert(
                                                firstStd.keySet.intersect(secondStd.keySet).isEmpty,
                                                s"tabs must have disjoint frameIds. first=${firstStd.keySet} second=${secondStd.keySet}"
                                            )
                                            assert(
                                                firstMap.size > secondMap.size,
                                                s"tab1 has the iframe so its map should be larger than tab2. first=${firstMap.size} second=${secondMap.size}"
                                            )
                                            succeed
                                    }
                                }
                            yield tabsCompared
                            end for
                        }
                    }
                }
            }.orFail("Unexpected")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frame-context threading
    // ─────────────────────────────────────────────────────────────────────────

    "evalJs without an active frame scope evaluates against the top-level execution context" in run {
        Scope.run {
            Abort.run[BrowserConnectionException | BrowserIFrameException | BrowserNavigationException] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            _           <- tab.backend.lastEvaluateParams.set(Absent)
                            _           <- BrowserEval.evalJs("window.location.href")
                            recordedOpt <- tab.backend.lastEvaluateParams.get
                        yield
                            val recorded = recordedOpt match
                                case Present(value) => value
                                case Absent         => fail("no Runtime.evaluate params recorded")
                            val params = decode[EvalParams](recorded)
                            assert(
                                params.contextId.isEmpty,
                                s"Expected contextId Absent but recorded params were '$recorded' (decoded: $params)"
                            )
                            succeed
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "evalJs inside an active withFrame scope evaluates against the iframe's execution context" in run {
        Scope.run {
            Abort.run[
                BrowserConnectionException | BrowserIFrameException | BrowserNavigationException | BrowserAssertionException |
                    BrowserScriptException
            ] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            _ <- Browser.eval(
                                """(() => {
                                    const f = document.createElement('iframe');
                                    f.id = 'iframe-frame';
                                    f.src = 'about:blank';
                                    document.body.appendChild(f);
                                    return 'ok';
                                })()"""
                            )
                            ctxMap <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                tab.frameContexts.get.map { m =>
                                    if m.size >= 2 then m
                                    else
                                        Abort.fail[BrowserAssertionException](
                                            BrowserAssertionTimedOutException("iframe ctx", "size>=2", s"size=${m.size}")
                                        )
                                }
                            }
                            rootMaybe <- tab.rootFrameId.get
                            iframeEntry = ctxMap.find { case (fid, _) => Present(fid) != rootMaybe } match
                                case Present(value) => value
                                case Absent         => fail(s"no iframe entry: rootMaybe=$rootMaybe map=$ctxMap")
                            handle = IFrameHandle(iframeEntry._1, iframeEntry._2)
                            _ <- tab.backend.lastEvaluateParams.set(Absent)
                            inFrameUrl <- Browser.activeIFrameLocal.let(Present(handle): Maybe[IFrameHandle]) {
                                BrowserEval.evalJs("window.location.href")
                            }
                            insideRecordedOpt  <- tab.backend.lastEvaluateParams.get
                            _                  <- tab.backend.lastEvaluateParams.set(Absent)
                            outsideUrl         <- BrowserEval.evalJs("window.location.href")
                            outsideRecordedOpt <- tab.backend.lastEvaluateParams.get
                        yield
                            assert(
                                inFrameUrl == "about:blank",
                                s"Expected iframe-scoped url to be 'about:blank' but was '$inFrameUrl'"
                            )
                            assert(
                                outsideUrl.contains("/json/version"),
                                s"Expected top-frame url after exit to contain '/json/version' but was '$outsideUrl'"
                            )
                            val insideRecorded = insideRecordedOpt match
                                case Present(value) => value
                                case Absent         => fail("no inside Runtime.evaluate params recorded")
                            val expectedCtx  = handle.executionContextId.value
                            val insideParams = decode[EvalParams](insideRecorded)
                            insideParams.contextId match
                                case Present(n) =>
                                    assert(
                                        n == expectedCtx,
                                        s"Expected contextId=$expectedCtx but recorded params were '$insideRecorded' (decoded: $insideParams)"
                                    )
                                case Absent =>
                                    fail(s"Expected contextId Present inside the let but recorded params were '$insideRecorded'")
                            end match
                            val outsideRecorded = outsideRecordedOpt match
                                case Present(value) => value
                                case Absent         => fail("no outside Runtime.evaluate params recorded")
                            val outsideParams = decode[EvalParams](outsideRecorded)
                            assert(
                                outsideParams.contextId.isEmpty,
                                s"Expected contextId Absent outside the let but recorded params were '$outsideRecorded' (decoded: $outsideParams)"
                            )
                            succeed
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "Resolver.resolveOne reads the active contextId and resolves elements inside the active frame" in run {
        Scope.run {
            Abort.run[
                BrowserConnectionException | BrowserIFrameException | BrowserNavigationException | BrowserAssertionException |
                    BrowserScriptException | BrowserElementException
            ] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            _ <- Browser.eval(
                                """(() => {
                                    const f = document.createElement('iframe');
                                    f.id = 'iframe-resolver-frame';
                                    f.src = 'about:blank';
                                    document.body.appendChild(f);
                                    f.contentDocument.body.innerHTML =
                                        '<div id="iframe-only-in-iframe">marker</div>';
                                    return 'ok';
                                })()"""
                            )
                            ctxMap <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                tab.frameContexts.get.map { m =>
                                    if m.size >= 2 then m
                                    else
                                        Abort.fail[BrowserAssertionException](
                                            BrowserAssertionTimedOutException("iframe ctx", "size>=2", s"size=${m.size}")
                                        )
                                }
                            }
                            rootMaybe <- tab.rootFrameId.get
                            iframeEntry = ctxMap.find { case (fid, _) => Present(fid) != rootMaybe } match
                                case Present(value) => value
                                case Absent         => fail("no iframe entry")
                            handle = IFrameHandle(iframeEntry._1, iframeEntry._2)
                            outsideRef <- Resolver.resolveOne(Browser.Selector.id("iframe-only-in-iframe"))
                            insideRef <- Browser.activeIFrameLocal.let(Present(handle): Maybe[IFrameHandle]) {
                                Resolver.resolveOne(Browser.Selector.id("iframe-only-in-iframe"))
                            }
                        yield
                            assert(
                                outsideRef == Absent,
                                s"Resolver in main scope must not see the iframe-only marker but got $outsideRef"
                            )
                            assert(
                                insideRef != Absent,
                                s"Resolver in frame scope must find the iframe-only marker but got $insideRef"
                            )
                            succeed
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "Actionability.check reads the active contextId and gates on the iframe's element" in run {
        Scope.run {
            Abort.run[
                BrowserConnectionException | BrowserIFrameException | BrowserNavigationException | BrowserAssertionException |
                    BrowserScriptException | BrowserElementException
            ] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            _ <- Browser.eval(
                                """(() => {
                                    const f = document.createElement('iframe');
                                    f.id = 'iframe-action-frame';
                                    f.src = 'about:blank';
                                    document.body.appendChild(f);
                                    f.contentDocument.body.innerHTML =
                                        '<button id="iframe-iframe-button" style="width:80px;height:30px">Click</button>';
                                    return 'ok';
                                })()"""
                            )
                            ctxMap <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                tab.frameContexts.get.map { m =>
                                    if m.size >= 2 then m
                                    else
                                        Abort.fail[BrowserAssertionException](
                                            BrowserAssertionTimedOutException("iframe ctx", "size>=2", s"size=${m.size}")
                                        )
                                }
                            }
                            rootMaybe <- tab.rootFrameId.get
                            iframeEntry = ctxMap.find { case (fid, _) => Present(fid) != rootMaybe } match
                                case Present(value) => value
                                case Absent         => fail("no iframe entry")
                            handle = IFrameHandle(iframeEntry._1, iframeEntry._2)
                            outside <- Actionability.check(
                                Browser.Selector.id("iframe-iframe-button"),
                                requireFillable = false,
                                requireEnabled = true
                            )
                            inside <- Browser.activeIFrameLocal.let(Present(handle): Maybe[IFrameHandle]) {
                                Actionability.check(
                                    Browser.Selector.id("iframe-iframe-button"),
                                    requireFillable = false,
                                    requireEnabled = true
                                )
                            }
                        yield
                            assert(
                                outside == Result.Failure(Reason.NotAttached),
                                s"Actionability in main scope must report NotAttached for the iframe-only button, got $outside"
                            )
                            assert(
                                inside.isSuccess,
                                s"Actionability in frame scope must return Success for the iframe button, got $inside"
                            )
                            succeed
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "evalJs against a destroyed execution context aborts with BrowserIFrameInvalidException" in run {
        Scope.run {
            Abort.run[
                BrowserConnectionException | BrowserNavigationException | BrowserAssertionException | BrowserScriptException
            ] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            rootMaybe <- tab.rootFrameId.get
                            staleFid = rootMaybe match
                                case Present(value) => value
                                case Absent         => fail("no root frame")
                            staleHandle = IFrameHandle(staleFid, ExecutionContextId(999999))
                            outcome <- Abort.run[BrowserIFrameException] {
                                Browser.activeIFrameLocal.let(Present(staleHandle): Maybe[IFrameHandle]) {
                                    BrowserEval.evalJs("1 + 1")
                                }
                            }
                        yield outcome match
                            case Result.Failure(BrowserIFrameInvalidException(IFrameReason.ContextDestroyed)) =>
                                succeed
                            case other =>
                                fail(s"Expected BrowserIFrameInvalidException(IFrameReason.ContextDestroyed) but got $other")
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "evalJsOn does not consult activeIFrameLocal - it always evaluates against the top-frame default context" in run {
        Scope.run {
            Abort.run[
                BrowserConnectionException | BrowserIFrameException | BrowserNavigationException | BrowserAssertionException |
                    BrowserScriptException
            ] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            _ <- Browser.eval(
                                """(() => {
                                    const f = document.createElement('iframe');
                                    f.id = 'iframe-evalJsOn-frame';
                                    f.src = 'about:blank';
                                    document.body.appendChild(f);
                                    return 'ok';
                                })()"""
                            )
                            ctxMap <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                tab.frameContexts.get.map { m =>
                                    if m.size >= 2 then m
                                    else
                                        Abort.fail[BrowserAssertionException](
                                            BrowserAssertionTimedOutException("iframe ctx", "size>=2", s"size=${m.size}")
                                        )
                                }
                            }
                            rootMaybe <- tab.rootFrameId.get
                            iframeEntry = ctxMap.find { case (fid, _) => Present(fid) != rootMaybe } match
                                case Present(value) => value
                                case Absent         => fail("no iframe entry")
                            handle = IFrameHandle(iframeEntry._1, iframeEntry._2)
                            (frameScopedUrl, escapeHatchUrl) <- Browser.activeIFrameLocal.let(
                                Present(handle): Maybe[IFrameHandle]
                            ) {
                                for
                                    // evalJs honors the local: returns 'about:blank'.
                                    a <- BrowserEval.evalJs("window.location.href")
                                    // evalJsOn explicitly bypasses: returns the parent's localhost URL.
                                    b <- Browser.runOn(tab) {
                                        Browser.use(t =>
                                            CdpBackend.runtimeEvaluate(
                                                t.backend.withSession(t.sessionId),
                                                EvalParams("window.location.href")
                                            ).map(CdpEvalDecoder.parseAndExtractEvalValue)
                                        )
                                    }
                                yield (a, b)
                            }
                        yield
                            assert(
                                frameScopedUrl == "about:blank",
                                s"evalJs inside withFrame must target the iframe but got '$frameScopedUrl'"
                            )
                            assert(
                                escapeHatchUrl.contains("/json/version"),
                                s"evalJsOn must always target the top frame but got '$escapeHatchUrl'"
                            )
                            succeed
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "nested Local.let calls round-trip the active frame: outer on entry, inner in body, outer restored on exit" in run {
        Scope.run {
            Abort.run[
                BrowserConnectionException | BrowserIFrameException | BrowserNavigationException | BrowserAssertionException |
                    BrowserScriptException
            ] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            _ <- Browser.eval(
                                """(() => {
                                    const a = document.createElement('iframe');
                                    a.id = 'iframe-outer';
                                    a.src = 'about:blank';
                                    document.body.appendChild(a);
                                    a.contentDocument.body.innerHTML = '<div id="iframe-tag">outer-frame</div>';
                                    const b = document.createElement('iframe');
                                    b.id = 'iframe-inner';
                                    b.src = 'about:blank';
                                    document.body.appendChild(b);
                                    b.contentDocument.body.innerHTML = '<div id="iframe-tag">inner-frame</div>';
                                    return 'ok';
                                })()"""
                            )
                            ctxMap <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                tab.frameContexts.get.map { m =>
                                    if m.size >= 3 then m
                                    else
                                        Abort.fail[BrowserAssertionException](
                                            BrowserAssertionTimedOutException("two iframe ctx", "size>=3", s"size=${m.size}")
                                        )
                                }
                            }
                            rootMaybe <- tab.rootFrameId.get
                            nonRoot     = ctxMap.toMap.toList.filter { case (fid, _) => Present(fid) != rootMaybe }
                            _           = assert(nonRoot.size == 2, s"Expected 2 iframe entries, got ${nonRoot.size}")
                            outerHandle = IFrameHandle(nonRoot.head._1, nonRoot.head._2)
                            innerHandle = IFrameHandle(nonRoot(1)._1, nonRoot(1)._2)
                            outerBefore <- Browser.activeIFrameLocal.let(Present(outerHandle): Maybe[IFrameHandle]) {
                                BrowserEval.evalJs("document.getElementById('iframe-tag').textContent")
                            }
                            innerInBody <- Browser.activeIFrameLocal.let(Present(outerHandle): Maybe[IFrameHandle]) {
                                Browser.activeIFrameLocal.let(Present(innerHandle): Maybe[IFrameHandle]) {
                                    BrowserEval.evalJs("document.getElementById('iframe-tag').textContent")
                                }
                            }
                            outerAfter <- Browser.activeIFrameLocal.let(Present(outerHandle): Maybe[IFrameHandle]) {
                                Browser.activeIFrameLocal.let(Present(innerHandle): Maybe[IFrameHandle])(BrowserEval.evalJs("1"))
                                    .andThen(BrowserEval.evalJs("document.getElementById('iframe-tag').textContent"))
                            }
                            absentAfter <- BrowserEval.evalJs("(document.getElementById('iframe-tag') || {textContent: ''}).textContent")
                        yield
                            assert(
                                outerBefore == outerAfter,
                                s"Outer scope must be restored after the inner block exits. before=$outerBefore after=$outerAfter"
                            )
                            assert(
                                innerInBody != outerBefore,
                                s"Inner scope must override the outer in the body. inner=$innerInBody outer=$outerBefore"
                            )
                            assert(
                                Set(outerBefore, innerInBody) == Set("outer-frame", "inner-frame"),
                                s"Both 'outer-frame' and 'inner-frame' must be observable. outer=$outerBefore inner=$innerInBody"
                            )
                            assert(
                                absentAfter == "",
                                s"After leaving every Local.let the parent document has no marker, got '$absentAfter'"
                            )
                            succeed
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "rapid 20 alert() dialogs are all auto-dismissed without drops" in run {
        Scope.run {
            Abort.run[BrowserConnectionException | BrowserNavigationException] {
                withBrowserOnLocalhost {
                    Browser.eval("""
                        (function() {
                            var count = 0;
                            for (var i = 0; i < 20; i++) {
                                alert('dialog-' + i);
                                count++;
                            }
                            return count;
                        })()
                    """).map { result =>
                        assert(result == "20", s"Expected 20 dialogs to complete but got: $result")
                        succeed
                    }
                }
            }.orFail("Unexpected")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // awaitDrain uses a Fiber.Promise re-issued per 0->1 in-flight transition.
    // ─────────────────────────────────────────────────────────────────────────

    "CdpBackend.awaitDrain returns within microseconds of last in-flight drain (no 5ms spin)" in run {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                Scope.run {
                    CdpBackend.init(wsUrl, Browser.LaunchConfig.default).map { backend =>
                        for
                            created  <- CdpBackend.createTarget(backend, CreateTargetParams("about:blank"))
                            attached <- CdpBackend.attachToTarget(backend, AttachParams(created.targetId, flatten = true))
                            session = backend.withSession(SessionId(attached.sessionId))
                            _ <- session.sendUnit[CdpNoParams]("Runtime.enable", CdpNoParams())

                            // Issue an in-flight 100 ms Promise and wait for it to complete.
                            _ <- CdpBackend.runtimeEvaluate(
                                session,
                                EvalParams(
                                    expression = "new Promise((r) => setTimeout(r, 100))",
                                    awaitPromise = true
                                )
                            )
                            t0 <- Clock.nowMonotonic
                            _  <- backend.awaitDrain
                            t1 <- Clock.nowMonotonic
                            elapsed = t1 - t0
                        yield assert(
                            elapsed < 5.millis,
                            s"awaitDrain still polling on 5ms tick? elapsed=$elapsed (expected well below 5ms with Fiber.Promise wake)"
                        )
                    }
                }
            }
        }.orFail("Unexpected BrowserConnectionException")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // close() WS full-teardown invariant
    // ─────────────────────────────────────────────────────────────────────────

    "close(grace) returns only after dialogDrainer is fully stopped (not just interrupted)" in run {
        // This test verifies the CORRECT invariant: after close() returns, the
        // dialogDrainer fiber is fully stopped (getResult is Done), not merely
        // scheduled-for-interrupt but still running. A drainer that is only
        // interrupted but not yet done would allow the next test's CdpBackend.init
        // to race with the still-live drainer fiber consuming the old dialog queue.
        val testLaunchCfg = Browser.LaunchConfig.default.copy(
            requestTimeout = 30.seconds,
            closeGrace = 100.millis
        )
        val testVersionResult = BrowserVersionResult(
            protocolVersion = "0",
            product = "Headless/0",
            revision = "0",
            userAgent = "Mozilla/5.0 (Headless)",
            jsVersion = "0.0"
        )
        Scope.run {
            for
                (clientTransport, server) <- JsonRpcTransport.inMemory
                versionMethod = JsonRpcMethod[BrowserGetVersionParams, BrowserVersionResult, Async & Abort[JsonRpcError]](
                    "Browser.getVersion"
                ) { (_, _) => testVersionResult }
                serverCfg = JsonRpcEndpoint.Config(
                    codec = JsonRpcCodec.Cdp,
                    maxInFlight = Present(8),
                    idStrategy = IdStrategy.SequentialInt
                )
                _       <- JsonRpcEndpoint.init(server, Seq(versionMethod), serverCfg)
                backend <- CdpBackend.initUnscoped(clientTransport, testLaunchCfg)
                drainer = backend.dialogDrainer
                // Verify drainer is alive before close.
                aliveBefore <- drainer.done
                // close(grace): must return only after full teardown.
                _ <- backend.close(50.millis)
                // After close() returns, getResult must be available immediately.
                // If the drainer were only interrupted-but-still-running, getResult
                // would block (still running) rather than completing right away.
                drainerResult <- drainer.getResult
                doneAfter     <- drainer.done
            yield
                assert(!aliveBefore, s"dialogDrainer should be running before close, but done=$aliveBefore")
                assert(doneAfter, s"dialogDrainer must be fully stopped after close() returns, but done=$doneAfter")
                drainerResult match
                    case Result.Failure(_: kyo.Interrupted) => succeed
                    case Result.Panic(_: kyo.Interrupted)   => succeed
                    case Result.Success(_)                  => succeed
                    case other                              => fail(s"drainer not fully stopped: $other")
                end match
            end for
        }
    }

end CdpBackendLifecycleTest

final private case class CreateIsolatedWorldParams(frameId: String, worldName: String) derives Schema, CanEqual
