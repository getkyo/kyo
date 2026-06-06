package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.BrowserElementNotActionableException.Reason
import kyo.BrowserIFrameInvalidException.Reason as IFrameReason
import kyo.internal.SharedChrome

/** [[CdpClient]] lifecycle, close, and connection tests.
  *
  * Tests that require a fault-injecting CDP WebSocket fixture server are marked `pending` with an explicit comment naming the missing
  * infrastructure. Live-browser tests use SharedChrome.
  */
class CdpClientLifecycleTest extends kyo.BrowserTest:

    override def timeout = 2.minutes

    // ─────────────────────────────────────────────────────────────────────────
    // initUnscoped(url).map(f) invokes f without auto-closing on exit
    // ─────────────────────────────────────────────────────────────────────────

    "initUnscoped + .map invokes f with the client" in {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                for
                    // Capture the client inside f, then verify it was called.
                    capturedClient <- CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { client =>
                        // Verify the client is live (f is invoked with a working client).
                        client.send("Target.getTargets").andThen(client)
                    }
                    // After initUnscoped + .map returns, f has completed but the client is NOT
                    // automatically closed; initUnscoped does not close on exit.
                    // The naming is "unscoped": the caller owns cleanup.
                    // Verify client is still live (unscoped means no auto-close).
                    stillAlive <- Abort.run[BrowserConnectionException](capturedClient.send("Target.getTargets"))
                    _          <- capturedClient.close(30.seconds) // manual cleanup
                yield stillAlive match
                    case Result.Success(reply) =>
                        // The client must still be alive (initUnscoped does not auto-close); a live send returns a non-empty wire reply.
                        assert(reply.nonEmpty)
                    case Result.Failure(err) =>
                        fail(s"initUnscoped + .map closed the client unexpectedly: ${err.getMessage}")
                    case Result.Panic(ex) => fail(s"Panic: ${ex.getMessage}")
            }
        }.orFail("Unexpected BrowserConnectionException")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // close() idempotency
    // ─────────────────────────────────────────────────────────────────────────

    "close() is idempotent - second call returns without exception" in {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { client =>
                    for
                        // First close; should succeed.
                        _ <- client.close(30.seconds)
                        // Second close; must not throw, hang, or panic.
                        _ <- client.close(30.seconds)
                    yield
                        // The leaf proves idempotency by reaching this point without exception; the
                        // orFail wrapper catches any Abort[BrowserConnectionException] on the failure
                        // path, so reaching here without failure is the meaningful contract.
                        succeed("a second close on an already-closed client is idempotent: it does not throw, hang, or panic")
                }
            }
        }.orFail("Unexpected exception on second close")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WS connection failure on init
    // ─────────────────────────────────────────────────────────────────────────

    "initUnscoped with bad URL fails fast with BrowserConnectionException" in {
        // initUnscoped waits for the WebSocket connect to resolve before returning the client (the
        // `connectReady` gate), so an unreachable URL surfaces as Abort[BrowserConnectionException]
        // from init itself rather than blocking the first send.
        // 127.0.0.1:0 is OS-rejected immediately so no timeout is needed.
        Abort.run[BrowserConnectionException] {
            CdpClient.initUnscoped("ws://127.0.0.1:0/", Browser.LaunchConfig.default).map(_ => ())
        }.map {
            case Result.Failure(_: BrowserConnectionException) =>
                ()
            case other =>
                fail(s"Expected fast Abort.Failure(BrowserConnectionException) from initUnscoped, got $other")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // close(Duration.Zero) short-circuit
    // ─────────────────────────────────────────────────────────────────────────

    "close(Duration.Zero) closes immediately and subsequent send raises ConnectionLost" in {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { client =>
                    // close(Duration.Zero) hits the zero-duration short-circuit branch.
                    client.close(Duration.Zero).andThen {
                        // Behavioural pair: subsequent send must fail with ConnectionLost.
                        Abort.run[BrowserConnectionException](client.send("Target.getTargets"))
                    }
                }
            }
        }.map {
            case Result.Success(innerResult) =>
                innerResult match
                    case Result.Failure(_: BrowserConnectionLostException) =>
                        ()
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

    "closeOrderly (close with grace) completes in-flight send before returning" in {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { client =>
                    for
                        createJson <- client.send("Target.createTarget", CreateTargetParams("about:blank"))
                        created = decodeCdpResult[CreateTargetResult](createJson)
                        attachJson <- client.send("Target.attachToTarget", AttachParams(created.targetId, flatten = true))
                        attached = decodeCdpResult[AttachResult](attachJson)
                        session  = client.withSession(SessionId(attached.sessionId))
                        _ <- session.sendUnit("Runtime.enable")

                        // Slow in-flight send: 1-second setTimeout Promise + awaitPromise=true.
                        slowFiber <- Fiber.initUnscoped {
                            session.send(
                                "Runtime.evaluate",
                                EvalParams(
                                    expression = "new Promise((r) => setTimeout(r, 1000))",
                                    awaitPromise = true
                                )
                            )
                        }

                        // Settle so the send is actually registered before close starts polling.
                        _ <- Async.delay(50.millis)(Kyo.unit)

                        // close(5s) must wait for the 1-second send to drain.
                        // Outer Async.timeout(10s) prevents a regression from hanging CI.
                        timedClose <- timed(Async.timeout(10.seconds)(client.close(5.seconds)))
                        (elapsed, _) = timedClose
                        fiberResult <- slowFiber.getResult
                    yield fiberResult match
                        case Result.Success(_) =>
                            // 900ms floor accounts for Chrome jitter while still detecting a regression
                            // where close returned without draining (~0 ms).
                            assert(
                                elapsed >= 900.millis,
                                s"close(5s) returned in $elapsed; expected >=900ms - drain did not wait"
                            )
                            ()
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

    "closeNow while a slow in-flight send is pending surfaces ConnectionLost to the caller" in {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { client =>
                    for
                        // Set up a session so we can issue a Runtime.evaluate with awaitPromise.
                        createJson <- client.send("Target.createTarget", CreateTargetParams("about:blank"))
                        created = decodeCdpResult[CreateTargetResult](createJson)
                        attachJson <- client.send("Target.attachToTarget", AttachParams(created.targetId, flatten = true))
                        attached = decodeCdpResult[AttachResult](attachJson)
                        session  = client.withSession(SessionId(attached.sessionId))
                        _ <- session.sendUnit("Runtime.enable")
                        // Launch the slow in-flight send: a Promise that resolves in 30 seconds.
                        // awaitPromise: true means the CDP round-trip won't complete for ~30s,
                        // guaranteeing the send is still in-flight when closeNow runs.
                        slowFiber <- Fiber.initUnscoped {
                            session.send(
                                "Runtime.evaluate",
                                EvalParams(
                                    expression = "new Promise((r) => setTimeout(r, 30000))",
                                    awaitPromise = true
                                )
                            )
                        }
                        // The slow send is guaranteed in-flight (30s timeout); call closeNow immediately.
                        _           <- client.closeNow
                        fiberResult <- slowFiber.getResult
                    yield fiberResult match
                        case Result.Failure(_: BrowserConnectionLostException) =>
                            ()
                        case Result.Success(v) =>
                            fail(s"Expected ConnectionLost but send succeeded with: $v")
                        case Result.Panic(ex) =>
                            fail(s"Expected ConnectionLost but got Panic: ${ex.getMessage}")
                        case Result.Failure(other) =>
                            fail(s"Expected BrowserConnectionLostException but got ${other.getClass.getName}: ${other.getMessage}")
                }
            }
        }.orFail("Unexpected outer")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // cross-session routing
    // ─────────────────────────────────────────────────────────────────────────

    "concurrent sends on two sessions route to the correct session without cross-talk" in {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                Scope.run {
                    CdpClient.init(wsUrl, Browser.LaunchConfig.default).map { client =>
                        for
                            // Create two targets.
                            t1Json <- client.send("Target.createTarget", CreateTargetParams("about:blank"))
                            t2Json <- client.send("Target.createTarget", CreateTargetParams("about:blank"))
                            t1 = decodeCdpResult[CreateTargetResult](t1Json)
                            t2 = decodeCdpResult[CreateTargetResult](t2Json)
                            a1Json <- client.send("Target.attachToTarget", AttachParams(t1.targetId, flatten = true))
                            a2Json <- client.send("Target.attachToTarget", AttachParams(t2.targetId, flatten = true))
                            a1       = decodeCdpResult[AttachResult](a1Json)
                            a2       = decodeCdpResult[AttachResult](a2Json)
                            sessionA = client.withSession(SessionId(a1.sessionId))
                            sessionB = client.withSession(SessionId(a2.sessionId))
                            _ <- sessionA.sendUnit("Runtime.enable")
                            _ <- sessionB.sendUnit("Runtime.enable")
                            // Concurrent sends on both sessions via Async.zip.
                            (rA, rB) <- Async.zip(
                                sessionA.send("Runtime.evaluate", EvalParams("'result-A'")),
                                sessionB.send("Runtime.evaluate", EvalParams("'result-B'"))
                            )
                            _ <- client.send("Target.closeTarget", CloseTargetParams(t1.targetId))
                            _ <- client.send("Target.closeTarget", CloseTargetParams(t2.targetId))
                        yield
                            assert(rA.contains("result-A"), s"Session A got wrong result: $rA")
                            assert(rB.contains("result-B"), s"Session B got wrong result: $rB")
                    }
                }
            }
        }.orFail("Unexpected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // nested withSession
    // ─────────────────────────────────────────────────────────────────────────

    "nested withSession routes inner sends to inner session and outer sends to outer session" in {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                Scope.run {
                    CdpClient.init(wsUrl, Browser.LaunchConfig.default).map { client =>
                        for
                            // Create two targets.
                            t1Json <- client.send("Target.createTarget", CreateTargetParams("about:blank"))
                            t2Json <- client.send("Target.createTarget", CreateTargetParams("about:blank"))
                            t1 = decodeCdpResult[CreateTargetResult](t1Json)
                            t2 = decodeCdpResult[CreateTargetResult](t2Json)
                            a1Json <- client.send("Target.attachToTarget", AttachParams(t1.targetId, flatten = true))
                            a2Json <- client.send("Target.attachToTarget", AttachParams(t2.targetId, flatten = true))
                            a1    = decodeCdpResult[AttachResult](a1Json)
                            a2    = decodeCdpResult[AttachResult](a2Json)
                            outer = client.withSession(SessionId(a1.sessionId))
                            inner = outer.withSession(SessionId(a2.sessionId))
                            _ <- outer.sendUnit("Runtime.enable")
                            _ <- inner.sendUnit("Runtime.enable")
                            // inner.send should carry sessionId = a2.sessionId
                            innerResult <- inner.send("Runtime.evaluate", EvalParams("'inner-session'"))
                            // outer.send should carry sessionId = a1.sessionId
                            outerResult <- outer.send("Runtime.evaluate", EvalParams("'outer-session'"))
                            _           <- client.send("Target.closeTarget", CloseTargetParams(t1.targetId))
                            _           <- client.send("Target.closeTarget", CloseTargetParams(t2.targetId))
                        yield
                            assert(inner.sessionId == Present(SessionId(a2.sessionId)), s"inner.sessionId mismatch: ${inner.sessionId}")
                            assert(outer.sessionId == Present(SessionId(a1.sessionId)), s"outer.sessionId mismatch: ${outer.sessionId}")
                            assert(innerResult.contains("inner-session"), s"inner session returned wrong result: $innerResult")
                            assert(outerResult.contains("outer-session"), s"outer session returned wrong result: $outerResult")
                    }
                }
            }
        }.orFail("Unexpected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // dialog drainer survives queue close on closeNow
    // ─────────────────────────────────────────────────────────────────────────

    "dialogDrainer fiber is done after closeNow" in {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                CdpClient.initUnscoped(wsUrl, Browser.LaunchConfig.default).map { client =>
                    // Capture reference to dialogDrainer fiber before closing.
                    val drainer = client.dialogDrainer
                    for
                        // Verify drainer is alive before close.
                        aliveBefore <- drainer.done
                        _           <- client.closeNow
                        // After closeNow, dialogDrainer must be interrupted/done.
                        // Poll briefly to allow fiber to settle (interrupt is async).
                        _          <- Async.delay(50.millis)(Kyo.unit)
                        aliveAfter <- drainer.done
                    yield
                        assert(!aliveBefore, s"dialogDrainer should be running before close, but done=$aliveBefore")
                        assert(aliveAfter, s"dialogDrainer should be done after closeNow, but done=$aliveAfter")
                    end for
                }
            }
        }.orFail("Unexpected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // rapid 20 dialogs, queue capacity 16
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // Frame-context tracker
    // ─────────────────────────────────────────────────────────────────────────

    "attachTab seeds frameContexts with the root frame's default executionContextId before user code runs" in {
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
                            ()
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "iframe injected via document.write triggers Runtime.executionContextCreated and adds an entry to frameContexts" in {
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
                            ()
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "iframe removal triggers Runtime.executionContextDestroyed and removes the matching entry" in {
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
                            ()
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "isolated-world contexts (auxData.isDefault == false) do not pollute frameContexts" in {
        // Strategy: emit two events in a known order on the wire, then synchronize on the SECOND.
        //   1. Page.createIsolatedWorld for the root frame  ->  Runtime.executionContextCreated
        //                                                       with auxData.isDefault == false.
        //   2. Inject a same-origin iframe                  ->  Runtime.executionContextCreated
        //                                                       with auxData.isDefault == true.
        // CDP delivers events in wire order on a single WebSocket and the relay dispatches them
        // sequentially, so by the time the iframe's entry appears in `frameContexts`, the prior
        // isolated-world event has already been processed (and correctly dropped by the filter).
        // This gives a deterministic synchronization point; no fixed delay required.
        Scope.run {
            Abort.run[BrowserConnectionException | BrowserNavigationException | BrowserAssertionException | BrowserScriptException] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            before    <- tab.frameContexts.get
                            rootMaybe <- tab.rootFrameId.get
                            _ <- rootMaybe match
                                case Present(rid) =>
                                    tab.client.withSession(tab.sessionId).sendUnit(
                                        "Page.createIsolatedWorld",
                                        CreateIsolatedWorldParams(rid.value, "kyo-iso-test")
                                    )
                                case Absent => Kyo.unit
                            // Inject a same-origin iframe. Its main world produces an
                            // executionContextCreated event with isDefault == true; the
                            // positive observable we synchronize on.
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
                            // The same-origin iframe's main world is now in the map. Because the
                            // relay processes wire messages sequentially, the isolated-world event
                            // (which arrived before the iframe event) has already been dispatched
                            // and dropped by the isDefault filter. Therefore:
                            //   - the map grew by EXACTLY 1 (the iframe), not 2;
                            //   - the root frame's executionContextId is unchanged (an isolated
                            //     world reuses the root frameId, so a leak would have replaced it).
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
                                        s"root frame's executionContextId must be unchanged " +
                                            s"(an isolated-world leak would have replaced it). " +
                                            s"rid=$rid before=${before.get(rid)} after=${after.get(rid)}"
                                    )
                                case Absent => fail("rootFrameId must be seeded by attachTab")
                            end match
                            ()
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "Page.getFrameTree returns the root frame plus every same-origin child frame after page settle" in {
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
                                json <- tab.client.withSession(tab.sessionId).send("Page.getFrameTree")
                                tree <- CdpBackend.decodeOrFail[GetFrameTreeResult](json, "Page.getFrameTree")
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
                                ()
                            end for
                        }
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "iframe added after page load and then removed leaves no leaked map keys" in {
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
                            ()
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "concurrent tabs share a connection but maintain independent frameContexts maps" in {
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
                                                BrowserAssertionTimedOutException(
                                                    "tab1 iframe",
                                                    "size>=2",
                                                    s"size=${m.size}"
                                                )
                                            )
                                    }
                                }
                                // Open a second tab in the same connection. about:blank; it has its own
                                // root frame and its own executionContextId (different from tab1's).
                                tabsCompared <- Browser.withNewTab {
                                    Browser.use { secondTab =>
                                        for
                                            // Wait for tab2's tracker to observe its own root context create event.
                                            _ <- Retry[BrowserAssertionException](Schedule.fixed(50.millis).take(40)) {
                                                secondTab.frameContexts.get.map { m =>
                                                    if m.nonEmpty then m
                                                    else
                                                        Abort.fail[BrowserAssertionException](
                                                            BrowserAssertionTimedOutException(
                                                                "tab2 root ctx",
                                                                "nonEmpty",
                                                                "empty"
                                                            )
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
                                            // Verify tab1's iframe entry didn't leak into tab2.
                                            assert(
                                                firstMap.size > secondMap.size,
                                                s"tab1 has the iframe so its map should be larger than tab2. first=${firstMap.size} second=${secondMap.size}"
                                            )
                                            ()
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

    "evalJs without an active frame scope evaluates against the top-level execution context" in {
        Scope.run {
            Abort.run[BrowserConnectionException | BrowserIFrameException | BrowserNavigationException] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            // No Local.let around evalJs => activeIFrameLocal == Absent => contextId
                            // must be OMITTED from the wire-encoded `Runtime.evaluate` params.
                            _           <- tab.client.lastEvaluateParams.set(Absent)
                            _           <- BrowserEval.evalJs("window.location.href")
                            recordedOpt <- tab.client.lastEvaluateParams.get
                        yield
                            val recorded = recordedOpt match
                                case Present(value) => value
                                case Absent         => fail("no Runtime.evaluate params recorded")
                            // Wire-shape contract: when activeIFrameLocal is Absent, the encoded
                            // `Runtime.evaluate` params must NOT carry a contextId field (Schema's
                            // Option[Int] encoder elides None entirely, so the key is absent).
                            // Decode the recorded params and assert the typed `contextId` is Absent.
                            val params = decode[EvalParams](recorded)
                            assert(
                                params.contextId.isEmpty,
                                s"Expected contextId Absent but recorded params were '$recorded' (decoded: $params)"
                            )
                            ()
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "evalJs inside an active withFrame scope evaluates against the iframe's execution context" in {
        Scope.run {
            Abort.run[
                BrowserConnectionException | BrowserIFrameException | BrowserNavigationException | BrowserAssertionException |
                    BrowserScriptException
            ] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            // Inject a same-origin iframe that loads `about:blank`. It inherits the parent origin so
                            // `Runtime.evaluate` against its execution context works on Chromium.
                            _ <- Browser.eval(
                                """(() => {
                                    const f = document.createElement('iframe');
                                    f.id = 'iframe-frame';
                                    f.src = 'about:blank';
                                    document.body.appendChild(f);
                                    return 'ok';
                                })()"""
                            )
                            // Wait for the executionContextCreated event to land in frameContexts.
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
                            // Pick the iframe's frameId; anything in the map other than the root.
                            iframeEntry = ctxMap.find { case (fid, _) => Present(fid) != rootMaybe } match
                                case Present(value) => value
                                case Absent         => fail(s"no iframe entry: rootMaybe=$rootMaybe map=$ctxMap")
                            handle = IFrameHandle(iframeEntry._1, iframeEntry._2)
                            // Inside the local scope, evalJs reads the iframe's contextId; window.location.href is
                            // the iframe's document URL ("about:blank"), distinct from the parent's localhost URL.
                            _ <- tab.client.lastEvaluateParams.set(Absent)
                            inFrameUrl <- Browser.activeIFrameLocal.let(Present(handle): Maybe[IFrameHandle]) {
                                BrowserEval.evalJs("window.location.href")
                            }
                            insideRecordedOpt  <- tab.client.lastEvaluateParams.get
                            _                  <- tab.client.lastEvaluateParams.set(Absent)
                            outsideUrl         <- BrowserEval.evalJs("window.location.href")
                            outsideRecordedOpt <- tab.client.lastEvaluateParams.get
                        yield
                            assert(
                                inFrameUrl == "about:blank",
                                s"Expected iframe-scoped url to be 'about:blank' but was '$inFrameUrl'"
                            )
                            assert(
                                outsideUrl.contains("/json/version"),
                                s"Expected top-frame url after exit to contain '/json/version' but was '$outsideUrl'"
                            )
                            // Wire-shape contract: inside the let, the encoded `Runtime.evaluate`
                            // params MUST carry contextId == iframe.executionContextId. Outside the
                            // let, the contextId field MUST be omitted (Absent activeIFrameLocal).
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
                            ()
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "Resolver.resolveOne reads the active contextId and resolves elements inside the active frame" in {
        Scope.run {
            Abort.run[
                BrowserConnectionException | BrowserIFrameException | BrowserNavigationException | BrowserAssertionException |
                    BrowserScriptException | BrowserElementException
            ] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            // Inject an iframe and stamp a marker element only inside it. The parent document does NOT
                            // contain an element with this id. If Resolver does not honor activeIFrameLocal, the resolver
                            // will fail to find the element when scoped to the iframe.
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
                            // Outside the frame scope: the marker element is invisible to the resolver because it
                            // lives in the iframe's document, not the parent document.
                            outsideRef <- Resolver.resolveOne(Browser.Selector.id("iframe-only-in-iframe"))
                            // Inside the frame scope: Resolver evaluates against the iframe's contextId, so
                            // `document` resolves to the iframe's document and the marker is found.
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
                            ()
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "Actionability.check reads the active contextId and gates on the iframe's element" in {
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
                            // Outside the frame scope: no element matches the selector, so Actionability returns NotAttached.
                            outside <- Actionability.check(
                                Browser.Selector.id("iframe-iframe-button"),
                                requireFillable = false,
                                requireEnabled = true
                            )
                            // Inside the frame scope: Actionability evaluates against the iframe's context. The button
                            // is connected and visible there, so the check passes.
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
                            ()
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "evalJs against a destroyed execution context aborts with BrowserIFrameInvalidException" in {
        Scope.run {
            Abort.run[
                BrowserConnectionException | BrowserNavigationException | BrowserAssertionException | BrowserScriptException
            ] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            // Synthesize a stale IFrameHandle whose executionContextId was never created on the page.
                            // CDP will respond with the error "Cannot find context with specified id"; the iframe
                            // translator must intercept and surface BrowserIFrameInvalidException, NOT BrowserProtocolErrorException.
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
                                ()
                            case other =>
                                fail(s"Expected BrowserIFrameInvalidException(IFrameReason.ContextDestroyed) but got $other")
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "evalJsOn does not consult activeIFrameLocal - it always evaluates against the top-frame default context" in {
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
                            // Inside an active frame scope, evalJs targets the iframe; the inlined runtime-evaluate
                            // path must NOT consult the local; it always evaluates against the top-frame default
                            // context. We invoke the private helper via Browser.runOn (which strips Env[BrowserTab])
                            // inside the let so the active scope is in flight when the evaluate would have
                            // observed it had it been threaded.
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
                                                t.client.withSession(t.sessionId),
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
                            ()
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "nested Local.let calls round-trip the active frame: outer on entry, inner in body, outer restored on exit" in {
        Scope.run {
            Abort.run[
                BrowserConnectionException | BrowserIFrameException | BrowserNavigationException | BrowserAssertionException |
                    BrowserScriptException
            ] {
                withBrowserOnLocalhost {
                    Browser.use { tab =>
                        for
                            // Two distinct iframes; we'll point outer at the first, inner at the second.
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
                            // Order is non-deterministic but the two iframes inject markers identifying themselves;
                            // pick whichever ctxId resolves to the 'outer-frame' marker as the outer handle.
                            // We probe each non-root entry to find the matching one.
                            nonRoot     = ctxMap.toMap.toList.filter { case (fid, _) => Present(fid) != rootMaybe }
                            _           = assert(nonRoot.size == 2, s"Expected 2 iframe entries, got ${nonRoot.size}")
                            outerHandle = IFrameHandle(nonRoot.head._1, nonRoot.head._2)
                            innerHandle = IFrameHandle(nonRoot(1)._1, nonRoot(1)._2)
                            // Read the marker tag in each scope to verify Local.let round-trip.
                            outerBefore <- Browser.activeIFrameLocal.let(Present(outerHandle): Maybe[IFrameHandle]) {
                                BrowserEval.evalJs("document.getElementById('iframe-tag').textContent")
                            }
                            innerInBody <- Browser.activeIFrameLocal.let(Present(outerHandle): Maybe[IFrameHandle]) {
                                Browser.activeIFrameLocal.let(Present(innerHandle): Maybe[IFrameHandle]) {
                                    BrowserEval.evalJs("document.getElementById('iframe-tag').textContent")
                                }
                            }
                            outerAfter <- Browser.activeIFrameLocal.let(Present(outerHandle): Maybe[IFrameHandle]) {
                                // After leaving the inner Local.let, the outer frame must be restored.
                                Browser.activeIFrameLocal.let(Present(innerHandle): Maybe[IFrameHandle])(BrowserEval.evalJs("1"))
                                    .andThen(BrowserEval.evalJs("document.getElementById('iframe-tag').textContent"))
                            }
                            // After leaving the outer Local.let entirely, the active frame is Absent; top-frame
                            // evaluation; the parent document has no '#iframe-tag' element at all.
                            absentAfter <- BrowserEval.evalJs("(document.getElementById('iframe-tag') || {textContent: ''}).textContent")
                        yield
                            // We don't know which of nonRoot is "outer" vs "inner" up-front; instead, assert that
                            // outerBefore and outerAfter agree (round-trip), and innerInBody differs from outerBefore.
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
                            ()
                        end for
                    }
                }
            }.orFail("Unexpected")
        }
    }

    "rapid 20 alert() dialogs are all auto-dismissed without drops" in {
        // This test fires 20 synchronous alert() calls via JS and counts the
        // Page.javascriptDialogOpening events enqueued in the dialog drainer.
        // The dialogQueue capacity is 16 - if the drainer cannot keep up the
        // extra dialogs overflow. We assert all 20 are dismissed (no hang).
        //
        // Uses withBrowserOnLocalhost because data: URLs can behave differently.
        // Each alert() is synchronous in the page context; they fire one at a time
        // as Chrome awaits Page.handleJavaScriptDialog before resuming JS.
        // Therefore the 20 dialogs are naturally serialised through the drainer.
        Scope.run {
            Abort.run[BrowserConnectionException | BrowserNavigationException] {
                withBrowserOnLocalhost {
                    // Fire 20 alert() calls in a loop. Because alert() is synchronous,
                    // Chrome waits for Page.handleJavaScriptDialog between each one,
                    // so the dialogQueue never holds more than 1 entry at a time.
                    // The drainer dismisses them in order without drops.
                    // We use window.__dialogCount to count dismissals.
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
                    }
                }
            }.orFail("Unexpected")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // awaitDrain uses a Fiber.Promise re-issued per 0↔1 in-flight transition instead of a 5 ms polling loop.
    // The behavioural contract: once the last in-flight CDP request completes, awaitDrain wakes within
    // microseconds, not on a 5 ms tick boundary. We exercise the wake path by issuing a long in-page
    // Promise, letting it complete, then timing the awaitDrain call from after that point.
    // ─────────────────────────────────────────────────────────────────────────

    "CdpClient.awaitDrain returns within microseconds of last in-flight drain (no 5ms spin)" in {
        Abort.run[BrowserConnectionException] {
            SharedChrome.init.map { wsUrl =>
                Scope.run {
                    CdpClient.init(wsUrl, Browser.LaunchConfig.default).map { client =>
                        for
                            createJson <- client.send("Target.createTarget", CreateTargetParams("about:blank"))
                            created = decodeCdpResult[CreateTargetResult](createJson)
                            attachJson <- client.send("Target.attachToTarget", AttachParams(created.targetId, flatten = true))
                            attached = decodeCdpResult[AttachResult](attachJson)
                            session  = client.withSession(SessionId(attached.sessionId))
                            _ <- session.sendUnit("Runtime.enable")

                            // Issue an in-flight 100 ms Promise and wait for it to complete: at this point inFlight
                            // is back to 0 and the drain promise has already been completed. The awaitDrain that
                            // follows must see the AtomicInt at 0 and return immediately, NOT wait 5 ms before
                            // re-checking.
                            _ <- session.send(
                                "Runtime.evaluate",
                                EvalParams(
                                    expression = "new Promise((r) => setTimeout(r, 100))",
                                    awaitPromise = true
                                )
                            )
                            t0 <- Clock.nowMonotonic
                            _  <- client.awaitDrain
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

end CdpClientLifecycleTest

final private case class CreateIsolatedWorldParams(frameId: String, worldName: String) derives Schema, CanEqual
