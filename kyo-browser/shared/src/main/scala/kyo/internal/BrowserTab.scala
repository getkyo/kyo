package kyo.internal

import CdpTypes.*
import kyo.*

/** A live browser tab.
  *
  * `browserContextId` is the id of the isolated Chrome browser context this tab belongs to (created by `Target.createBrowserContext` in
  * `Browser.run`'s `attachTab`). `withNewTab` / `withFork` / `isolate.fresh` must thread this through when they call `Target.createTarget`
  * otherwise the new tab would land in Chrome's default context and **not** share cookies / storage with the parent tab, breaking the
  * intended "same context, fresh tab" semantics.
  *
  * `consoleCaptureRegistered` and `responseTrackerRegistered` guard the per-tab `Page.addScriptToEvaluateOnNewDocument` registrations that
  * install the console-log capture hook and the fetch/XHR response observer (used by `Browser.waitForRequestUrl`). The CDP call returns an
  * identifier and is **not** idempotent; re-invoking it would queue another script per navigation. Callers use `compareAndSet(false, true)`
  * to ensure the registration runs at most once per tab; the JS-side `__kyoConsoleInstalled` / `__kyoResponseTrackingInstalled` gates keep
  * the inline re-runs no-ops on already-instrumented documents.
  */
final private[kyo] class BrowserTab(
    val targetId: TargetId,
    val sessionId: SessionId,
    val backend: CdpBackend,
    val browserContextId: Maybe[String],
    val frameContexts: AtomicRef[Dict[FrameId, ExecutionContextId]],
    val rootFrameId: AtomicRef[Maybe[FrameId]],
    val consoleCaptureRegistered: AtomicBoolean,
    val responseTrackerRegistered: AtomicBoolean,
    val viewportOverride: AtomicRef[Maybe[BrowserTab.ViewportOverride]],
    val emulationOverride: AtomicRef[Maybe[BrowserTab.EmulatedMediaState]],
    val downloadPolicy: AtomicRef[Maybe[(Browser.DownloadBehavior, Maybe[String])]]
):
    /** Session-scoped CDP backend. Every interaction path issues CDP commands against this tab's specific session; capturing the
      * `backend.withSession(sessionId)` pair once eliminates the `tab.backend.withSession(tab.sessionId)` boilerplate that otherwise repeats
      * at every CDP call site.
      */
    val session: CdpBackend = backend.withSession(sessionId)
end BrowserTab

/** Companion for [[BrowserTab]], holding nested types shared across the internal module. */
private[kyo] object BrowserTab:
    /** Cached per-tab viewport override carrying the device-scale-factor (DPR).
      *
      * Stored in the per-tab `viewportOverride: AtomicRef[Maybe[ViewportOverride]]`. A value
      * of `Absent` means no active override (natural viewport). `dpr` defaults to `1.0` for
      * the no-DPR-override case. Consumed by `setViewport`/`withViewport`.
      */
    final case class ViewportOverride(width: Int, height: Int, dpr: Double) derives CanEqual

    /** Cached per-tab emulated-media state backing the scoped restore in `Browser.withEmulation`.
      *
      * Stored in the per-tab `emulationOverride: AtomicRef[Maybe[EmulatedMediaState]]`. A value of `Absent` means no active
      * override (the page sees its real media features). `colorScheme` and `media` hold the CDP wire strings sent to
      * `Emulation.setEmulatedMedia` (`"light"`, `"dark"`, `"screen"`, `"print"`, or `""` for a cleared / unset feature);
      * `reducedMotion` mirrors the `reducedMotion: Boolean` argument passed to `withEmulation`. On scope exit `withEmulation`
      * re-applies the cached prior state, or clears the override when the prior was `Absent`.
      */
    final case class EmulatedMediaState(
        colorScheme: Maybe[String],
        media: Maybe[String],
        reducedMotion: Boolean
    ) derives CanEqual
end BrowserTab

/** All tab-attachment plumbing lives here; `Browser.scala` retains only the `Env[BrowserTab]` binding.
  *
  * `attachAndSetupTab` is the single entry point for creating a fresh isolated browser context, attaching a CDP target, and running all
  * per-tab event-tracker installation steps. `enableDomains` fires the four CDP domains (Page, Runtime, DOM, Network) that every action
  * path depends on. The remaining members (`mkBrowserTab`, `installFrameContextTracker`, `seedRootFrameId`, `installConsoleCapture`) are
  * lower-level primitives used by `attachAndSetupTab` and by the open-coded sibling paths (`withNewTab`, `withPopup`, `createChildTab`).
  */
private[kyo] object BrowserTabSetup:

    /** Allocates a [[BrowserTab]] together with its per-tab atomic state.
      *
      * Centralised so every call site (attachTab, withNewTab, withPopup, createChildTab) ends up with the same subscriber-then-getFrameTree
      * ordering required by the frame-context invariant.
      */
    private[kyo] def mkBrowserTab(
        targetId: TargetId,
        sessionId: SessionId,
        backend: CdpBackend,
        browserContextId: Maybe[String]
    )(using Frame): BrowserTab < Sync =
        for
            frameCtx         <- AtomicRef.init[Dict[FrameId, ExecutionContextId]](Dict.empty)
            rootRef          <- AtomicRef.init[Maybe[FrameId]](Absent)
            consoleRegister  <- AtomicBoolean.init(false)
            responseRegister <- AtomicBoolean.init(false)
            viewportRef      <- AtomicRef.init[Maybe[BrowserTab.ViewportOverride]](Absent)
            emulationRef     <- AtomicRef.init[Maybe[BrowserTab.EmulatedMediaState]](Absent)
            downloadRef      <- AtomicRef.init[Maybe[(Browser.DownloadBehavior, Maybe[String])]](Absent)
        yield new BrowserTab(
            targetId,
            sessionId,
            backend,
            browserContextId,
            frameCtx,
            rootRef,
            consoleRegister,
            responseRegister,
            viewportRef,
            emulationRef,
            downloadRef
        )

    /** Subscribes this tab's session to `Runtime.executionContext{Created,Destroyed}` events.
      *
      * Must be registered BEFORE `Runtime.enable` (in [[enableDomains]]); otherwise CDP fires the create event for the initial about:blank
      * context before the consumer is ready and the frame's default executionContext is lost. The Scope.ensure deregister keeps the handler
      * from outliving the tab.
      */
    private[kyo] def installFrameContextTracker(tab: BrowserTab)(using Frame): Unit < (Sync & Scope) =
        val key = tab.sessionId.value
        // If a future scalac upgrade flags E197 ("anonymous class duplicated at inline site")
        // on the SAM lambda below, apply `@nowarn("msg=anonymous")` matching the pattern in `kyo-core/Atomic.scala`.
        // No actual @nowarn yet; current scalac does not flag this site.
        val handler: CdpEvent.Generic => Unit < Sync = ev =>
            if ev.method == "Runtime.executionContextCreated" || ev.method == "Runtime.executionContextDestroyed" then
                updateFrameContexts(tab, ev)
            else Kyo.unit
        tab.backend.frameEventDispatchers.updateAndGet(_.update(key, handler)).andThen(
            Scope.ensure(tab.backend.frameEventDispatchers.updateAndGet(_.remove(key)).unit)
        )
    end installFrameContextTracker

    /** Updates the per-tab frame-context map from a `Runtime.executionContext{Created,Destroyed}` event.
      *
      * Only `auxData.isDefault == true` contexts are recorded; isolated worlds (extensions, content scripts) share a frameId but have a
      * different contextId and would otherwise overwrite the default entry. Destroy events carry only the contextId, so we filter the map's
      * values rather than its keys.
      */
    private[kyo] def updateFrameContexts(tab: BrowserTab, ev: CdpEvent)(using Frame): Unit < Sync =
        ev match
            case CdpEvent.Generic(_, params, _) =>
                params match
                    case created: ExecutionContextCreatedParams =>
                        val ctx = created.context
                        if ctx.auxData.isDefault && ctx.auxData.frameId.nonEmpty then
                            tab.frameContexts
                                .updateAndGet(_.update(FrameId(ctx.auxData.frameId), ExecutionContextId(ctx.id)))
                                .unit
                        else Kyo.unit
                        end if
                    case destroyed: ExecutionContextDestroyedParams =>
                        val cid = ExecutionContextId(destroyed.executionContextId)
                        tab.frameContexts.updateAndGet { m =>
                            m.filter((_, v) => v != cid)
                        }.unit
                    case _ => Kyo.unit
                end match

    /** Stashes the root frame id from a one-shot `Page.getFrameTree` round-trip.
      *
      * Must run AFTER [[enableDomains]] (Page domain enabled). The frame-context map update is order-independent w.r.t. the create event,
      * so racing with it is safe.
      */
    private[kyo] def seedRootFrameId(tab: BrowserTab)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackend.getFrameTree(tab.session).map { tree =>
            tab.rootFrameId.set(Present(FrameId(tree.frameTree.frame.id)))
        }

    private[kyo] val consoleCaptureJs: String =
        """(() => {
            window.__kyoConsoleInstalled = true;
            window.__kyoConsoleLogs = [];
            window.__kyoConsoleT0 = Date.now();
            const origLog = console.log;
            const origInfo = console.info;
            const origWarn = console.warn;
            const origError = console.error;
            const origDebug = console.debug;
            console.log = function() {
                window.__kyoConsoleLogs.push({ level: 'log', message: Array.from(arguments).join(' '), timestamp: Date.now() });
                origLog.apply(console, arguments);
            };
            console.info = function() {
                window.__kyoConsoleLogs.push({ level: 'info', message: Array.from(arguments).join(' '), timestamp: Date.now() });
                origInfo.apply(console, arguments);
            };
            console.warn = function() {
                window.__kyoConsoleLogs.push({ level: 'warn', message: Array.from(arguments).join(' '), timestamp: Date.now() });
                origWarn.apply(console, arguments);
            };
            console.error = function() {
                window.__kyoConsoleLogs.push({ level: 'error', message: Array.from(arguments).join(' '), timestamp: Date.now() });
                origError.apply(console, arguments);
            };
            console.debug = function() {
                window.__kyoConsoleLogs.push({ level: 'debug', message: Array.from(arguments).join(' '), timestamp: Date.now() });
                origDebug.apply(console, arguments);
            };
            return 'ok';
        })()"""

    /** Installs the console.log/warn/error capture override for the tab. */
    private[kyo] def installConsoleCapture(tab: BrowserTab)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        // Page.addScriptToEvaluateOnNewDocument is NOT idempotent: repeated calls stack a script per
        // navigation, so we gate registration on a per-tab AtomicBoolean. The inline evalJsOn re-run is safe:
        // the JS body gates on `window.__kyoConsoleInstalled`, so a second call is a no-op against live docs.
        tab.consoleCaptureRegistered.compareAndSet(false, true).map { firstTime =>
            val register: Unit < (Async & Abort[BrowserReadException]) =
                if firstTime then
                    tab.session.sendUnit(
                        "Page.addScriptToEvaluateOnNewDocument",
                        AddScriptToEvaluateOnNewDocumentParams(consoleCaptureJs)
                    )
                else ()
            register.andThen(CdpBackend.runtimeEvaluate(
                tab.session,
                EvalParams(consoleCaptureJs)
            ).map(CdpEvalDecoder.extractValueOrFail).unit)
        }
    end installConsoleCapture

    /** Installs the fetch/XHR response observer used by [[Browser.waitForRequestUrl]]. Same `Page.addScriptToEvaluateOnNewDocument` shape
      * as [[installConsoleCapture]]: registers once, gated by a per-tab AtomicBoolean, then runs the JS inline against the already-loaded
      * document so the observer is in place for actions issued on the current page (not just future navigations).
      */
    private[kyo] def installResponseTracker(tab: BrowserTab)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        tab.responseTrackerRegistered.compareAndSet(false, true).map { firstTime =>
            val register: Unit < (Async & Abort[BrowserReadException]) =
                if firstTime then
                    tab.session.sendUnit(
                        "Page.addScriptToEvaluateOnNewDocument",
                        AddScriptToEvaluateOnNewDocumentParams(BrowserNetworkTracker.responseTrackerScript)
                    )
                else ()
            register.andThen(CdpBackend.runtimeEvaluate(
                tab.session,
                EvalParams(BrowserNetworkTracker.responseTrackerScript)
            ).map(CdpEvalDecoder.extractValueOrFail).unit)
        }
    end installResponseTracker

    /** Creates an isolated browser context, attaches a CDP target, and runs all per-tab event-tracker installation steps.
      *
      * Returns the fully-initialised [[BrowserTab]]. The caller is responsible for binding it to the `Env[BrowserTab]` effect (via
      * `Env.run(tab)(v)`). The `Scope` effect covers context disposal on scope exit.
      */
    private[kyo] def attachAndSetupTab(backend: CdpBackend)(using Frame): BrowserTab < (Async & Scope & Abort[BrowserReadException]) =
        for
            // Create an isolated browser context. Disposing the context on scope exit cleans up service workers,
            // downloads, storage, and renderer state that plain Target.closeTarget leaves behind.
            ctx <- CdpBackend.createBrowserContext(backend)
            _ <- Scope.ensure(
                CdpBackend.disposeBrowserContext(backend, DisposeBrowserContextParams(ctx.browserContextId))
            )
            created  <- CdpBackend.createTarget(backend, CreateTargetParams("about:blank", Present(ctx.browserContextId)))
            attached <- CdpBackend.attachToTarget(backend, AttachParams(created.targetId, flatten = true))
            tab <- mkBrowserTab(
                TargetId(created.targetId),
                SessionId(attached.sessionId),
                backend,
                Present(ctx.browserContextId)
            )
            // Install the frame-context tracker BEFORE Runtime.enable so executionContextCreated events
            // for the initial about:blank frame are not lost between enable-time and first poll.
            _ <- installFrameContextTracker(tab)
            _ <- enableDomains(tab.session)
            _ <- seedRootFrameId(tab)
            _ <- installConsoleCapture(tab)
            _ <- installResponseTracker(tab)
        yield tab

    /** Enables the four CDP domains required by every action path. Issued in parallel to amortise the round-trip cost on tab attach,
      * followed by `Emulation.setFocusEmulationEnabled(true)` so programmatic `el.focus()` calls (from `fillViaJs` / `focusElement` /
      * future helpers) dispatch focus/blur DOM events instead of only updating `document.activeElement`. Without the emulation flag,
      * headless / background tabs suppress those events and framework listeners (kyo-ui, React onFocus, etc.) silently miss user-flow
      * events.
      */
    private[kyo] def enableDomains(sender: CdpBackend)(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        Async.zip(
            sender.sendUnit[CdpNoParams]("Page.enable", CdpNoParams()),
            sender.sendUnit[CdpNoParams]("Runtime.enable", CdpNoParams()),
            sender.sendUnit[CdpNoParams]("DOM.enable", CdpNoParams()),
            sender.sendUnit[CdpNoParams]("Network.enable", CdpNoParams())
        ).andThen {
            sender.sendUnit[FocusEmulationParams]("Emulation.setFocusEmulationEnabled", FocusEmulationParams(true))
        }

    /** Creates a child tab from a parent **inside a newly-minted browser context**, registered for cleanup in Scope.
      *
      * This path is used by `Browser.withFork` and both `Browser.isolate.fresh` / `Browser.isolate.clone`; all three share the requirement
      * that mutations in the child (cookies, storage, service workers) must not leak into the parent. Disposing the browser context on
      * scope exit is sufficient cleanup: it tears down the target, storage, service workers, and any in-flight downloads belonging to it,
      * so no separate `Target.closeTarget` hook is required.
      *
      * For a "same context, just a blank page" tab (where cookies *are* shared with the parent) use `Browser.withNewTab` instead.
      */
    private[kyo] def createChildTab(parent: BrowserTab)(using Frame): BrowserTab < (Async & Scope & Abort[BrowserReadException]) =
        for
            ctx <- CdpBackend.createBrowserContext(parent.backend)
            _ <- Scope.ensure(
                CdpBackend.disposeBrowserContext(parent.backend, DisposeBrowserContextParams(ctx.browserContextId))
            )
            created  <- CdpBackend.createTarget(parent.backend, CreateTargetParams("about:blank", Present(ctx.browserContextId)))
            attached <- CdpBackend.attachToTarget(parent.backend, AttachParams(created.targetId, flatten = true))
            tab <- mkBrowserTab(
                TargetId(created.targetId),
                SessionId(attached.sessionId),
                parent.backend,
                Present(ctx.browserContextId)
            )
            _ <- installFrameContextTracker(tab)
            _ <- enableDomains(tab.session)
            _ <- seedRootFrameId(tab)
            _ <- installConsoleCapture(tab)
            _ <- installResponseTracker(tab)
        yield tab

end BrowserTabSetup
