package kyo.internal

import CdpTypes.*
import kyo.*

/** Waits for a document to quiesce after an action. Each wait owns its observer and polling timers through a CDP object handle.
  * The handle belongs to the document that installed it: navigation cannot redirect a wait or its cleanup into another document.
  * Acquisitions use CdpBackend.acquire so an abandoned caller still releases a late reply.
  */
private[kyo] object MutationSettlement:

    /** Observes the whole document because a handler can update siblings of its target. */
    def afterAction[A, S](action: A < (Browser & Async & Abort[BrowserReadException] & S))(
        scopeSelector: Maybe[Selector]
    )(using Frame): A < (Browser & Async & Abort[BrowserReadException] & S) =
        val _ = scopeSelector
        Browser.configLocal.use { cfg =>
            if cfg.mutationQuiescenceWindow == Duration.Zero then action
            else
                Scope.run {
                    installObserver().map { observer =>
                        action.map { result =>
                            awaitQuiescence(
                                observer,
                                cfg.mutationQuiescenceWindow,
                                cfg.mutationSettlementTimeout,
                                cfg.mutationFirstMutationGrace,
                                cfg.mutationPollInterval
                            ).andThen(result)
                        }
                    }
                }
        }
    end afterAction

    private[kyo] def waitForStable(timeout: Duration)(using Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            Scope.run {
                installObserver().map { observer =>
                    awaitQuiescence(
                        observer,
                        cfg.mutationQuiescenceWindow,
                        timeout,
                        cfg.mutationFirstMutationGrace,
                        cfg.mutationPollInterval
                    )
                }
            }
        }

    /** Capture deliberately treats a quiescence timeout as best effort; other failures still propagate. */
    private[kyo] def settleForCapture(using Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            Abort.recover[BrowserAssertionTimedOutException](_ => ()) {
                waitForStable(cfg.mutationSettlementTimeout)
            }
        }

    private def installObserver()(using Frame): String < (Browser & Scope & Abort[BrowserReadException]) =
        Browser.activeIFrameLocal.use(active => active.map(_.executionContextId)).map { context =>
            Browser.use { tab =>
                kyo.kernel.ContextEffect.suspendWith(Tag[Scope]) { finalizer =>
                    CdpBackend.recoverContextDestroyed {
                        tab.session.acquire[EvaluateObjectParams, EvaluateObjectResult](
                            finalizer,
                            CdpBackend.RuntimeEvaluateMethod,
                            EvaluateObjectParams(installObserverJs, contextId = context.map(_.value))
                        )(reply => releaseObserver(tab.session, reply))
                    }
                }.map { reply =>
                    reply.exceptionDetails match
                        case Present(error) =>
                            Abort.fail(BrowserProtocolErrorException.internalEvalFailed(ExceptionDetailsFormat.format(error)))
                        case Absent =>
                            reply.result.objectId match
                                case Present(id) => id
                                case Absent      =>
                                    Abort.fail(BrowserProtocolErrorException.unexpectedReply(
                                        CdpBackend.RuntimeEvaluateMethod,
                                        "missing settlement observer handle"
                                    ))
                }
            }
        }

    private val installObserverJs: String =
        """(() => {
            const state = { count: 0, last: performance.now(), closed: false };
            const set = window.setTimeout.bind(window);
            const clear = window.clearTimeout.bind(window);
            const sleeps = new Map();
            state.sleep = ms => new Promise(resolve => {
                if (state.closed) { resolve(); return; }
                // HTML timers use signed 32-bit milliseconds; the deadline loop rearms longer waits.
                const id = set(() => { sleeps.delete(id); resolve(); }, Math.min(ms, 2147483647));
                sleeps.set(id, resolve);
            });
            const observer = new MutationObserver(records => {
                const internal = n => {
                    const el = n && (n.nodeType === 1 ? n : n.parentElement);
                    return !!(el && el.closest && el.closest('[data-kyo-internal]'));
                };
                let count = 0;
                for (const record of records) {
                    if (internal(record.target)) continue;
                    const touched = record.addedNodes.length + record.removedNodes.length;
                    if (record.type === 'childList' && touched > 0 &&
                        Array.prototype.every.call(record.addedNodes, internal) &&
                        Array.prototype.every.call(record.removedNodes, internal)) continue;
                    count++;
                }
                if (count > 0) { state.count += count; state.last = performance.now(); }
            });
            state.dispose = () => {
                if (state.closed) return;
                state.closed = true;
                observer.disconnect();
                for (const [id, resolve] of sleeps) { clear(id); resolve(); }
                sleeps.clear();
            };
            try {
                observer.observe(document, { childList: true, subtree: true, attributes: true, characterData: true });
                return state;
            } catch (error) { state.dispose(); throw error; }
        })()"""

    /** Both operations address the acquired object, never a global on the current page. A destroyed context has already released it. */
    private def releaseObserver(backend: CdpBackend, reply: EvaluateObjectResult)(using Frame): Unit < Async =
        reply.result.objectId match
            case Absent      => ()
            case Present(id) =>
                Abort.run[BrowserReadException](
                    CdpBackend.runtimeCallFunctionOn(
                        backend,
                        CallFunctionOnParams(id, "function() { this.dispose(); }")
                    )
                ).andThen {
                    Abort.run[BrowserReadException](
                        backend.sendUnit("Runtime.releaseObject", ReleaseObjectParams(id))
                    ).unit
                }

    private def documentReplaced(error: BrowserProtocolErrorException): Boolean =
        error.method == "Runtime.callFunctionOn" && error.code.contains(-32000) &&
            (error.error.contains(CdpErrorStrings.TargetNavigatedErrorMessage) ||
                error.error.contains(CdpErrorStrings.ContextDestroyedErrorMessage) ||
                error.error.contains(CdpErrorStrings.ObjectNotFoundErrorMessage))

    private def awaitNewPage(using Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        Browser.activeIFrameLocal.use { active =>
            if active.isDefined then
                Abort.fail(BrowserIFrameInvalidException(BrowserIFrameInvalidException.Reason.ContextDestroyed))
            else NavigationWatcher.awaitCommitted(Browser.Settle.NetworkIdle, throwOnFailure = true)
        }

    private def awaitQuiescence(
        observer: String,
        quiescenceWindow: Duration,
        overallDeadline: Duration,
        firstGrace: Duration,
        pollInterval: Duration
    )(using Frame): Unit < (Browser & Async & Abort[BrowserReadException]) =
        val js = s"""async function() {
            const state = this;
            const windowMs = ${quiescenceWindow.toMillis};
            const deadlineAt = performance.now() + ${overallDeadline.toMillis};
            const firstGraceAt = performance.now() + ${firstGrace.toMillis};
            const sleep = ms => state.sleep(ms);
            const deadline = async () => {
                do {
                    await sleep(Math.max(0, deadlineAt - performance.now()));
                } while (!state.closed && performance.now() < deadlineAt);
                return 'timeout';
            };
            const queue = await Promise.race([
                Promise.resolve(window._kyoPostQ).then(() => 'drained'),
                deadline()
            ]);
            if (queue === 'timeout') return JSON.stringify({
                tag: 'timeout', count: state.count, delta: Math.round(performance.now() - state.last)
            });
            if (state.closed) return JSON.stringify({ tag: 'done' });
            if ((window.__kyoPostFailures || 0) > 0) {
                const error = window.__kyoPostLastError || '(unknown)';
                window.__kyoPostFailures = 0;
                window.__kyoPostLastError = '';
                return JSON.stringify({ tag: 'postfail', error });
            }
            while (true) {
                if (state.closed) return JSON.stringify({ tag: 'done' });
                const count = state.count;
                const now = performance.now();
                const delta = Math.round(now - state.last);
                if (count > 0 && delta >= windowMs) return JSON.stringify({ tag: 'done', count, delta });
                if (count === 0 && now >= firstGraceAt) return JSON.stringify({ tag: 'done', count, delta });
                if (now >= deadlineAt) return JSON.stringify({ tag: count > 0 ? 'timeout' : 'done', count, delta });
                await sleep(Math.min(${pollInterval.toMillis}, Math.max(0, deadlineAt - now)));
            }
        }"""
        Browser.use { tab =>
            Abort.recover[BrowserProtocolErrorException] { error =>
                if documentReplaced(error) then awaitNewPage else Abort.fail(error)
            } {
                CdpBackend.runtimeCallFunctionOn(
                    tab.session,
                    CallFunctionOnParams(observer, js, awaitPromise = true)
                ).map { reply =>
                    CdpEvalDecoder.extractValueOrFail(reply).map { value =>
                        parseSettlementValue(value) match
                            case SettlementResult.Done                  => ()
                            case SettlementResult.Timeout(count, delta) =>
                                Abort.fail(BrowserAssertionTimedOutException.notQuiesced(
                                    quiescenceWindow,
                                    delta,
                                    count,
                                    overallDeadline
                                ))
                            case SettlementResult.PostFailed(error) =>
                                Abort.fail(BrowserProtocolErrorException.unexpectedReply(
                                    "MutationSettlement.awaitQuiescence",
                                    s"event POST failed: $error"
                                ))
                            case SettlementResult.Malformed =>
                                Abort.fail(BrowserProtocolErrorException.unexpectedReply("MutationSettlement.awaitQuiescence", value))
                    }
                }
            }
        }
    end awaitQuiescence

    private[internal] enum SettlementResult derives CanEqual:
        case Done
        case Timeout(count: Long, delta: Long)
        case PostFailed(error: String)
        case Malformed
    end SettlementResult

    private case class SettlementWire(
        tag: String,
        count: Maybe[Long] = Absent,
        delta: Maybe[Long] = Absent,
        error: Maybe[String] = Absent
    ) derives Schema

    private[internal] def parseSettlementValue(value: String)(using Frame): SettlementResult =
        Json.decode[SettlementWire](value) match
            case Result.Success(w) =>
                w.tag match
                    case "done"    => SettlementResult.Done
                    case "timeout" =>
                        (w.count, w.delta) match
                            case (Present(c), Present(d)) => SettlementResult.Timeout(c, d)
                            case _                        => SettlementResult.Malformed
                    case "postfail" =>
                        w.error match
                            case Present(error) => SettlementResult.PostFailed(error)
                            case Absent         => SettlementResult.Malformed
                    case _ => SettlementResult.Malformed
            case _ => SettlementResult.Malformed

end MutationSettlement
