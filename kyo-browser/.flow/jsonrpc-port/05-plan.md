# 05 Plan: Port kyo-browser CDP client to kyo-jsonrpc

Task type: migration
Cites design: ./02-design.md (patched)
Cites invariants: ./04-invariants.md
Cites resolutions: ./03a-open-resolutions.md, ./03b-user-escalations.md

<!-- flow-allow: Every `Sync.defer(...)` token in this plan is the Kyo
     effect-suspension API (see feedback_cio_lift_defer.md), not the
     "defer this for later" reward-hack the class-A scanner targets.
     All 7 rewardhack class-A hits on `Sync.defer` are VALIDATED_EXCEPTION
     per the 06-validation.md class-B verdict. -->

## Summary

5 implementation phases (01..05) covering the design's §11 cutover. Net code change:

- ~1000 LoC deleted from `kyo-browser` internals (CdpClient.scala 627 LoC + CdpWire envelope/error helpers + CdpSender trait + decodeOrFail helper + bespoke decodeCdpMessage router).
- Net test delta: -32 cases (1276 shared + 4 platform-specific after Phase 03).
- 18 stability-layer files byte-identical post-port (per INV-007).
- Public surface byte-identical (INV-001 / INV-002 / INV-003).

Per-phase outline:

- **Phase 01** Introduce new `CdpBackend` (runtime class) built on `JsonRpcEndpoint`. Wire `JsonRpcHttpTransport.webSocket`, `JsonRpcCodec.Cdp`, `ExtrasEncoder` for sessionId, `Browser.getVersion` connect-probe (Q-002 option b). Add per-session routing via `JsonRpcMethod.notification` handlers. Old `CdpClient` kept alive in parallel so tests keep compiling. New file `CdpBackendSmokeTest.scala` exercises the new path.
- **Phase 02** Cut over `Browser` / `BrowserTab` / `BrowserSnapshot` (and every other consumer of `CdpClient`) to the new `CdpBackend`. Delete `CdpClient.scala` (627 LoC), the `CdpSender` trait, and the `decodeOrFail` helper. Compile + run shared / JVM / JS / Native targeted test suites.
- **Phase 03** Test-rewrite accounting per Q-005 / INV-004. DELETE `CdpClientTest` (15) + `CdpClientDecoderTest` (7); RENAME + shrink `CdpClientLifecycleTest` -> `CdpBackendLifecycleTest` (10 cases deleted, 15 kept); REWRITE bodies of `CdpBackendTest` (41) and `BrowserWireDecodeFailureTest` (6); KEEP unchanged `CdpParamsRoundTripTest` (15), `CdpTypesTest` (5), `CdpTypesSchemaFailureTest` (6), `CdpEvalDecoderTest` (15). Net delta -32 cases. Commit message records the engine-side coverage citation for every deleted case.
- **Phase 04** Delete obsolete `CdpWire` envelope/error case classes (`CdpEnvelope`, `CdpNoParams`, `CdpWireMessage`, `CdpReply`, `CdpEventParams`, `FallbackIdEnvelope`, plus the `CdpError` ADT). Keep CDP-specific decoders (`CdpBase64Decode`, `ExceptionDetailsFormat`, `CdpEvalDecoder`). Delete `CdpEvalEnvelope.decodeEvalEnvelope` (now redundant). Verify CDP-only types survive.
- **Phase 05** Cross-platform sweep on JVM + JS + Native, ALL 1276 shared + 4 platform-specific cases per platform run sequentially (per INV-021). Final strip-dev pass owned by Stage 3 of the FLOW campaign.

Cross-cutting: every phase declares `platforms: [jvm, js, native]` (per INV-005). Every phase uses `// flow-allow: <rationale>` for any annotated exception (INV-024). Per-platform test runs are SEQUENTIAL (INV-021) due to Chrome process contention.

---

## Phase 01: Introduce CdpBackend runtime class behind feature parity

Depends on: none (this is the foundational phase).

Phase 01 introduces a new runtime class `CdpBackend` in `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` that owns a `JsonRpcEndpoint`, dispatches CDP events via `JsonRpcMethod.notification` handlers, and exposes the `send` / `withSession` / `close` / `closeNow` / `awaitDrain` / `init` / `initUnscoped` surface. The existing `CdpClient.scala` is preserved byte-equivalent in this phase (renamed nowhere, fields untouched) so production call sites and the 1308 existing test cases compile. `kyo-browser` gains a new `dependsOn(`kyo-jsonrpc`, `kyo-jsonrpc-http`)` in `build.sbt`.

The old `CdpBackend` object (228 LoC method namespace holding the 28 typed wrappers + `decodeOrFail` + `CdpSender` interface) keeps the same file path. To allow a runtime CLASS `CdpBackend` to co-exist in the same file we rename the existing OBJECT `CdpBackend` to `CdpBackendOld` for ONE PHASE. Phase 02 finalises by deleting the `Old` symbol when the cut-over completes; this is a Phase 01 internal-name accomodation (`// flow-allow: phase-01 byte-equivalent coexistence` annotated where the rename appears).

A new smoke test `CdpBackendSmokeTest.scala` exercises the new class: init via `JsonRpcTransport.inMemory`, a basic send-and-receive, withSession, close, sendUnmatched fire-and-forget, and the four notification routes (`Page.javascriptDialogOpening`, `Runtime.executionContext{Created,Destroyed}`, `Page.downloadWillBegin`, `Page.downloadProgress`). The smoke test feeds the per-phase `flow-verify` step.

A new test `JsonRpcPortInvariantsSpec.scala` is introduced carrying smoke tests for every invariant Phase 01 produces (INV-008 / INV-009 / INV-011 / INV-012 / INV-013 / INV-014 / INV-015 / INV-016 / INV-017 / INV-018 / INV-019 / INV-020 / INV-022 / INV-023 / INV-024). Per Rule 8c, the spec lives in the same commit as the new `CdpBackend` runtime class.

### Files to produce

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`: introduces the new `CdpBackend` runtime class alongside the existing object (renamed to `CdpBackendOld` for one phase). Owns dispatcher tables, `JsonRpcEndpoint`, dialog drainer, lastEvaluateParams. Init wires the 5 notification handlers and runs the `Browser.getVersion` connect-probe.
  Matching test: `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala`

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala
package kyo.internal

import CdpTypes.*
import kyo.*
import kyo.internal.cdp.PageDownload

/** Runtime CDP backend built atop a [[JsonRpcEndpoint]]. Owns the per-connection
  * dispatcher tables (frame-event / download-event / dialog handlers / dialog
  * recorders), the dialog drainer fiber, the lastEvaluateParams diagnostic, the
  * per-session ExtrasEncoder, and the 5 CDP notification handlers. Wire framing,
  * codec dispatch, request correlation, per-call timeout, in-flight metering,
  * drain signal, graceful close, and malformed-envelope routing are owned by the
  * embedded [[JsonRpcEndpoint]].
  *
  * Replaces the deprecated `CdpClient` (Phase 02 deletes `CdpClient.scala`).
  */
final private[kyo] class CdpBackend private[kyo] (
    private[kyo] val endpoint: JsonRpcEndpoint,
    private[kyo] val dialogHandlers: AtomicRef[Dict[String, (Boolean, String)]],
    private[kyo] val dialogDrainer: Fiber[Unit, Any],
    private[kyo] val dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
    private[kyo] val frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
    private[kyo] val dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]],
    private[kyo] val lastEvaluateParams: AtomicRef[Maybe[String]],
    private[kyo] val sessionId: Maybe[SessionId] = Absent
):

    /** Typed CDP call. Records lastEvaluateParams on Runtime.evaluate, stamps
      * sessionId via ExtrasEncoder, recovers engine errors to kyo-browser's
      * typed BrowserReadException tree (INV-017).
      */
    private[kyo] def send[P: Schema, R: Schema](method: String, params: P)(using
        Frame
    ): R < (Async & Abort[BrowserReadException]) =
        val record =
            if method == CdpBackend.RuntimeEvaluateMethod
            then lastEvaluateParams.set(Present(Json.encode(params)))
            else Kyo.unit
        val extras = sessionId match
            case Present(sid) => ExtrasEncoder.const(
                Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value)))
            )
            case Absent => ExtrasEncoder.empty
        record.andThen {
            Abort.recover[Closed] { closed =>
                Abort.fail(BrowserConnectionLostException(
                    s"Connection lost: ${closed.getMessage}", Present(closed)))
            } {
                Abort.recover[Timeout] { _ =>
                    Abort.fail(BrowserConnectionLostException(
                        s"Request timeout: $method", Absent))
                } {
                    Abort.recover[JsonRpcError] { err =>
                        Abort.fail(BrowserProtocolErrorException(method, err.message))
                    } {
                        endpoint.call[P, R](method, params, extras)
                    }
                }
            }
        }
    end send

    /** Unit-typed send: discards the typed result. Wrappers that do not consume
      * a result envelope (e.g. Page.navigate) call this.
      */
    private[kyo] def sendUnit[P: Schema](method: String, params: P)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        send[P, Unit](method, params)

    /** Session-scoped fork. All dispatcher tables and the endpoint are shared
      * with the parent; only the sessionId field differs. ExtrasEncoder is
      * recomputed per-call from this field, so the wire byte shape is
      * identical to the legacy CdpClient.withSession.
      */
    private[kyo] def withSession(sid: SessionId): CdpBackend =
        new CdpBackend(
            endpoint, dialogHandlers, dialogDrainer, dialogQueue,
            frameEventDispatchers, downloadEventDispatchers,
            dialogRecorders, lastEvaluateParams,
            sessionId = Present(sid)
        )

    /** Graceful close: delegates to engine.close(gracePeriod), then closes the
      * dialog queue, then interrupts the drainer fiber.
      */
    private[kyo] def close(gracePeriod: Duration)(using Frame): Unit < Async =
        endpoint.close(gracePeriod)
            .andThen(Abort.run[Closed](dialogQueue.close).unit)
            .andThen(dialogDrainer.interrupt.unit)

    /** Immediate close: same ordering, no grace period. */
    private[kyo] def closeNow(using Frame): Unit < Async =
        endpoint.closeNow
            .andThen(Abort.run[Closed](dialogQueue.close).unit)
            .andThen(dialogDrainer.interrupt.unit)

    /** Drain barrier: returns when all outstanding inflight calls have settled. */
    private[kyo] def awaitDrain(using Frame): Unit < Async =
        endpoint.awaitDrain
end CdpBackend

/** Static helpers + named constants + `init` / `initUnscoped` for [[CdpBackend]].
  *
  * Keeps the `CdpBackendOld` object (the 228-LoC legacy method-namespace +
  * decodeOrFail helper + 28 typed wrappers + CdpSender trait) alive in the
  * SAME file for Phase 01 byte-equivalent coexistence; Phase 02 deletes
  * `CdpBackendOld` and inlines the typed wrappers as two-line bodies on the
  * runtime `CdpBackend`.
  */
private[kyo] object CdpBackend:

    /** CDP method name surfaced both at the call site and at the
      * lastEvaluateParams write site.
      */
    private[kyo] val RuntimeEvaluateMethod = "Runtime.evaluate"

    /** Per-connection in-flight cap. Carried verbatim from
      * CdpClient.scala:206; the engine binds this to
      * `Config.maxInFlight = Present(8)`.
      */
    private[kyo] val maxInFlight: Int = 8

    /** Scope-bound init: closes the backend on scope exit. */
    def init(wsUrl: String, launchCfg: Browser.LaunchConfig)(using
        Frame
    ): CdpBackend < (Async & Scope & Abort[BrowserReadException]) =
        Scope.acquireRelease(initUnscoped(wsUrl, launchCfg))(_.close(launchCfg.closeGrace))

    /** Unscoped init: caller-managed lifecycle. */
    def initUnscoped(wsUrl: String, launchCfg: Browser.LaunchConfig)(using
        Frame
    ): CdpBackend < (Async & Abort[BrowserReadException]) =
        for
            dialogHandlers           <- AtomicRef.init[Dict[String, (Boolean, String)]](Dict.empty)
            dialogQueue              <- Channel.initUnscoped[(Boolean, String, Maybe[SessionId])](16)
            frameEventDispatchers    <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            downloadEventDispatchers <- AtomicRef.init[Dict[String, CdpEvent.Generic => Unit < Sync]](Dict.empty)
            dialogRecorders          <- AtomicRef.init[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]](Dict.empty)
            lastEvaluateParams       <- AtomicRef.init[Maybe[String]](Absent)
            dialogIdCounter          <- AtomicInt.init(Int.MinValue)
            url                      <- parseWsUrl(wsUrl)
            transport <- Abort.recover[HttpException] { e =>
                Abort.fail(BrowserSetupException(s"WS transport setup: ${e.getMessage}"))
            } {
                JsonRpcHttpTransport.webSocket(url, HttpHeaders.empty, JsonRpcCodec.Cdp)
            }
            dialogMethod         <- buildDialogMethod(dialogHandlers, dialogQueue, dialogRecorders)
            frameCreatedMethod   <- buildFrameMethod(frameEventDispatchers, "Runtime.executionContextCreated")
            frameDestroyedMethod <- buildFrameMethod(frameEventDispatchers, "Runtime.executionContextDestroyed")
            downloadWillMethod   <- buildDownloadWillMethod(downloadEventDispatchers)
            downloadProgMethod   <- buildDownloadProgressMethod(downloadEventDispatchers)
            config = JsonRpcEndpoint.Config(
                codec                 = JsonRpcCodec.Cdp,
                cancellation          = Absent,
                progress              = Absent,
                unknownMethod         = UnknownMethodPolicy.minimal,
                gate                  = Absent,
                maxInFlight           = Present(maxInFlight),
                requestTimeout        = launchCfg.requestTimeout,
                idStrategy            = IdStrategy.SequentialInt,
                progressResetsTimeout = false
            )
            endpoint <- JsonRpcEndpoint.init(
                transport,
                Seq(dialogMethod, frameCreatedMethod, frameDestroyedMethod,
                    downloadWillMethod, downloadProgMethod),
                config
            )
            dialogDrainer <- buildDialogDrainer(endpoint, dialogQueue, dialogIdCounter)
            backend = new CdpBackend(
                endpoint, dialogHandlers, dialogDrainer, dialogQueue,
                frameEventDispatchers, downloadEventDispatchers,
                dialogRecorders, lastEvaluateParams,
                sessionId = Absent
            )
            // Q-002 ratified probe: Browser.getVersion proves the WS handshake
            // + one CDP round-trip is live. Closed -> BrowserSetupException
            // surfaces an upfront typed error rather than a delayed hang.
            _ <- Abort.recover[BrowserReadException] {
                case _: BrowserConnectionLostException =>
                    Abort.fail(BrowserSetupException("WS handshake failed: probe call returned Closed"))
                case other => Abort.fail(other)
            } {
                backend.send[BrowserGetVersionParams, BrowserVersionResult](
                    "Browser.getVersion", BrowserGetVersionParams())
            }
        yield backend

    /** Parse the WS url, recovering parse failure to BrowserSetupException. */
    private def parseWsUrl(wsUrl: String)(using Frame): HttpUrl < Abort[BrowserSetupException] =
        HttpUrl.parse(wsUrl) match
            case Result.Success(u) => u
            case Result.Failure(e) => Abort.fail(BrowserSetupException(s"WS url parse failed: ${e.getMessage}"))
            case Result.Panic(t)   => Abort.fail(BrowserSetupException(s"WS url parse panicked: ${t.getMessage}"))

    /** Page.javascriptDialogOpening: intercept, lookup handler, enqueue
      * Page.handleJavaScriptDialog response, append to recorder if registered.
      */
    private def buildDialogMethod(
        dialogHandlers: AtomicRef[Dict[String, (Boolean, String)]],
        dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
        dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]]
    )(using Frame): JsonRpcMethod[Async & Abort[JsonRpcError]] < Sync =
        Sync.defer(JsonRpcMethod.notification[JavascriptDialogOpeningParams, Async & Abort[JsonRpcError]](
            "Page.javascriptDialogOpening"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            handleDialogOpening(dialogHandlers, dialogQueue, dialogRecorders, params, sid)
        })

    /** Runtime.executionContext{Created,Destroyed}: shared body. */
    private def buildFrameMethod(
        frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
        methodName: String
    )(using Frame): JsonRpcMethod[Async & Abort[JsonRpcError]] < Sync =
        Sync.defer(JsonRpcMethod.notification[Structure.Value, Async & Abort[JsonRpcError]](methodName) {
            (paramsValue, ctx) =>
                val sid = readSessionIdFromExtras(ctx.extras)
                dispatchFrameEvent(frameEventDispatchers, methodName, paramsValue, sid)
        })

    /** Page.downloadWillBegin notification. */
    private def buildDownloadWillMethod(
        downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcMethod[Async & Abort[JsonRpcError]] < Sync =
        Sync.defer(JsonRpcMethod.notification[PageDownload.DownloadWillBeginWire, Async & Abort[JsonRpcError]](
            "Page.downloadWillBegin"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchDownloadEvent(downloadEventDispatchers, "Page.downloadWillBegin", params, sid)
        })

    /** Page.downloadProgress notification. */
    private def buildDownloadProgressMethod(
        downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]]
    )(using Frame): JsonRpcMethod[Async & Abort[JsonRpcError]] < Sync =
        Sync.defer(JsonRpcMethod.notification[PageDownload.DownloadProgressWire, Async & Abort[JsonRpcError]](
            "Page.downloadProgress"
        ) { (params, ctx) =>
            val sid = readSessionIdFromExtras(ctx.extras)
            dispatchDownloadEvent(downloadEventDispatchers, "Page.downloadProgress", params, sid)
        })

    /** Dialog drainer: consumes dialogQueue, allocates negative ids from
      * dialogIdCounter (disjoint from IdStrategy.SequentialInt's positive
      * allocator, per INV-018), and writes Page.handleJavaScriptDialog
      * fire-and-forget via endpoint.sendUnmatched.
      */
    private def buildDialogDrainer(
        endpoint: JsonRpcEndpoint,
        dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
        dialogIdCounter: AtomicInt
    )(using Frame): Fiber[Unit, Any] < Async =
        Fiber.initUnscoped {
            Abort.run[Closed] {
                dialogQueue.stream().foreach { case (accept, promptText, sessionId) =>
                    dialogIdCounter.getAndIncrement.map { id =>
                        val extras = sessionId match
                            case Present(sid) => ExtrasEncoder.const(
                                Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str(sid.value)))
                            )
                            case Absent => ExtrasEncoder.empty
                        Abort.run[Closed](
                            endpoint.sendUnmatched(
                                "Page.handleJavaScriptDialog",
                                HandleJavaScriptDialogParams(accept, promptText),
                                JsonRpcId.Num(id.toLong),
                                extras
                            )
                        ).unit
                    }
                }
            }.unit
        }

    /** Reads sessionId from HandlerCtx.extras (RI-001 path:
      * JsonRpcEndpointImpl.scala:911-916 constructs HandlerCtx with env.extras).
      */
    private def readSessionIdFromExtras(extras: Maybe[Structure.Value]): Maybe[SessionId] =
        extras match
            case Present(Structure.Value.Record(fields)) =>
                Maybe.fromOption(fields.iterator.collectFirst {
                    case ("sessionId", Structure.Value.Str(s)) => SessionId(s)
                })
            case _ => Absent

    /** Handles a dialog-opening event: lookup handler, default to auto-dismiss
      * (accept=false, prompt=""), enqueue the response, and append to the
      * recorder (if any) for this session.
      *
      * Auto-dismiss is the test-stability-critical edge case: every test that
      * triggers an alert without explicit dialog handling relies on it to
      * avoid hang. The behavior here is byte-equivalent to
      * CdpClient.decodeCdpMessage's dialog-opening branch.
      */
    private def handleDialogOpening(
        dialogHandlers: AtomicRef[Dict[String, (Boolean, String)]],
        dialogQueue: Channel[(Boolean, String, Maybe[SessionId])],
        dialogRecorders: AtomicRef[Dict[String, AtomicRef[Chunk[Browser.DialogEvent]]]],
        params: JavascriptDialogOpeningParams,
        sid: Maybe[SessionId]
    )(using Frame): Unit < (Async & Abort[JsonRpcError]) =
        val key = sid.map(_.value).getOrElse("")
        for
            handlers <- dialogHandlers.get
            decision = handlers.get(key).getOrElse((false, ""))
            _ <- Abort.run[Closed](dialogQueue.put((decision._1, decision._2, sid))).unit
            recorders <- dialogRecorders.get
            _ <- recorders.get(key) match
                     case Present(ref) =>
                         ref.updateAndGet(_ :+ Browser.DialogEvent(params.`type`, params.message, decision._1, decision._2)).unit
                     case Absent => Kyo.unit
        yield ()
        end for

    /** Dispatch a typed frame event to the per-session handler.
      *
      * The dispatcher table stays keyed by sessionId.value and holds
      * `CdpEvent.Generic => Unit < Sync` handlers. The notification handler
      * wraps the typed params back into `CdpEvent.Generic(method,
      * Json.encode(params), sid)` for byte-equivalence with the legacy
      * dispatcher map shape.
      */
    private def dispatchFrameEvent(
        frameEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
        method: String,
        paramsValue: Structure.Value,
        sid: Maybe[SessionId]
    )(using Frame): Unit < (Async & Abort[JsonRpcError]) =
        val key  = sid.map(_.value).getOrElse("")
        val json = Json.encode(paramsValue)
        for
            table <- frameEventDispatchers.get
            _ <- table.get(key) match
                     case Present(h) => h(CdpEvent.Generic(method, json, sid))
                     case Absent     => Kyo.unit
        yield ()

    /** Same dispatch shape as dispatchFrameEvent but for download events;
      * carries the typed schema wrapper so kyo-schema validates incoming wire.
      */
    private def dispatchDownloadEvent[P: Schema](
        downloadEventDispatchers: AtomicRef[Dict[String, CdpEvent.Generic => Unit < Sync]],
        method: String,
        params: P,
        sid: Maybe[SessionId]
    )(using Frame): Unit < (Async & Abort[JsonRpcError]) =
        val key  = sid.map(_.value).getOrElse("")
        val json = Json.encode(params)
        for
            table <- downloadEventDispatchers.get
            _ <- table.get(key) match
                     case Present(h) => h(CdpEvent.Generic(method, json, sid))
                     case Absent     => Kyo.unit
        yield ()
end CdpBackend

/** Phase-01 byte-equivalent coexistence: the legacy method-namespace object
  * `CdpBackendOld` carries the 228-LoC of typed wrappers + decodeOrFail +
  * CdpSender trait so the existing CdpClient call sites continue to compile
  * unchanged. Phase 02 deletes this object outright and inlines two-line
  * `backend.send[Params, Result]` calls at every call site.
  *
  * // flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02
  */
private[kyo] object CdpBackendOld:
    private[kyo] val RuntimeEvaluateMethod = CdpBackend.RuntimeEvaluateMethod
    // ... existing 228 LoC of decodeOrFail + 28 typed wrappers + CdpSender
    // trait moved verbatim from prior CdpBackend object body.
    // (Listing the full 228 LoC here is byte-equivalent to current HEAD;
    // see kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala
    // at pre-port-tag for the canonical source.)
    // plan: Phase 01 impl agent transcribes pre-port HEAD's CdpBackend object
    // verbatim under this name, except every reference to internal type
    // CdpSender stays the same (the trait is still alive in CdpClient.scala
    // in Phase 01).
end CdpBackendOld
```

- `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala`: smoke tests for INVs that Phase 01 produces (per INV-008 Rule 8c, same commit).
  Matching test file is itself (it IS the spec).

  *(No code block. Test files have no plan-side code per the FLOW code-in-plan contract; behavior is described in `### Tests`.)*

### Files to modify

- `kyo-browser/build.sbt` (root): add `dependsOn(`kyo-jsonrpc`, `kyo-jsonrpc-http`)` to `kyo-browser` crossProject at line 1166.

```scala
// build.sbt ; BEFORE (line 1162-1175 region)
lazy val `kyo-browser` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-browser"))
        .dependsOn(`kyo-http`)
        .settings(
            `kyo-settings`,
            scalacOptions ++= Seq("-Wunused:imports")
        )

// build.sbt ; AFTER (line 1162-1175 region)
lazy val `kyo-browser` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform)
        .crossType(CrossType.Full)
        .in(file("kyo-browser"))
        .dependsOn(`kyo-http`, `kyo-jsonrpc`, `kyo-jsonrpc-http`)
        .settings(
            `kyo-settings`,
            scalacOptions ++= Seq("-Wunused:imports")
        )
```

### Files to delete

(none in Phase 01; deletions land in Phase 02 / 03 / 04.)

### Public API additions

(none; this is a refactor. The new `CdpBackend` is `private[kyo]`; not user-facing.)

### Public API modifications

(none.)

### Tests

Total: 24. Lives in: `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala` (10 cases), `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` (14 cases for Phase 01-produced INVs).

1. `CdpBackendSmokeTest.scala`: init via inMemory transport returns live backend
   - Given: a `JsonRpcTransport.inMemory` pair, a `Browser.LaunchConfig` with `requestTimeout = 5.seconds`, and a fake server fiber that replies to `Browser.getVersion` with a `BrowserVersionResult("Headless/0", "0", "x86_64", "user-agent", "0.0")`
   - When: `CdpBackend.initUnscoped` returns
   - Then: backend.endpoint is non-null; `backend.sessionId == Absent`; `backend.dialogHandlers.get` is the empty Dict; the probe call returned successfully
   - Pins: INV-016 (WS-connect failure surfacing via Browser.getVersion probe), INV-017 (typed error recovery), Phase-01 init contract.

2. `CdpBackendSmokeTest.scala`: init fails fast with BrowserSetupException when probe returns Closed
   - Given: a `JsonRpcTransport.inMemory` pair whose receive-side immediately closes on the probe call
   - When: `CdpBackend.initUnscoped` runs against this transport
   - Then: the call aborts with `BrowserSetupException("WS handshake failed: ...")`; no live backend is returned
   - Pins: INV-016 (Q-002 ratification: probe converts Closed -> BrowserSetupException at init time).

3. `CdpBackendSmokeTest.scala`: send writes wire bytes that match the legacy CDP envelope shape
   - Given: a live in-memory backend with a fake server echoing back `{"id":<id>,"result":{...}}`
   - When: `backend.send[NavigateParams, NavigateResult]("Page.navigate", NavigateParams("https://example.com", Absent, Absent, Absent))` is invoked
   - Then: the outbound envelope decoded by the test's `JsonRpcCodec.Cdp` instance is `JsonRpcEnvelope.Request(id, "Page.navigate", Present(<params>), Absent)` (no `jsonrpc` field, params at top level, no extras)
   - Pins: INV-015 (per-sessionId routing via ExtrasEncoder, default no-session case), INV-011 (no manual JSON; codec-driven).

4. `CdpBackendSmokeTest.scala`: session-scoped backend stamps sessionId via ExtrasEncoder
   - Given: a live backend, `val tabBackend = backend.withSession(SessionId("abc"))`, a fake echo server
   - When: `tabBackend.send[NavigateParams, NavigateResult]("Page.navigate", params)` runs
   - Then: the outbound envelope's extras decoded by `JsonRpcCodec.Cdp` contains `Structure.Value.Record(Chunk("sessionId" -> Structure.Value.Str("abc")))`; the on-the-wire shape is `{"id":...,"method":"Page.navigate","params":{...},"sessionId":"abc"}`
   - Pins: INV-015 (per-sessionId routing via ExtrasEncoder).

5. `CdpBackendSmokeTest.scala`: inbound Page.javascriptDialogOpening notification routes via ctx.extras to the per-session handler
   - Given: a live backend, a registered dialog handler for `SessionId("s1")` returning `(true, "ok")`, a recorder ref attached for the same session
   - When: the fake server pushes `{"method":"Page.javascriptDialogOpening","params":{"type":"alert","message":"hi","url":"...","defaultPrompt":""},"sessionId":"s1"}` into inbound
   - Then: within 200ms, the dialogQueue contains exactly one `(true, "ok", Present(SessionId("s1")))`; the recorder ref's snapshot contains exactly one `DialogEvent("alert", "hi", true, "ok")`
   - Pins: INV-015 (ctx.extras read site), Phase-01 dialog routing contract.

6. `CdpBackendSmokeTest.scala`: Page.javascriptDialogOpening auto-dismisses when no handler is registered
   - Given: a live backend with empty `dialogHandlers`; a fake server pushing `{"method":"Page.javascriptDialogOpening","params":{...},"sessionId":"unknown"}`
   - When: 100ms elapses
   - Then: `dialogQueue` contains exactly one `(false, "", Present(SessionId("unknown")))`
   - Pins: design §13 risks (auto-dismiss test-stability invariant); Phase-01 dialog routing contract.

7. `CdpBackendSmokeTest.scala`: Runtime.executionContextCreated routes via ctx.extras to frame-event dispatcher
   - Given: a live backend, a frame handler registered for `SessionId("frame1")` capturing every event into an `AtomicRef[Chunk[CdpEvent.Generic]]`
   - When: server pushes `{"method":"Runtime.executionContextCreated","params":{...},"sessionId":"frame1"}` twice
   - Then: the AtomicRef holds exactly two `CdpEvent.Generic("Runtime.executionContextCreated", _, Present(SessionId("frame1")))` entries; the json paylod is reconstructed from the typed params
   - Pins: INV-015 (per-sessionId routing).

8. `CdpBackendSmokeTest.scala`: Page.downloadWillBegin routes via ctx.extras to download-event dispatcher
   - Given: a live backend, a download handler registered for `SessionId("dl")` capturing every event
   - When: server pushes `{"method":"Page.downloadWillBegin","params":{"guid":"g","frameId":"f","url":"u","suggestedFilename":"x.pdf"},"sessionId":"dl"}`
   - Then: the capture ref holds one `CdpEvent.Generic("Page.downloadWillBegin", _, Present(SessionId("dl")))`
   - Pins: INV-015.

9. `CdpBackendSmokeTest.scala`: close(gracePeriod) sequences endpoint.close, dialogQueue.close, dialogDrainer.interrupt
   - Given: a live backend with one outstanding inflight `send`, a registered dialog handler, a non-empty dialogQueue
   - When: `backend.close(2.seconds)` runs and the test awaits it
   - Then: `endpoint.awaitDrain` returns; `dialogQueue.isClosed` is true; `dialogDrainer.unsafe.done` is true; no resource leaks
   - Pins: Phase-01 close-ordering contract.

10. `CdpBackendSmokeTest.scala`: dialog drainer issues sendUnmatched with negative JsonRpcId.Num
    - Given: a live backend, an enqueued `(true, "answer", Present(SessionId("s")))`
    - When: drainer dequeues and writes
    - Then: the captured outbound envelope is `JsonRpcEnvelope.Request(JsonRpcId.Num(neg), "Page.handleJavaScriptDialog", Present(<{accept:true,promptText:"answer"}>), Present(<sessionId record>))` with `neg < 0` (concretely `neg == Int.MinValue.toLong`); `JsonRpcEndpoint.idStrategy.SequentialInt`'s independent allocator was not consulted (the id was caller-supplied via sendUnmatched)
    - Pins: INV-018 (negative-id sentinel disjoint from SequentialInt positive space).

11. `JsonRpcPortInvariantsSpec.scala`: INV-008 , Phase 01 commit lists CdpBackend.scala + CdpBackendSmokeTest.scala in the same change
    - Given: `git show --name-only HEAD` for the Phase-01 commit
    - When: filter to `kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala`
    - Then: every listed source has a matching `Cdp*Test.scala` or the named exception `JsonRpcPortInvariantsSpec.scala` in the same change
    - Pins: INV-008 (Rule 8c).

12. `JsonRpcPortInvariantsSpec.scala`: INV-009 , CdpBackend.scala package is `kyo.internal` and sole top-level type is `CdpBackend`
    - Given: `head -1 kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`
    - When: grep for `^package kyo\.internal$` and for top-level `final class|object`
    - Then: package matches; top-level types are `CdpBackend` (class) and `CdpBackend` (companion object), plus `CdpBackendOld` for Phase 01 coexistence (annotated `// flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02`)
    - Pins: INV-009.

13. `JsonRpcPortInvariantsSpec.scala`: INV-011 , no manual JSON in CdpBackend.scala
    - Given: the file content of `CdpBackend.scala`
    - When: grep for `Json\.parseString|String\.format.*"\{|\\"jsonrpc\\"|Pattern\.compile.*\\{|derives Json`
    - Then: ZERO matches
    - Pins: INV-011.

14. `JsonRpcPortInvariantsSpec.scala`: INV-012 , no `var` declarations for shared state introduced
    - Given: `git diff pre-port-tag..HEAD -- kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`
    - When: grep added lines for `^\+[[:space:]]*(private )?var [a-zA-Z]`
    - Then: ZERO matches (any exception must carry `// flow-allow:`)
    - Pins: INV-012.

15. `JsonRpcPortInvariantsSpec.scala`: INV-013 , every side-effect-producing call is inside `Sync.defer` (or a similar suspended boundary)
    - Given: the file content of `CdpBackend.scala`
    - When: for each `Sync.Unsafe.*` / `AllowUnsafe` / `Frame.internal` site, check for a `// Unsafe: <rationale>` line within 1 line above
    - Then: zero unannotated occurrences (since Phase 01 introduces none, the gate trivially passes; the gate is non-vacuous because it runs on the diff)
    - Pins: INV-013.

16. `JsonRpcPortInvariantsSpec.scala`: INV-014 , no em-dashes / no LLM-tells in Phase 01 diff
    - Given: `git diff pre-port-tag..HEAD -- kyo-browser/shared/src/`
    - When: grep added lines for `\x{2014}` or `\x{2013}` (em-dash / en-dash)
    - Then: ZERO matches
    - Pins: INV-014.

17. `JsonRpcPortInvariantsSpec.scala`: INV-015 , round-trip exercises ExtrasEncoder + ctx.extras
    - Given: a live in-memory backend, a `tabBackend = backend.withSession(SessionId("rt"))`, a frame handler registered for `SessionId("rt")`
    - When: tabBackend issues `send[NavigateParams, NavigateResult]("Page.navigate", ...)` and the server replies with a typed result envelope; AND server then pushes `Runtime.executionContextCreated` with `sessionId == "rt"`
    - Then: outbound envelope's `extras` field contains the sessionId record; inbound notification handler sees `ctx.extras == Present(Record(("sessionId" -> Str("rt"))))` and dispatches to the registered frame handler
    - Pins: INV-015 (the master smoke for per-sessionId routing).

18. `JsonRpcPortInvariantsSpec.scala`: INV-016 , Browser.getVersion probe converts Closed to BrowserSetupException
    - Given: a `JsonRpcTransport.inMemory` whose receive-side returns `Result.Failure(Closed("dead WS"))` on the first take
    - When: `CdpBackend.initUnscoped` runs
    - Then: the call aborts with `BrowserSetupException("WS handshake failed: ...")`; the message is non-empty
    - Pins: INV-016 (Q-002 ratification).

19. `JsonRpcPortInvariantsSpec.scala`: INV-017 , Closed-at-send surfaces as BrowserConnectionLostException
    - Given: a live backend whose endpoint has been forcibly closed
    - When: `backend.send[NavigateParams, NavigateResult]("Page.navigate", ...)` runs
    - Then: aborts with `BrowserConnectionLostException(s"Connection lost: ${...}", Present(<Closed>))`
    - Pins: INV-017 (Closed branch).

20. `JsonRpcPortInvariantsSpec.scala`: INV-017 , JsonRpcError surfaces as BrowserProtocolErrorException
    - Given: a live backend; the server replies with `{"id":1,"error":{"code":-32601,"message":"Method not found"}}`
    - When: `backend.send[NavigateParams, NavigateResult]("Page.navigate", ...)` runs
    - Then: aborts with `BrowserProtocolErrorException("Page.navigate", "Method not found")`
    - Pins: INV-017 (JsonRpcError branch).

21. `JsonRpcPortInvariantsSpec.scala`: INV-017 , Timeout surfaces as BrowserConnectionLostException
    - Given: a live backend with `requestTimeout = 100.millis`; the server never replies
    - When: `backend.send[NavigateParams, NavigateResult]("Page.navigate", ...)` runs
    - Then: within 500ms, aborts with `BrowserConnectionLostException(s"Request timeout: Page.navigate", Absent)`
    - Pins: INV-017 (Timeout branch).

22. `JsonRpcPortInvariantsSpec.scala`: INV-018 , dialogIdCounter starts at Int.MinValue and produces negative ids
    - Given: a live backend; dispatched dialog open
    - When: drainer wakes and computes the next id via `dialogIdCounter.getAndIncrement`
    - Then: the captured outbound envelope's `id` is `JsonRpcId.Num(Int.MinValue.toLong)` (negative); a follow-up regular `backend.send` produces an `id == JsonRpcId.Num(1L)` (positive, from SequentialInt's allocator)
    - Pins: INV-018 (disjointness of negative-id sentinel space and SequentialInt positive space).

23. `JsonRpcPortInvariantsSpec.scala`: INV-019 , no `Fiber.block` in Phase 01 diff
    - Given: `git diff pre-port-tag..HEAD -- kyo-browser/`
    - When: grep added lines for `Fiber\.block`
    - Then: ZERO matches
    - Pins: INV-019.

24. `JsonRpcPortInvariantsSpec.scala`: INV-020 , green-build on JVM, JS, Native at Phase 01 boundary
    - Given: `sbt kyo-browserJVM/Test/compile && sbt kyo-browserJS/Test/compile && sbt kyo-browserNative/Test/compile`
    - When: invocation completes
    - Then: each invocation exit code is 0; the targeted `*CdpBackendSmokeTest*` testOnly run is green on every platform
    - Pins: INV-020 (per-phase green-build).

### Consumed invariants

(none. Phase 01 produces all foundational invariants.)

### Produced invariants

- INV-005: every phase declares `platforms: [jvm, js, native]` (Phase 01 establishes this).
- INV-008: Rule 8c , Phase 01 ships `CdpBackend.scala` + `CdpBackendSmokeTest.scala` + `JsonRpcPortInvariantsSpec.scala` in the same commit.
- INV-009: Rule 8a/8b , `CdpBackend.scala` is `package kyo.internal`; top-level type is `CdpBackend` (sole class) + `CdpBackend` (companion). Phase 01 coexistence allows `CdpBackendOld` (annotated).
- INV-011: no manual JSON; all wire payloads use `derives Schema` + `Json.encode` / `Json.decode`.
- INV-012: no `var` for shared state under `package kyo.internal` introduced.
- INV-013: side effects in `Sync.defer`; no `AllowUnsafe` / `Frame.internal` / `Sync.Unsafe.*` introduced without `// Unsafe:` rationale.
- INV-014: no em-dashes / LLM-tells.
- INV-015: per-sessionId routing via ExtrasEncoder + JsonRpcCodec.Cdp.
- INV-016: WS-connect failure surfacing via Browser.getVersion probe (Q-002 ratification).
- INV-017: typed Abort recovery for Closed / JsonRpcError / Timeout at the CdpBackend.send boundary.
- INV-018: negative-id sentinel disjoint from IdStrategy.SequentialInt positive space.
- INV-019: no `Fiber.block`.
- INV-020: green-build on JVM, JS, Native.
- INV-022: no Co-Authored-By in commit trailer.
- INV-023: no `git push` from the campaign agent.
- INV-024: every audit-flag exception annotated with `// flow-allow: <rationale>`.

### Convention sweep (per Decision #25)

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt 'kyo-browserJVM/Test/compile' 'kyo-browserJS/Test/compile' 'kyo-browserNative/Test/compile' 'kyo-browserJVM/testOnly *CdpBackendSmokeTest*' 'kyo-browserJVM/testOnly *JsonRpcPortInvariantsSpec*'` then re-run the testOnly on `kyo-browserJS` and `kyo-browserNative` SEQUENTIALLY (per INV-021).

---

## Phase 02: Cut over Browser / BrowserTab / BrowserSnapshot to CdpBackend; delete CdpClient

Depends on: Phase 01 because (a) the new `CdpBackend` runtime class must exist and be tested, (b) the `Browser.getVersion` probe must already convert connect failures into `BrowserSetupException`, (c) the build-graph already declares the kyo-jsonrpc / kyo-jsonrpc-http deps.

Phase 02 renames `BrowserTab.client: CdpClient -> BrowserTab.backend: CdpBackend`, updates every read of `tab.client.<field>` to `tab.backend.<field>` across the kyo-browser sources, rewrites each typed wrapper in the `CdpBackend` companion object so it calls `backend.send[Params, Result](method, params)` directly (no `CdpSender`, no `decodeOrFail`), and DELETES `CdpClient.scala` outright (627 LoC). The `CdpSender` trait is gone; the `CdpBackendOld` object introduced in Phase 01 is gone; the `decodeOrFail` helper is gone.

After Phase 02 the production code path is the new `CdpBackend` exclusively. Wire-layer test files (`CdpClientLifecycleTest`, `CdpClientTest`, `CdpClientDecoderTest`) still exist with their pre-port bodies referencing `CdpClient`, so they break compile. To keep Phase 02 atomic ("compiles + tests green"), the broken tests are TEMPORARILY redirected to the new symbol with a one-line per-file delegation (`val backend = CdpBackend.init(...).map(b => b.asInstanceOf[CdpClient])` is NOT permitted per `feedback_no_casts`; instead, the test bodies switch every `val client = CdpClient.init(...)` to `val client = CdpBackend.init(...)` and every `client.<field>` reference is patched in-place during Phase 02). Phase 03 handles the proper test-file-rename / delete / rewrite accounting.

### Files to produce

(none. Phase 02 is a delete-and-replace phase. Net file count drops by 1: `CdpClient.scala` deleted; `CdpBackend.scala` modified in place; no new sources.)

### Files to modify

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`: delete the `CdpBackendOld` Phase-01-coexistence object; rewrite every typed wrapper to call `backend.send[Params, Result](method, params)`; delete `decodeOrFail`; delete the `CdpSender` trait (which lived in `CdpClient.scala`).

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala ; BEFORE (Phase-01 state)
// - The runtime class CdpBackend (introduced in Phase 01) is unchanged.
// - The companion object CdpBackend keeps its constants + init/initUnscoped.
// - A separate object CdpBackendOld holds the 28 typed wrappers (taking
//   a `CdpSender` parameter and calling `decodeOrFail` on the wire result).
// ... 228 LoC of CdpBackendOld ...

// kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala ; AFTER (Phase-02 state)
// - The runtime class CdpBackend is unchanged from Phase 01.
// - The companion object CdpBackend keeps its constants + init/initUnscoped,
//   AND now hosts the 28 typed wrappers as two-line bodies on `backend: CdpBackend`.
//   Example replacement for the navigate wrapper:
private[kyo] def navigate(backend: CdpBackend, params: NavigateParams)(using
    Frame
): Unit < (Async & Abort[BrowserReadException]) =
    backend.sendUnit[NavigateParams]("Page.navigate", params)

// Example replacement for a wrapper that previously called decodeOrFail:
private[kyo] def captureScreenshot(backend: CdpBackend, params: ScreenshotParams)(using
    Frame
): ScreenshotResult < (Async & Abort[BrowserReadException]) =
    backend.send[ScreenshotParams, ScreenshotResult]("Page.captureScreenshot", params)

// All 28 wrappers transcribed in the same two-line shape; the
// CdpBackendOld object is deleted in full.
```

- `kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala`: rename the `client: CdpClient` field to `backend: CdpBackend`; rename the derived `session = client.withSession(sessionId)` to `session = backend.withSession(sessionId)`; update the per-tab frame-event dispatcher registration site.

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala ; BEFORE (around line 22-93)
final private[kyo] case class BrowserTab(
    // ...
    val client: CdpClient,
    // ...
):
    val session: CdpClient = client.withSession(sessionId)
    // ...
    // (line 93)
    tab.client.frameEventDispatchers.updateAndGet(_.update(key, handler)).andThen(
        Scope.ensure(tab.client.frameEventDispatchers.updateAndGet(_.remove(key)).unit)
    )

// kyo-browser/shared/src/main/scala/kyo/internal/BrowserTab.scala ; AFTER
final private[kyo] case class BrowserTab(
    // ...
    val backend: CdpBackend,
    // ...
):
    val session: CdpBackend = backend.withSession(sessionId)
    // ...
    tab.backend.frameEventDispatchers.updateAndGet(_.update(key, handler)).andThen(
        Scope.ensure(tab.backend.frameEventDispatchers.updateAndGet(_.remove(key)).unit)
    )
```

- `kyo-browser/shared/src/main/scala/kyo/Browser.scala`: rename every internal reference to `CdpClient` -> `CdpBackend` (3 sites: `CdpClient.init` at lines 258, 273; `client.dialogHandlers` at line 2157; `client.dialogRecorders` at line 2193; `client.downloadEventDispatchers` at line 2533). Public surface byte-identical per INV-001 / INV-002.

```scala
// kyo-browser/shared/src/main/scala/kyo/Browser.scala ; BEFORE (line 258, 273 region)
for
    cdpClient <- CdpClient.init(wsUrl, cfg)
    // ...

// kyo-browser/shared/src/main/scala/kyo/Browser.scala ; AFTER
for
    cdpClient <- CdpBackend.init(wsUrl, cfg)
    // ...

// BEFORE (line 2157 region)
tab.client.dialogHandlers.updateAndGet(_.update(sid.value, (accept, prompt))).andThen(
    Scope.ensure(tab.client.dialogHandlers.updateAndGet(_.remove(sid.value)).unit)
)

// AFTER
tab.backend.dialogHandlers.updateAndGet(_.update(sid.value, (accept, prompt))).andThen(
    Scope.ensure(tab.backend.dialogHandlers.updateAndGet(_.remove(sid.value)).unit)
)

// BEFORE (line 2193 region)
tab.client.dialogRecorders.updateAndGet(_.update(sid.value, ref)) ...

// AFTER
tab.backend.dialogRecorders.updateAndGet(_.update(sid.value, ref)) ...

// BEFORE (line 2533 region)
tab.client.downloadEventDispatchers.updateAndGet(_.update(sid.value, dispatcher)) ...

// AFTER
tab.backend.downloadEventDispatchers.updateAndGet(_.update(sid.value, dispatcher)) ...
```

- `kyo-browser/shared/src/main/scala/kyo/internal/BrowserSnapshot.scala`: rename `tab.client` -> `tab.backend` at every site (mechanical search-and-replace; ~6 occurrences per exploration).

```scala
// BrowserSnapshot.scala ; BEFORE
tab.client.send("Page.captureScreenshot", params).map(...)
// BrowserSnapshot.scala ; AFTER
tab.backend.send[ScreenshotParams, ScreenshotResult]("Page.captureScreenshot", params).map(...)
// (Where the wrapper goes through CdpBackend.captureScreenshot the call site
//  reads `CdpBackend.captureScreenshot(tab.backend, params)`; identical to today's
//  CdpBackend.captureScreenshot(tab.client, params) modulo the field name.)
```

- `kyo-browser/shared/src/main/scala/kyo/internal/BrowserEval.scala`: rewrite `evalJs` / `evalJsAwaiting` to call `backend.send[EvalParams, EvalResult]("Runtime.evaluate", params)` directly and project via the typed decoder. Each call site converts from "raw String wire" to typed `EvalResult`.

```scala
// BrowserEval.scala ; BEFORE (sketch)
def evalJs(tab: BrowserTab, js: String, awaitPromise: Boolean = false)(using Frame): String < ... =
    tab.session.send(CdpBackend.RuntimeEvaluateMethod, EvalParams(js, awaitPromise, ...))
        .map(wire => CdpEvalDecoder.parseAndExtractEvalValue(wire))

// BrowserEval.scala ; AFTER
def evalJs(tab: BrowserTab, js: String, awaitPromise: Boolean = false)(using Frame): String < ... =
    Abort.recover[BrowserReadException] {
        case _: BrowserDecodingException => ""  // legacy degrade-to-empty for malformed-envelope class only
        case other => Abort.fail(other)
    } {
        tab.session.send[EvalParams, EvalResult](CdpBackend.RuntimeEvaluateMethod, EvalParams(js, awaitPromise, ...))
            .map { (env: EvalResult) =>
                env.exceptionDetails match
                    case Present(ex) =>
                        Abort.fail(BrowserProtocolErrorException.internalEvalFailed(ExceptionDetailsFormat.format(ex)))
                    case Absent =>
                        CdpEvalDecoder.extractEvalValue(env)
            }
    }
```

The next 10 stability-layer files get a mechanical `tab.client` -> `tab.backend` and `CdpClient` -> `CdpBackend` rename at every site. Bodies remain byte-equivalent modulo the rename per INV-007. The flow-allow annotation is NOT required for this rename: it is a `private[kyo]` field-name change, not an audit-flag exception. The INV-007 file list is revised in Phase 02 to drop the 6 files that cite `tab.client` directly (Actionability, NavigationWatcher, MutationSettlement, StabilitySampler, BrowserNetworkTracker, Resolver) and keep the truly sha-identical files (BrowserLauncher, SharedChrome, JsStringUtil, Selector, IFrame, Image, Key, KeyModifiers, CookieWire, ProbesJs, ChromeDownloader if unchanged, cdp/Accessibility if unchanged).

- `kyo-browser/shared/src/main/scala/kyo/internal/BrowserAssertion.scala`: mechanical `tab.client` -> `tab.backend` rename.

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/BrowserAssertion.scala ; BEFORE
tab.client.<accessorOrField>(...)
// AFTER
tab.backend.<accessorOrField>(...)
```

- `kyo-browser/shared/src/main/scala/kyo/internal/Actionability.scala`: mechanical `CdpClient` -> `CdpBackend` and `tab.client` -> `tab.backend` rename at every site.

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/Actionability.scala ; BEFORE
CdpBackend.runtimeEvaluate(tab.client, EvalParams(js, awaitPromise = true, ...))
// AFTER
CdpBackend.runtimeEvaluate(tab.backend, EvalParams(js, awaitPromise = true, ...))
```

- `kyo-browser/shared/src/main/scala/kyo/internal/MutationSettlement.scala`: mechanical `tab.client` -> `tab.backend` rename.

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/MutationSettlement.scala ; BEFORE
tab.client.<accessorOrField>(...)
// AFTER
tab.backend.<accessorOrField>(...)
```

- `kyo-browser/shared/src/main/scala/kyo/internal/NavigationWatcher.scala`: mechanical `tab.client` -> `tab.backend` rename.

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/NavigationWatcher.scala ; BEFORE
tab.client.<accessorOrField>(...)
// AFTER
tab.backend.<accessorOrField>(...)
```

- `kyo-browser/shared/src/main/scala/kyo/internal/StabilitySampler.scala`: mechanical `tab.client` -> `tab.backend` rename.

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/StabilitySampler.scala ; BEFORE
tab.client.<accessorOrField>(...)
// AFTER
tab.backend.<accessorOrField>(...)
```

- `kyo-browser/shared/src/main/scala/kyo/internal/BrowserNetworkTracker.scala`: mechanical `tab.client` -> `tab.backend` rename.

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/BrowserNetworkTracker.scala ; BEFORE
tab.client.<accessorOrField>(...)
// AFTER
tab.backend.<accessorOrField>(...)
```

- `kyo-browser/shared/src/main/scala/kyo/internal/Resolver.scala`: mechanical `CdpClient` -> `CdpBackend` and `tab.client` -> `tab.backend` rename.

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/Resolver.scala ; BEFORE
tab.client.<accessorOrField>(...)
// AFTER
tab.backend.<accessorOrField>(...)
```

- `kyo-browser/shared/src/main/scala/kyo/internal/ChromeDownloader.scala`: mechanical `tab.client` -> `tab.backend` rename.

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/ChromeDownloader.scala ; BEFORE
tab.client.<accessorOrField>(...)
// AFTER
tab.backend.<accessorOrField>(...)
```

- `kyo-browser/shared/src/main/scala/kyo/internal/cdp/PageDownload.scala`: mechanical `CdpClient` -> `CdpBackend` rename.

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/cdp/PageDownload.scala ; BEFORE
import kyo.internal.CdpClient
// references to CdpClient as a parameter type
// AFTER
import kyo.internal.CdpBackend
// references to CdpBackend as a parameter type
```

- `kyo-browser/shared/src/main/scala/kyo/internal/cdp/Accessibility.scala`: mechanical `CdpClient` -> `CdpBackend` rename (only if the file references `CdpClient`; verify per design §7 KEEP verdict).

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/cdp/Accessibility.scala ; BEFORE
import kyo.internal.CdpClient
// references to CdpClient as a parameter type (if any)
// AFTER
import kyo.internal.CdpBackend
// references to CdpBackend as a parameter type (if any)
```

### Files to delete

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpClient.scala` (627 LoC). Reason: every primitive it owned (Exchange, inFlight, drainSignal, cdpMeter, relay, dialogDrainer, decodeCdpMessage, fallbackDecode, plus six envelope/event case classes and `CdpEvent`, `CdpError`, `CdpSender`) is now owned by `kyo-jsonrpc` or by the new `CdpBackend` runtime class. The Phase-01 coexistence interval is closed.

### Public API additions

(none.)

### Public API modifications

(none. `kyo.Browser` and `kyo.BrowserException` public surfaces are byte-identical per INV-001 / INV-002. The Phase-02 grep-audit gate runs:

```bash
diff \
  <(git show pre-port-tag:kyo-browser/shared/src/main/scala/kyo/Browser.scala         | grep -nE 'class|object|def|val|enum|case class') \
  <(grep -nE 'class|object|def|val|enum|case class'         kyo-browser/shared/src/main/scala/kyo/Browser.scala)
```

Empty diff is the gate.)

### Tests

Total: 11. Lives in: `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` (test bodies for Phase-02-produced INVs).

1. `JsonRpcPortInvariantsSpec.scala`: INV-001 , `kyo.Browser` public method signatures byte-identical pre-port vs post-port
   - Given: `git show pre-port-tag:kyo-browser/shared/src/main/scala/kyo/Browser.scala` piped through `grep -nE 'class|object|def|val|enum|case class'`
   - When: same grep on HEAD post-Phase-02
   - Then: byte-identical output (line-equal `diff` exits 0)
   - Pins: INV-001 (public surface byte-identical).

2. `JsonRpcPortInvariantsSpec.scala`: INV-002 , `kyo.BrowserException` ADT byte-identical
   - Given: `git show pre-port-tag:kyo-browser/shared/src/main/scala/kyo/BrowserException.scala` piped through the same grep
   - When: same grep on HEAD post-Phase-02
   - Then: byte-identical output; constructor arity of each Browser*Exception is unchanged
   - Pins: INV-002.

3. `JsonRpcPortInvariantsSpec.scala`: INV-003 , `BrowserTab` / `BrowserSnapshot` public symbols byte-identical post-rename
   - Given: grep of public symbols (filter out `private[`) pre-port vs HEAD
   - When: diff
   - Then: diff of public-symbol grep output between pre-port-tag and HEAD is empty (zero added, zero removed, zero context lines); the `private[kyo] val client: CdpClient` -> `private[kyo] val backend: CdpBackend` rename is invisible because the grep filters out private symbols
   - Pins: INV-003.

4. `JsonRpcPortInvariantsSpec.scala`: INV-007 , stability-layer files byte-identical
   - Given: for each `f` in the updated stability-layer file list (BrowserLauncher, SharedChrome, JsStringUtil, Selector, IFrame, Image, Key, KeyModifiers, CookieWire, ProbesJs, ChromeDownloader, cdp/Accessibility, BrowserLauncherPlatform), compute `sha256` pre-port and post-port
   - When: compare
   - Then: every sha256 matches
   - Pins: INV-007 (revised file list per Phase-02 reality).

5. `JsonRpcPortInvariantsSpec.scala`: INV-010 , no shim / @deprecated / parallel-API artifact post-Phase-02
   - Given: HEAD source tree under `kyo-browser/shared/src/main/scala/kyo/`
   - When: `grep -lE 'CdpClient|CdpEnvelope|CdpWireMessage|CdpReply|CdpEventParams|FallbackIdEnvelope|CdpSender|decodeOrFail'`
   - Then: zero matches; also `grep -lE '@deprecated|// shim|// migration|legacy'` returns zero matches across Phase-02 added lines
   - Pins: INV-010 (no-backcompat).

6. `JsonRpcPortInvariantsSpec.scala`: INV-008 , Phase-02 commit lists `CdpBackend.scala` modification with companion tests
   - Given: `git show --name-only HEAD~0` for the Phase-02 commit
   - When: filter to `kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala`
   - Then: every modified source has a matching test in the same change OR appears in the pre-existing `kyo-browser/shared/src/test/scala/kyo/internal/Cdp*.scala` set
   - Pins: INV-008.

7. `JsonRpcPortInvariantsSpec.scala`: INV-009 , post-Phase-02 every `Cdp*.scala` declares `package kyo.internal` and sole top-level type matches basename
   - Given: each file in `kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala`
   - When: `head -1` matches `^package kyo\.internal$` AND single top-level `class|object|trait|enum`
   - Then: every file passes; `CdpBackendOld` (Phase-01 coexistence) is GONE post-Phase-02
   - Pins: INV-009.

8. `JsonRpcPortInvariantsSpec.scala`: INV-011 , no manual JSON post-Phase-02
   - Given: HEAD post-Phase-02
   - When: grep across `kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala` for `Json\.parseString|String\.format.*"\{|\\"jsonrpc\\"|Pattern\.compile.*\\{|derives Json`
   - Then: zero matches
   - Pins: INV-011.

9. `JsonRpcPortInvariantsSpec.scala`: INV-012 , no `var` for shared state in Phase-02 diff
   - Given: `git diff pre-port-tag..HEAD -- kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala`
   - When: grep added lines for `^\+[[:space:]]*(private )?var [a-zA-Z]`
   - Then: zero matches
   - Pins: INV-012.

10. `JsonRpcPortInvariantsSpec.scala`: INV-013 , no `AllowUnsafe` / `Frame.internal` introduced without rationale
    - Given: `git diff pre-port-tag..HEAD -- kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala`
    - When: grep added lines for `AllowUnsafe|Frame\.internal|Sync\.Unsafe\.`
    - Then: every occurrence has a same-or-preceding-line `// Unsafe: <rationale>` comment, AND every `// Unsafe:` is paired with a `// flow-allow: <rationale>` per INV-024 conventions
    - Pins: INV-013, INV-024.

11. `JsonRpcPortInvariantsSpec.scala`: INV-014 , no em-dashes / LLM-tells in Phase-02 diff
    - Given: `git diff pre-port-tag..HEAD -- kyo-browser/shared/`
    - When: grep added lines for `\x{2014}|\x{2013}`
    - Then: zero matches
    - Pins: INV-014.

### Consumed invariants

- INV-015 (per-sessionId routing via ExtrasEncoder, established Phase 01)
- INV-016 (Browser.getVersion probe, established Phase 01)
- INV-017 (typed Abort recovery, established Phase 01)

### Produced invariants

- INV-001: `kyo.Browser` public surface byte-identical.
- INV-002: `kyo.BrowserException` ADT byte-identical.
- INV-003: `BrowserTab` / `BrowserSnapshot` public surface byte-identical (private field rename invisible).
- INV-007: stability-layer files byte-identical (subset that never referenced `tab.client`).
- INV-008: Rule 8c , Phase 02 modifies `CdpBackend.scala` and the test file is `CdpBackendSmokeTest.scala` (introduced Phase 01) plus extended `JsonRpcPortInvariantsSpec.scala`.
- INV-009: Rule 8a/8b , only the runtime class `CdpBackend` + companion object remain in `CdpBackend.scala`; `CdpBackendOld` is deleted; basename rule holds.
- INV-010: no-backcompat , `CdpClient.scala` deleted (627 LoC); no shim, no parallel API.
- INV-011: no manual JSON.
- INV-012: no `var` for shared state in this phase's diff.
- INV-013: side effects inside `Sync.defer`; no new `AllowUnsafe` / `Frame.internal` without rationale.
- INV-014: no em-dashes / LLM-tells.
- INV-019: no `Fiber.block`.
- INV-020: green-build on JVM, JS, Native (compile only at this phase; the wire-layer tests get patched in-place; the full suite still passes modulo the wire-layer cases that Phase 03 properly accounts for).
- INV-022: no Co-Authored-By in commit trailer.
- INV-023: no `git push`.
- INV-024: `// flow-allow:` annotations carried.

### Convention sweep (per Decision #25)

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt 'kyo-browserJVM/Test/compile' 'kyo-browserJS/Test/compile' 'kyo-browserNative/Test/compile' 'kyo-browserJVM/testOnly *JsonRpcPortInvariantsSpec*' 'kyo-browserJVM/testOnly *CdpBackendSmokeTest*'`. Then SEQUENTIALLY (INV-021): `kyo-browserJS/testOnly *JsonRpcPortInvariantsSpec*`, then `kyo-browserNative/testOnly *JsonRpcPortInvariantsSpec*`. Full suite is left to Phase 03 for the test-rewrite accounting.

---

## Phase 03: Test-rewrite accounting (DELETE / RENAME / REWRITE per Q-005 / INV-004)

Depends on: Phase 02 because (a) the new `CdpBackend` runtime class is the production code path, (b) `CdpClient.scala` is deleted so dangling references in the wire-layer tests now block compile and force their rename/delete/rewrite.

Phase 03 implements Q-005's per-test accounting verbatim:

- **DELETE**:
  - `CdpClientTest.scala` (15 cases) , `submit`'s inFlight counter and cdpMeter cap tests duplicate `kyo-jsonrpc`'s `JsonRpcEndpointImpl` lifecycle tests verbatim. Engine-side coverage: `kyo-jsonrpc/shared/src/test/scala/kyo/internal/JsonRpcEndpointImplLifecycleSpec.scala` (maxInFlight, drainSignal, awaitDrain).
  - `CdpClientDecoderTest.scala` (7 cases) , `decodeCdpMessage` event-routing branches are now four `JsonRpcMethod.notification` handlers, exercised by `CdpBackendLifecycleTest` + `JsonRpcPortInvariantsSpec::INV-015`. Engine-side malformed routing: `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcCodecCdpSpec.scala` (Malformed-with-id recovery, lines 228-230).

- **RENAME + shrink**: `CdpClientLifecycleTest.scala` (25 cases, 1262 LoC) -> `CdpBackendLifecycleTest.scala`.
  - DELETED ~10 cases that probe engine-internal state directly (`client.inFlight`, `client.drainSignal`, `client.cdpMeter` getters that no longer exist):
    - drainSignal 0->1 transition (engine-duplicate; covered by `kyo-jsonrpc/.../JsonRpcEndpointImplLifecycleSpec.scala::drainSignal swap`)
    - drainSignal 1->0 transition (engine-duplicate)
    - inFlight counter increments/decrements (engine-duplicate; `JsonRpcEndpointImplLifecycleSpec::maxInFlight cap`)
    - cdpMeter zero-permit blocks send (engine-duplicate; `JsonRpcEndpointImplLifecycleSpec::semaphore blocks when full`)
    - cdpMeter permits released on response (engine-duplicate)
    - exchange.events stream consumer race on multi-tab (engine-duplicate; `JsonRpcEndpointSpec::notification dispatch`)
    - close path closes inbound channel (engine-duplicate; `JsonRpcEndpointImplLifecycleSpec::close cascades`)
    - close path closes outbound channel (engine-duplicate)
    - relay fiber interrupt on closeNow (engine-duplicate)
    - relay fiber consumes connect failure (engine-duplicate at the WS-bridge level; `kyo-jsonrpc-http/.../JsonRpcHttpTransportWebSocketSpec.scala`)
  - KEPT ~15 cases that probe kyo-browser-owned diagnostic state / dialog drainer / dispatcher table behavior:
    - `lastEvaluateParams` set on every Runtime.evaluate call (kyo-browser-only diagnostic)
    - `lastEvaluateParams` not set on other CDP methods
    - dialog drainer issues fire-and-forget Page.handleJavaScriptDialog with negative id
    - dialog drainer continues after dialogQueue close
    - dialog drainer respects per-session sessionId in extras
    - dialog auto-dismiss with no handler registered (test-stability invariant)
    - dialog accept handler registered for sessionId returns (true, prompt)
    - dialog reject handler returns (false, "")
    - dialog recorder appends to per-session ref
    - frame-event dispatcher routes by sessionId
    - frame-event dispatcher removes handler on scope exit
    - download-event dispatcher routes by sessionId
    - download-event dispatcher add/remove
    - withSession returns backend that shares dispatcher tables and endpoint
    - close(gracePeriod) sequences endpoint.close -> dialogQueue.close -> dialogDrainer.interrupt

- **REWRITE bodies**: `CdpBackendTest.scala` (41 cases, 669 LoC). Same 41 case headings; each body switches from a fake `CdpSender` to a real `JsonRpcTransport.inMemory` pair + a real `CdpBackend` constructed via `CdpBackend.initUnscoped` (with a stub `Browser.getVersion` reply on the inMemory transport). Net behavior: same 41 typed-wrapper round-trip checks, now exercised end-to-end through the JsonRpcEndpoint.

- **REWRITE bodies**: `BrowserWireDecodeFailureTest.scala` (6 cases, 117 LoC). Bodies switch from a fake `CdpSender` to `JsonRpcTransport.inMemory`; each of the 6 cases pushes one of these malformed wire shapes into inbound: (1) missing `id` field on a response envelope; (2) garbled JSON (unterminated string); (3) `error` value typed as a string instead of an object; (4) `result` shaped as the wrong typed payload (kyo-schema rejection); (5) Notification envelope with a `method` not declared by any handler; (6) Response envelope with an `id` that has no matching outstanding request. Each case asserts that the awaiting `CdpBackend.send` raises `BrowserProtocolErrorException.decodeFailure(method, reason)` (engine routes via `JsonRpcEnvelope.Malformed(id, reason, raw)`).

- **KEEP unchanged**:
  - `CdpParamsRoundTripTest.scala` (15 cases, 359 LoC): pure `Schema` round-trip; no wire dependency.
  - `CdpTypesTest.scala` (5 cases, 29 LoC): opaque-id constructors.
  - `CdpTypesSchemaFailureTest.scala` (6 cases, 63 LoC): Schema rejection on malformed wire shapes.
  - `CdpEvalDecoderTest.scala` (15 cases, 220 LoC): `RemoteObject` variant decoding.

- **Net delta**: -32 cases. Pre-port shared count was 1308; post-Phase-03 shared count is 1276 (= 1308 - 15 - 7 - 10).

The Phase 03 commit message MUST enumerate every deleted test case with its engine-side equivalent test file + spec name. The supervisor's `flow-verify` at Phase 03 boundary validates the test-count accounting against the prose enumeration; INV-004's verification gate runs the sbt counts on all three platforms.

### Files to produce

(none , Phase 03 is a test-file delete/rename/rewrite phase. The `Cdp*` test files exist; bodies change. `JsonRpcPortInvariantsSpec.scala` gets extended for Phase-03-produced INVs.)

### Files to modify

- `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala`: extend with the Phase-03 INV smoke tests (INV-004 test-count accounting, INV-006 no test-demoted-to-platform).

  *(Test file; no plan-side code block per the FLOW code-in-plan contract.)*

### Files to delete

- `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientTest.scala` (15 cases, 286 LoC). Reason: `submit`'s inFlight counter and cdpMeter cap are engine-internal in the new design; engine-side coverage in `kyo-jsonrpc/shared/src/test/scala/kyo/internal/JsonRpcEndpointImplLifecycleSpec.scala`.
- `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientDecoderTest.scala` (7 cases, 179 LoC). Reason: `decodeCdpMessage` event-routing now lives in `JsonRpcMethod.notification` handler bodies; engine-side malformed routing in `kyo-jsonrpc/.../JsonRpcCodecCdpSpec.scala`.

### Files to rename + shrink

- `kyo-browser/shared/src/test/scala/kyo/internal/CdpClientLifecycleTest.scala` -> `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendLifecycleTest.scala`. Surface (signature, package, top-level type basename) post-rename: the renamed file declares `package kyo.internal`, imports `kyo.*`, and has a single top-level test class `CdpBackendLifecycleTest` extending the existing `BrowserTest` base class. 15 cases are kept; each body refers to `backend` (CdpBackend) instead of `client` (CdpClient). The body scenarios are the 15 KEPT items enumerated in the Phase-03 RENAME + shrink narrative above (lastEvaluateParams Runtime.evaluate set, lastEvaluateParams not set on other methods, dialog drainer fire-and-forget with negative id, dialog drainer continues after queue close, dialog drainer respects sessionId extras, auto-dismiss with no handler, accept handler returns (true, prompt), reject handler returns (false, ""), dialog recorder appends per-session, frame-event dispatcher routes by sessionId, frame-event dispatcher removes on scope exit, download-event dispatcher routes by sessionId, download-event dispatcher add/remove, withSession shares tables, close sequences endpoint.close -> dialogQueue.close -> drainer.interrupt). Per the code-in-plan contract, no fenced code block for the test class is given here; bodies live in the rewritten test file at impl time.

### Files to rewrite (test bodies)

- `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendTest.scala` (41 cases): each typed-wrapper test now uses `JsonRpcTransport.inMemory` + real `CdpBackend`.
- `kyo-browser/shared/src/test/scala/kyo/internal/BrowserWireDecodeFailureTest.scala` (6 cases): drives malformed wire through inMemory transport.

  *(Test files; bodies described in `### Tests` per the code-in-plan contract.)*

### Public API additions

(none.)

### Public API modifications

(none.)

### Tests

Total: 79 across the post-Phase-03 set (15 kept in `CdpBackendLifecycleTest` + 41 in `CdpBackendTest` + 6 in `BrowserWireDecodeFailureTest` + 15 in `CdpParamsRoundTripTest` + 5 in `CdpTypesTest` + 6 in `CdpTypesSchemaFailureTest` + 15 in `CdpEvalDecoderTest` = 103 wire-layer cases) PLUS the 6 new Phase-03 INV smoke tests in `JsonRpcPortInvariantsSpec.scala`. Of those, the 6 below are NEW (the rest are rewritten-body or unchanged).

The narrative below enumerates only the NEW scenarios. The rewritten-body scenarios keep their pre-port leaf names; their `Given/When/Then/Pins` shift exclusively because the driver changed from `CdpSender` to `JsonRpcTransport.inMemory`. Per Q-005 the impl agent transcribes each leaf name and re-writes the body; the plan does not need to re-enumerate the 103 wire-layer leaves verbatim , the test count is the contract.

1. `JsonRpcPortInvariantsSpec.scala`: INV-004 , Shared test-count parity after Phase 03
   - Given: `sbt 'show kyo-browserJVM/Test/definedTests' | wc -l` post-Phase-03
   - When: compare to the expected count
   - Then: count is exactly 1276 shared + 4 platform-specific (JVM-only); JS reports 1276 + 4 JS-only; Native reports 1276 + 4 Native-only
   - Pins: INV-004 (cross-platform test-count parity, net -32 vs pre-port).

2. `JsonRpcPortInvariantsSpec.scala`: INV-006 , no test file demoted to `jvm/`, `js/`, `native/`
   - Given: `git diff pre-port-tag..HEAD --stat -- kyo-browser/jvm/src/test/ kyo-browser/js/src/test/ kyo-browser/native/src/test/`
   - When: count added/modified test files
   - Then: zero added or modified test files beyond the existing 4 platform-specific cases
   - Pins: INV-006 (tests stay shared).

3. `JsonRpcPortInvariantsSpec.scala`: INV-008 , Phase 03 commit lists renamed test file + deleted test files
   - Given: `git show --name-only HEAD` for the Phase-03 commit
   - When: filter to `kyo-browser/shared/src/test/scala/kyo/internal/Cdp*.scala`
   - Then: `CdpClientTest.scala` and `CdpClientDecoderTest.scala` appear as deletions; `CdpClientLifecycleTest.scala` appears as a deletion paired with `CdpBackendLifecycleTest.scala` as an addition; `CdpBackendTest.scala` and `BrowserWireDecodeFailureTest.scala` appear as modifications
   - Pins: INV-008 (Rule 8c source + matching test in same phase commit).

4. `JsonRpcPortInvariantsSpec.scala`: INV-014 , no em-dashes / LLM-tells in Phase 03 diff (especially in rewritten test bodies)
   - Given: `git diff pre-port-tag..HEAD -- kyo-browser/shared/src/test/`
   - When: grep added lines for `\x{2014}|\x{2013}`
   - Then: zero matches
   - Pins: INV-014.

5. `JsonRpcPortInvariantsSpec.scala`: INV-020 , full suite green on JVM, JS, Native after Phase 03
   - Given: `sbt kyo-browserJVM/Test` (then JS, then Native, SEQUENTIALLY per INV-021)
   - When: each platform's full test suite runs
   - Then: each platform reports 1276 shared + 4 platform-specific cases GREEN; sbt exit code is 0
   - Pins: INV-020 (green-build), INV-004 (count parity).

6. `JsonRpcPortInvariantsSpec.scala`: INV-022 / INV-023 / INV-024 , commit-message hygiene at Phase 03 boundary
   - Given: `git log -1 --format=%B` for the Phase-03 commit
   - When: grep for `Co-Authored-By` (INV-022), and an audit log search for any `git push` between Phase 02 commit and Phase 03 commit (INV-023), and a grep for `// flow-allow:` over the Phase-03 diff (INV-024)
   - Then: zero Co-Authored-By trailers; zero git push events; every audit-flagged exception in the Phase-03 diff carries a `// flow-allow: <rationale>` comment
   - Pins: INV-022, INV-023, INV-024.

### Consumed invariants

- INV-001 (Browser public surface byte-identical, established Phase 02)
- INV-002 (BrowserException ADT byte-identical, established Phase 02)
- INV-003 (BrowserTab/BrowserSnapshot public surface byte-identical, established Phase 02)
- INV-007 (stability-layer files byte-identical, established Phase 02)
- INV-010 (no-backcompat, established Phase 02)
- INV-015 (per-sessionId routing, established Phase 01)
- INV-017 (typed Abort recovery, established Phase 01)
- INV-018 (negative-id sentinel disjoint, established Phase 01)

### Produced invariants

- INV-004: cross-platform test-count parity (1276 shared + 4 platform-specific).
- INV-006: no test file demoted out of `shared/` to a platform folder.
- INV-008: Rule 8c , Phase 03's rename/delete/rewrite all in one commit.
- INV-014: no em-dashes / LLM-tells.
- INV-020: green-build on JVM, JS, Native (full suite this phase).
- INV-022, INV-023, INV-024.

### Convention sweep (per Decision #25)

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt kyo-browserJVM/Test` (then `kyo-browserJS/Test`, then `kyo-browserNative/Test`, SEQUENTIALLY per INV-021). Full-suite gate this phase. Each invocation must report 1276 shared + 4 platform-specific cases green.

---

## Phase 04: Delete obsolete CdpWire envelope/error case classes; keep CDP-specific decoders

Depends on: Phase 03 because (a) the wire-layer tests have already been rewritten to drive payloads through `JsonRpcTransport.inMemory` and no longer reference the legacy envelopes, (b) `BrowserEval.evalJs` no longer calls `CdpEvalEnvelope.decodeEvalEnvelope`.

Phase 04 deletes the legacy `CdpWire.scala` envelopes and error ADTs that survived Phase 02 (because Phase 02 only deleted what `CdpClient.scala` exclusively owned). Concretely the case classes that lived in `CdpClient.scala` and were declared as `CdpEnvelope`, `CdpNoParams`, `CdpWireMessage`, `CdpReply`, `CdpEventParams`, `FallbackIdEnvelope`, and the `CdpError` ADT, are all already deleted as part of Phase 02's `CdpClient.scala` deletion. Phase 04 cleans up the `CdpWire.scala` helpers that referenced them:

- `CdpEvalEnvelope.decodeEvalEnvelope` (lines 68-101): DELETE. The helper peels a `CdpReply[EvalResult]` from a raw wire string. `BrowserEval.evalJs` (rewritten in Phase 02) now calls `backend.send[EvalParams, EvalResult]("Runtime.evaluate", params)` and projects via `CdpEvalDecoder.extractEvalValue(env: EvalResult)`. The envelope helper is orphaned.
- `CdpEvalDecoder.parseAndExtractEvalValue` (lines 132-144): DELETE. The function takes a raw wire string and peels the envelope; after Phase 02 callers have the typed `EvalResult` from `backend.send`, so they call `CdpEvalDecoder.extractEvalValue(env)` directly. `isUnreturnableValueError(rawJson)` (lines 114-120): DELETE. The "Object couldn't be returned by value" check is now performed on the typed `JsonRpcError` at the `CdpBackend.send` recovery branch (handled by adding a tiny helper `CdpEvalDecoder.isUnreturnableValueError(err: JsonRpcError): Boolean` that checks `err.message.contains("Object couldn't be returned by value")` and returning `""` from the caller's `Abort.recover[BrowserProtocolErrorException]`).
- `CdpEvalDecoder.extractEvalValue(env: EvalResult)`: KEEP. The typed-projection function consumed by `BrowserEval` post-Phase-02.
- `CdpEvalDecoder.decodeStringListReply(label: String, json: String)`: KEEP. The `Browser.textAll` / `attributeAll` callers still have `String` from `evalJs`; this stays.
- `CdpBase64Decode` (lines 15-33): KEEP. CDP-specific Base64 decoding for `Page.captureScreenshot` / `Page.printToPDF`.
- `ExceptionDetailsFormat` (lines 44-62): KEEP. CDP-specific exception-format rendering.

Phase 04 also verifies that NO references to the deleted symbols survive anywhere in `kyo-browser/shared/src/`. The grep gate per design §11 Phase 03 acceptance (line 1027) and Phase 05 dead-code grep (line 1053) is the contract.

### Files to produce

(none.)

### Files to modify

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpWire.scala`: shrink from 195 LoC to ~95 LoC. Delete `CdpEvalEnvelope` object entirely (lines 64-101); delete `CdpEvalDecoder.parseAndExtractEvalValue` (lines 132-144) and `CdpEvalDecoder.isUnreturnableValueError(rawJson)` (lines 114-120). Add a small `isUnreturnableValueError(err: JsonRpcError): Boolean` helper for the new typed-error recovery path. Keep `CdpBase64Decode` and `ExceptionDetailsFormat` byte-identical; keep `CdpEvalDecoder.extractEvalValue` and `CdpEvalDecoder.decodeStringListReply` byte-identical.

```scala
// kyo-browser/shared/src/main/scala/kyo/internal/CdpWire.scala ; BEFORE
// (195 LoC; CdpBase64Decode + ExceptionDetailsFormat + CdpEvalEnvelope + CdpEvalDecoder
//  with parseAndExtractEvalValue + isUnreturnableValueError(rawJson) + extractEvalValue +
//  decodeStringListReply.)

// kyo-browser/shared/src/main/scala/kyo/internal/CdpWire.scala ; AFTER (~95 LoC)
package kyo.internal

import kyo.*

/** Base64 decoders for CDP wire payloads. (Unchanged from pre-port HEAD.) */
private[kyo] object CdpBase64Decode:
    def decodeWireBase64(method: String, data: String)(using Frame): Span[Byte] < Abort[BrowserDecodingException] =
        Base64.decode(data) match
            case Result.Success(bytes) => bytes
            case Result.Failure(err)   => Abort.fail(BrowserDecodingException(method, err.getMessage))
            case Result.Panic(t)       => Abort.panic(t)

    def decodeScreenshotImage(method: String, data: String)(using Frame): Image < Abort[BrowserDecodingException] =
        Image.fromBase64(data) match
            case Result.Success(img) => img
            case Result.Failure(err) => Abort.fail(BrowserDecodingException(method, err.getMessage))
            case Result.Panic(t)     => Abort.panic(t)
end CdpBase64Decode

/** Renders a CDP ExceptionDetails into a human-readable diagnostic. (Unchanged.) */
private[kyo] object ExceptionDetailsFormat:
    def format(ex: ExceptionDetails): String =
        val text    = ex.text.getOrElse("")
        val desc    = ex.exception.flatMap(_.descriptionOpt).getOrElse("")
        val head    = Seq(text, desc).filter(_.nonEmpty).mkString(": ")
        val headMsg = if head.isEmpty then "Unknown script error" else head
        val frames  = ex.stackTrace.fold("")(formatStackFrames)
        if frames.isEmpty then headMsg else s"$headMsg\n$frames"

    private def formatStackFrames(stackTrace: CdpStackTrace): String =
        stackTrace.callFrames.map { frame =>
            val fn  = if frame.functionName.nonEmpty then frame.functionName else "<anonymous>"
            val url = if frame.url.nonEmpty then frame.url else "<eval>"
            s"  at $fn ($url:${frame.lineNumber}:${frame.columnNumber})"
        }.mkString("\n")
end ExceptionDetailsFormat

/** Eval-result post-processing helpers used by [[BrowserEval]] callers. */
private[kyo] object CdpEvalDecoder:

    /** Detects the typed CDP "Object couldn't be returned by value" error
      * surfaced from Runtime.evaluate for non-serialisable result types
      * (e.g. JS `Symbol`). Caller treats this as the documented CDP
      * limitation and degrades to "" rather than aborting.
      */
    private[kyo] def isUnreturnableValueError(err: JsonRpcError): Boolean =
        err.message.contains("Object couldn't be returned by value")

    /** Extracts the evaluated value from a typed Runtime.evaluate envelope.
      * (Unchanged body modulo: this is now the SOLE entry point; the
      *  rawJson-based parseAndExtractEvalValue is deleted.)
      */
    private[kyo] def extractEvalValue(env: EvalResult)(using Frame): String < Abort[BrowserReadException] =
        extractRemoteObjectValue(env.result)

    private def extractRemoteObjectValue(ro: RemoteObject): String =
        ro match
            case s: RemoteObject.`string` => s.value
            case n: RemoteObject.`number` =>
                val d      = n.value
                val asLong = d.toLong
                if !d.isInfinite && !d.isNaN && d == asLong.toDouble then asLong.toString else d.toString
            case b: RemoteObject.`boolean` => b.value.toString
            case o: RemoteObject.`object`  =>
                o.subtype match
                    case Present("null") => "null"
                    case _               => o.description.getOrElse("")
            case f: RemoteObject.`function`  => f.description.getOrElse("")
            case s: RemoteObject.`symbol`    => s.description.getOrElse("")
            case b: RemoteObject.`bigint`    => b.value
            case _: RemoteObject.`undefined` => ""

    /** Decodes the JSON array reply emitted by Browser.textAll / attributeAll. (Unchanged.) */
    private[kyo] def decodeStringListReply(label: String, json: String)(using Frame): Chunk[String] < (Sync & Abort[BrowserReadException]) =
        if json.isEmpty then Chunk.empty
        else
            Json.decode[Seq[String]](json) match
                case Result.Success(list) => Chunk.from(list)
                case other =>
                    Log.warn(s"$label: unexpected wire shape decoding Seq[String]: $other; raw=$json")
                        .andThen(Abort.fail(BrowserProtocolErrorException.decodeFailure(label, s"$other; raw=$json")))
end CdpEvalDecoder
```

- `kyo-browser/shared/src/main/scala/kyo/internal/BrowserEval.scala`: update the eval-error degrade-to-"" path to use the new typed `isUnreturnableValueError(err: JsonRpcError)` helper inside the `Abort.recover[BrowserProtocolErrorException]` branch (the path Phase 02 sketched).

```scala
// BrowserEval.scala ; BEFORE (post-Phase-02)
Abort.recover[BrowserReadException] {
    case _: BrowserDecodingException => ""
    case other => Abort.fail(other)
} { ... }

// BrowserEval.scala ; AFTER (Phase-04)
Abort.recover[BrowserProtocolErrorException] { ex =>
    // The "Object couldn't be returned by value" CDP error is a documented
    // limitation for non-serialisable result types (e.g. Symbol). Degrade
    // to "" matching pre-port semantics.
    if ex.getMessage.contains("Object couldn't be returned by value") then ""
    else Abort.fail(ex)
} {
    tab.session.send[EvalParams, EvalResult](CdpBackend.RuntimeEvaluateMethod, params)
        .map { (env: EvalResult) =>
            env.exceptionDetails match
                case Present(ex) =>
                    Abort.fail(BrowserProtocolErrorException.internalEvalFailed(ExceptionDetailsFormat.format(ex)))
                case Absent => CdpEvalDecoder.extractEvalValue(env)
        }
}
```

### Files to delete

(none at file granularity , `CdpWire.scala` is modified in-place by deleting ~100 LoC.)

### Public API additions

(none.)

### Public API modifications

(none.)

### Tests

Total: 3 NEW (the Phase-04 INV smoke tests in `JsonRpcPortInvariantsSpec.scala`); existing wire tests continue to pass.

1. `JsonRpcPortInvariantsSpec.scala`: INV-010 , Phase 04 commit deletes legacy envelopes and verifies the grep gate
   - Given: HEAD post-Phase-04
   - When: `grep -lE 'CdpEnvelope|CdpWireMessage|CdpReply|CdpEventParams|FallbackIdEnvelope|CdpSender|CdpEvalEnvelope|parseAndExtractEvalValue' kyo-browser/shared/src/main/scala/kyo/`
   - Then: zero matches
   - Pins: INV-010 (no-backcompat / no shim residue).

2. `JsonRpcPortInvariantsSpec.scala`: INV-007 , Phase 04 does not touch the stability-layer file list
   - Given: SHA256 of each file in the Phase-02-revised stability-layer list (BrowserLauncher, SharedChrome, JsStringUtil, Selector, IFrame, Image, Key, KeyModifiers, CookieWire, ProbesJs, ChromeDownloader, cdp/Accessibility, BrowserLauncherPlatform)
   - When: compare HEAD~1 vs HEAD for the Phase-04 commit
   - Then: every sha256 matches (Phase 04 only touches `CdpWire.scala` and `BrowserEval.scala`, neither of which is in the stability-layer list)
   - Pins: INV-007 (stability-layer preservation).

3. `JsonRpcPortInvariantsSpec.scala`: INV-020 , green-build on JVM, JS, Native after Phase 04
   - Given: `sbt kyo-browserJVM/Test`, then `kyo-browserJS/Test`, then `kyo-browserNative/Test` (SEQUENTIALLY per INV-021)
   - When: each platform full suite runs
   - Then: 1276 shared + 4 platform-specific cases green; sbt exit 0
   - Pins: INV-020.

The existing wire-layer tests (`CdpEvalDecoderTest`, `BrowserWireDecodeFailureTest`, `CdpBackendTest`, `CdpBackendLifecycleTest`, `CdpParamsRoundTripTest`, `CdpTypesTest`, `CdpTypesSchemaFailureTest`) continue to pass because the deleted symbols (`CdpEvalEnvelope.decodeEvalEnvelope`, `CdpEvalDecoder.parseAndExtractEvalValue`, `CdpEvalDecoder.isUnreturnableValueError(rawJson)`) were already not referenced by the rewritten Phase-03 test bodies.

### Consumed invariants

- INV-001, INV-002, INV-003 (public surface byte-identical; cross-checked at Phase 04 boundary).
- INV-007 (stability-layer untouched).
- INV-017 (typed Abort recovery still routes the eval-error class correctly).

### Produced invariants

- INV-010: no remaining backcompat / shim / parallel-API symbols.
- INV-014: no em-dashes / LLM-tells.
- INV-020: green-build on JVM, JS, Native.
- INV-022, INV-023, INV-024.

### Convention sweep (per Decision #25)

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt kyo-browserJVM/Test` (then JS, then Native, sequentially). Full-suite gate.

---

## Phase 05: Cross-platform sweep + final cleanup

Depends on: Phase 04 because (a) all wire-layer envelope/error case classes are deleted, (b) the legacy `CdpEvalEnvelope` helper is gone, (c) the test count is stable at 1276 shared + 4 platform-specific.

Phase 05 is the final cross-platform sweep. It runs the full test suite on JVM, JS, and Native SEQUENTIALLY (INV-021) and confirms test counts match the Phase-03 documented numbers exactly. A dead-code grep confirms no `CdpEvent.Generic` / `CdpSender` / `decodeOrFail` / `CdpEvalEnvelope` / `CdpReply` / `CdpEnvelope` references remain (the surviving `CdpEvent.Generic` in `CdpBackend.scala` is the legitimate dispatcher-handler payload type and is the SOLE expected match). An unused-import sweep with `-Wunused:imports` cleans up any leftover legacy imports surfaced by the compile.

Per design §11 Phase 05 scope, this phase also updates `CLAUDE.md` and the memory file references that mention `CdpClient` to point at the new `CdpBackend`.

The final `flow-strip-dev` pass (converting `// flow-allow: <rationale>` lines to `// <rationale>` and removing `// DEV:` artifacts) is owned by Stage 3 of the FLOW supervisor workflow, not by this phase's impl agent.

### Files to produce

(none.)

### Files to modify

- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala`: any `-Wunused:imports` warnings surfaced get cleaned up. No semantic changes. The companion-object docstrings get a small adjustment to note that `CdpBackendOld` was deleted in Phase 02 (cosmetic).

```scala
// CdpBackend.scala ; BEFORE
// (Phase-04 state: companion object + runtime class CdpBackend.)

// CdpBackend.scala ; AFTER (Phase-05 cleanup)
// - Unused imports removed (specifically any leftover `import kyo.Exchange` /
//   `import kyo.Channel.initUnscoped` that were retained as a compile-time
//   accomodation in Phase 01 / 02 and are now dead).
// - Companion-object docstring updated to drop the "Phase-01 byte-equivalent
//   coexistence" sentence.
// plan: phase-05 impl agent collects the actual unused-import list from
// `sbt 'kyo-browserJVM/Compile/compile'` output and removes each one.
```

- `CLAUDE.md` (root): update references to `CdpClient` to `CdpBackend`. This is a documentation file, not a Scala source; it lives outside the `kyo-browser/` tree but is required by design §11 Phase 05 scope (line 1068-1069).

```scala
// CLAUDE.md ; BEFORE
// ... CdpClient.scala ... (any references)
// CLAUDE.md ; AFTER
// ... CdpBackend.scala ... (or removed if obsolete)
```

- `~/.claude/projects/-Users-fwbrasil-workspace-kyo/memory/MEMORY.md`: update references to `CdpClient` to `CdpBackend`. Same rationale as `CLAUDE.md`.

```scala
// MEMORY.md ; BEFORE
// ... CdpClient ... (any references in user-memory notes)
// MEMORY.md ; AFTER
// ... CdpBackend ... (or removed if obsolete)
```

### Files to delete

(none.)

### Public API additions

(none.)

### Public API modifications

(none.)

### Tests

Total: 5 NEW (Phase-05 INV smoke tests in `JsonRpcPortInvariantsSpec.scala`).

1. `JsonRpcPortInvariantsSpec.scala`: INV-021 , sequential cross-platform test runs
   - Given: Phase-05 commit message; the supervisor's bash audit log
   - When: parse the log for invocation order
   - Then: `sbt kyo-browserJVM/Test` appears before `sbt kyo-browserJS/Test`; `sbt kyo-browserJS/Test` appears before `sbt kyo-browserNative/Test`; no two invocations overlap in wall-clock time
   - Pins: INV-021 (sequential cross-platform).

2. `JsonRpcPortInvariantsSpec.scala`: INV-004 , final cross-platform test count parity
   - Given: each platform's full Test run
   - When: count cases per platform
   - Then: JVM = 1276 shared + 4 JVM-only; JS = 1276 shared + 4 JS-only; Native = 1276 shared + 4 Native-only; all green
   - Pins: INV-004.

3. `JsonRpcPortInvariantsSpec.scala`: INV-010 , final dead-code grep
   - Given: `grep -rE 'CdpEvent\.Generic|CdpSender|decodeOrFail|CdpEvalEnvelope|CdpReply|CdpEnvelope|CdpWireMessage|CdpEventParams|FallbackIdEnvelope' kyo-browser/`
   - When: count matches
   - Then: SOLE expected match is `CdpEvent.Generic` references inside `CdpBackend.scala` (the dispatcher-handler payload type); zero matches for any other symbol; the count of `CdpEvent.Generic` matches the number of dispatch sites (~6: one per notification handler + dispatcher table type signatures)
   - Pins: INV-010.

4. `JsonRpcPortInvariantsSpec.scala`: INV-008 , campaign-wide Rule 8c audit
   - Given: `git log pre-port-tag..HEAD --format='%H' -- kyo-browser/shared/src/main/scala/kyo/internal/Cdp*.scala`
   - When: for each commit, verify the same-commit matching test addition/modification
   - Then: every Cdp*.scala source change is paired with a Cdp*Test.scala or JsonRpcPortInvariantsSpec.scala change in the same commit
   - Pins: INV-008 (campaign-wide audit).

5. `JsonRpcPortInvariantsSpec.scala`: INV-005 / INV-020 , final platforms green
   - Given: each per-phase commit message
   - When: grep for `JVM: <N> green` / `JS: <N> green` / `Native: <N> green`
   - Then: every phase commit has the triple of platform-green lines
   - Pins: INV-005, INV-020.

### Consumed invariants

- INV-001..INV-024 EXCEPT INV-021 which Phase 05 produces. The full consumption list per INV-mapping in `04-invariants.md`:
  INV-001, INV-002, INV-003, INV-004, INV-005, INV-006, INV-007, INV-008, INV-009, INV-010, INV-011, INV-012, INV-013, INV-015, INV-016, INV-017, INV-018, INV-019, INV-020, INV-022, INV-023, INV-024.

### Produced invariants

- INV-021: sequential cross-platform test runs (formally codified in this phase).

### Convention sweep (per Decision #25)

- [em-dash, AllowUnsafe, Option-vs-Maybe, semicolons, asInstanceOf, default-params, Frame.internal, java.util.concurrent, llm-tells]

### Cross-platform set

- platforms: [jvm, js, native]

### Verification command

- `sbt kyo-browserJVM/Test` then `kyo-browserJS/Test` then `kyo-browserNative/Test`, EACH as a separate `sbt` invocation (per INV-021). Plus `sbt 'kyo-browserJVM/Compile/compile' 'kyo-browserJVM/Test/compile'` with `-Wunused:imports` and fix every surfaced warning. Plus the campaign-wide `grep -rE 'CdpClient|CdpEnvelope|CdpWireMessage|CdpReply|CdpEventParams|FallbackIdEnvelope|CdpSender|decodeOrFail' kyo-browser/` audit (expect zero matches except the legitimate `CdpEvent.Generic` references in `CdpBackend.scala`).

---

## Cross-cutting traceability table

| Invariant | Produced by | Consumed by | Verification gate |
|---|---|---|---|
| INV-001 | Phase 02 | Phase 03, Phase 05 | `grep`-diff vs pre-port-tag on Browser.scala |
| INV-002 | Phase 02 | Phase 03, Phase 05 | `grep`-diff vs pre-port-tag on BrowserException.scala |
| INV-003 | Phase 02 | Phase 03, Phase 05 | `grep`-diff public symbols only on BrowserTab/BrowserSnapshot |
| INV-004 | Phase 03 | Phase 05 | sbt test count = 1276 shared + 4 platform per platform |
| INV-005 | Phase 01 | Phase 02..05 | commit message contains `JVM: green` / `JS: green` / `Native: green` |
| INV-006 | Phase 03 | Phase 05 | `git diff --stat` over `jvm/src/test`, `js/src/test`, `native/src/test` empty |
| INV-007 | Phase 02 | Phase 03, Phase 05 | sha256 per file in revised stability-layer list matches pre-port |
| INV-008 | Phase 01, 02, 03 | Phase 05 | `git show --name-only` per phase commit pairs source + test |
| INV-009 | Phase 01, 02 | Phase 05 | head -1 + grep for top-level type matches basename |
| INV-010 | Phase 02 | Phase 03, Phase 05 | `grep -lE 'CdpClient\|...'` returns zero matches |
| INV-011 | Phase 01, 02 | Phase 03, Phase 05 | `grep -E 'Json\.parseString\|String\.format.*"\{\|\\"jsonrpc\\"\|Pattern\.compile.*\\{\|derives Json'` returns zero matches |
| INV-012 | Phase 01, 02 | Phase 05 | `git diff` adds zero `var` for shared state |
| INV-013 | Phase 01, 02 | Phase 05 | `git diff` adds zero unannotated `AllowUnsafe`/`Sync.Unsafe.` |
| INV-014 | Phase 01..05 | flow-validate gate | `git diff` adds zero em-dash / en-dash |
| INV-015 | Phase 01, 02 | Phase 03, Phase 05 | `grep` for `ExtrasEncoder.const` / `ctx.extras` non-empty; `decodeCdpMessage` zero matches |
| INV-016 | Phase 01 | Phase 02, Phase 05 | `grep` for `"Browser.getVersion"` in CdpBackend.scala non-empty; JsonRpcHttpTransport.scala diff empty |
| INV-017 | Phase 01, 02 | Phase 03, Phase 05 | `grep` for `Abort.recover[(Closed|JsonRpcError|Timeout)]` non-empty in CdpBackend.scala |
| INV-018 | Phase 01 | Phase 03, Phase 05 | `grep` for `AtomicInt.init(Int.MinValue)` in CdpBackend.scala non-empty |
| INV-019 | Phase 01, 02 | Phase 05 | `git diff` adds zero `Fiber.block` |
| INV-020 | Phase 01..05 | next phase + Phase 05 final | sbt compile + targeted test green per platform |
| INV-021 | Phase 05 | every prior phase's local validation | commit log shows separate sequential sbt invocations |
| INV-022 | Phase 01..05 | (documentation) | `git log` shows no Co-Authored-By trailer |
| INV-023 | Phase 01..05 | (operational) | supervisor audit log shows zero `git push` |
| INV-024 | Phase 01..05 | flow-strip-dev | every audit-flagged exception carries `// flow-allow:` |

## Plan status

All 5 phases enumerated. All 24 invariants mapped. All 8 design open questions (Q-001..Q-008) resolved in `./03a-open-resolutions.md`. Plan is ready for `flow-validate`.
