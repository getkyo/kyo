package kyo.internal

import CdpTypes.*
import kyo.*

/** Native contenteditable insertion, with event guards owned by the selected document. */
private[kyo] object EditableInput:

    private[kyo] def fill(selector: Selector, text: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        val element = SelectorJs.resolveElementJs(Selector.toNode(selector))
        val script  = s"""(() => {
            const el = $element;
            const doc = document;
            const view = window;
            let host = el;
            while (host && host.parentElement && host.parentElement.isContentEditable) host = host.parentElement;
            let pending;
            let armed = false;
            let redirected = false;
            const ownsSelection = () => {
                const selection = view.getSelection();
                return el && el.isConnected && host.getRootNode().activeElement === host &&
                    selection && selection.rangeCount && el.contains(selection.anchorNode) && el.contains(selection.focusNode);
            };
            const before = event => {
                if (!armed || !event.isTrusted) return;
                pending = event;
                if (event.target !== host || !ownsSelection()) { redirected = true; event.preventDefault(); }
            };
            const focus = () => {
                // Chromium resolves the insertion target after dispatching beforeinput. Cancel that same event on focus loss.
                if (armed && pending && pending.eventPhase !== Event.NONE) {
                    redirected = true;
                    pending.preventDefault();
                }
            };
            const textInput = event => {
                if (armed && event.isTrusted) pending = event;
                if (armed && event.isTrusted && (redirected || event.target !== host || !ownsSelection())) {
                    redirected = true;
                    event.preventDefault();
                }
            };
            const state = {
                prepare() {
                    if (!el || !el.isConnected || !el.isContentEditable) return 'lost';
                    host.focus();
                    if (host.getRootNode().activeElement !== host) return 'lost';
                    const range = doc.createRange();
                    range.selectNodeContents(el);
                    const selection = view.getSelection();
                    selection.removeAllRanges();
                    selection.addRange(range);
                    armed = true;
                    return ownsSelection() ? 'ready' : 'lost';
                },
                finish() { armed = false; pending = undefined; return redirected ? 'lost' : 'ready'; },
                dispose() {
                    armed = false;
                    pending = undefined;
                    view.removeEventListener('beforeinput', before, true);
                    view.removeEventListener('blur', focus, true);
                    view.removeEventListener('textInput', textInput, true);
                }
            };
            try {
                view.addEventListener('beforeinput', before, true);
                view.addEventListener('blur', focus, true);
                view.addEventListener('textInput', textInput, true);
                return state;
            } catch (error) { state.dispose(); throw error; }
        })()"""
        Scope.run {
            Browser.activeIFrameLocal.use(active => active.map(_.executionContextId)).map { context =>
                Browser.use { tab =>
                    tab.session.acquire[EvaluateObjectParams, EvaluateObjectResult](
                        "Runtime.evaluate",
                        EvaluateObjectParams(script, contextId = context.map(_.value))
                    )(reply => release(tab.session, reply)).map { reply =>
                        reply.exceptionDetails match
                            case Present(error) => Abort.fail(BrowserScriptErrorException(ExceptionDetailsFormat.format(error)))
                            case Absent         =>
                                reply.result.objectId match
                                    case Absent => Abort.fail(BrowserProtocolErrorException.decodeFailure("fill", "missing editor handle"))
                                    case Present(id) =>
                                        check(tab.session, id, "prepare", selector)
                                            .andThen(CdpBackend.insertText(tab.session, InsertTextParams(text)))
                                            .andThen(check(tab.session, id, "finish", selector))
                    }
                }
            }
        }
    end fill

    private def check(backend: CdpBackend, id: String, method: String, selector: Selector)(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        CdpBackend.runtimeCallFunctionOn(backend, CallFunctionOnParams(id, s"function() { return this.$method(); }"))
            .map(CdpEvalDecoder.extractValueOrFail).map { status =>
                if status == "ready" then ()
                else
                    Abort.fail(BrowserElementNotActionableException(
                        Browser.selectorNodeDescription(Selector.toNode(selector)),
                        BrowserElementNotActionableException.Reason.NotFocused
                    ))
            }

    private def release(backend: CdpBackend, reply: EvaluateObjectResult)(using Frame): Unit < Async =
        reply.result.objectId match
            case Absent      => ()
            case Present(id) =>
                Abort.run[BrowserReadException](
                    CdpBackend.runtimeCallFunctionOn(backend, CallFunctionOnParams(id, "function() { this.dispose(); }"))
                ).andThen {
                    Abort.run[BrowserReadException](backend.sendUnit("Runtime.releaseObject", ReleaseObjectParams(id))).unit
                }
end EditableInput
