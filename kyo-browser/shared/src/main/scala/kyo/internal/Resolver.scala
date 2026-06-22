package kyo.internal

import CdpTypes.NodeRef
import kyo.*

/** Central element-resolution pipeline.
  *
  * Maps a [[Selector]] to a typed [[NodeRef]] (single-match) or a `Chunk[NodeRef]` (multi-match). Presence is expressed in the return type
  * via [[Maybe]] and [[Chunk]]; there is no string sentinel.
  *
  * Implementation strategy (the objectId + requestNode pipeline):
  *   - **Evaluate**: `Runtime.evaluate` runs the JS resolver expression with `returnByValue = false`, producing a CDP-managed `objectId` JS
  *     handle.
  *   - **Request node**: `DOM.requestNode({objectId})` pushes the path from that node up to the document root into the agent's tracked tree
  *     and returns an agent-local `nodeId`.
  *   - **Describe node**: `DOM.describeNode({nodeId})` resolves that nodeId to the real, globally-unique `backendNodeId`.
  *   - **Why the two-step**: `DOM.describeNode(objectId)` directly does NOT push the node into the agent's tree; it returns a placeholder
  *     `backendNodeId` for un-tracked nodes that collides across distinct elements in a fresh tab (every element comes back as
  *     backendNodeId 3). The `requestNode + describeNode(nodeId)` pair avoids that collision.
  *   - **Mutation-safe**: because `requestNode` is keyed by the JS objectId (a stable handle on the live JS object, NOT keyed by document
  *     tree state), DOM mutations between `Runtime.evaluate` and `requestNode` cannot invalidate the handle, so concurrent `Browser.click`
  *     calls on the same tab do not race on `DOM.querySelector("invalid response")`.
  *   - **Handle release**: the handle is released via `Runtime.releaseObject` once `describeNode` returns; the release is awaited inline so
  *     it drains before the surrounding scope's `CdpClient.close` runs (a fire-and-forget release racing with tab teardown leaves the
  *     request in flight and forces `close` to wait its full grace period).
  *
  * @see
  *   [[SelectorJs]] for the JS resolver expressions, [[CdpBackend]] for the typed CDP command surface, and [[NodeRef]] for the resolved
  *   node handle.
  */
private[kyo] object Resolver:

    /** Resolves the first match of the selector to a typed backend node ref, if any. */
    def resolveOne(selector: Selector)(using
        Frame
    ): Maybe[NodeRef] < (Browser & Abort[BrowserReadException]) =
        Browser.activeIFrameLocal.use(active => active.map(_.executionContextId)).map { ctx =>
            val ctxOpt = ctx.map(c => CdpTypes.ExecutionContextId.value(c))
            Browser.use { tab =>
                val s      = tab.session
                val node   = Selector.toNode(selector)
                val jsExpr = SelectorJs.resolveElementJs(node)
                CdpBackend.recoverContextDestroyed {
                    s.send[EvaluateObjectParams, EvaluateObjectResult](
                        CdpBackend.RuntimeEvaluateMethod,
                        EvaluateObjectParams(s"(() => $jsExpr)()", returnByValue = false, awaitPromise = false, contextId = ctxOpt)
                    )
                }
                    .map { evalResult =>
                        evalResult.exceptionDetails match
                            case Present(details) =>
                                // Use ExceptionDetailsFormat.format to combine `text` AND `exception.description`. Chrome's
                                // `text` for a malformed-selector SyntaxError is just "Uncaught" on some platforms (JS/Native);
                                // the actual error message naming the bad selector lives in `exception.description`. Reading
                                // only `text` drops the diagnostic the test asserts on.
                                val msg = ExceptionDetailsFormat.format(details)
                                val msgOrDefault =
                                    if msg.isEmpty || msg == "Unknown script error" then "Runtime.evaluate exception" else msg
                                Abort.fail(BrowserProtocolErrorException(CdpBackend.RuntimeEvaluateMethod, msgOrDefault))
                            case Absent =>
                                evalResult.result.objectId match
                                    case Absent =>
                                        Absent: Maybe[NodeRef]
                                    case Present(objectId) =>
                                        // `Scope.run + Scope.ensure` runs the release on success AND on Abort/Panic /
                                        // interruption, so the JS handle drains even if `describeByObjectId` aborts mid-flight
                                        // (otherwise the handle leaks until tab teardown, forcing `CdpClient.close` to wait
                                        // its full grace period). Plain `Sync.ensure` does NOT fire on Abort short-circuits
                                        // see the equivalent dialog-handler restore comment at `Browser.withDialogs`.
                                        Scope.run {
                                            Scope.ensure(releaseObjectQuiet(s, objectId)).andThen {
                                                Abort.recover[BrowserProtocolErrorException] { e =>
                                                    if isStaleNodeError(e) then (Absent: Maybe[NodeRef])
                                                    else Abort.fail(e)
                                                } {
                                                    describeByObjectId(s, objectId).map { backendNodeId =>
                                                        Present(NodeRef(backendNodeId)): Maybe[NodeRef]
                                                    }
                                                }
                                            }
                                        }
                                end match
                        end match
                    }
            }
        }
    end resolveOne

    /** Resolves every match of the selector to a typed backend node ref in document order. */
    def resolveAll(selector: Selector)(using
        Frame
    ): Chunk[NodeRef] < (Browser & Abort[BrowserReadException]) =
        Browser.activeIFrameLocal.use(active => active.map(_.executionContextId)).map { ctx =>
            val ctxOpt = ctx.map(c => CdpTypes.ExecutionContextId.value(c))
            Browser.use { tab =>
                val s      = tab.session
                val node   = Selector.toNode(selector)
                val jsExpr = SelectorJs.resolveAllElementsJs(node)
                // Return null (not an empty array) when there are no matches so the CDP response carries no
                // objectId. The Absent-objectId branch below short-circuits to Chunk.empty without the extra
                // DOM.getProperties + Runtime.releaseObject round-trips that a non-null empty array would
                // require, shaving ~2 CDP messages per probe on the common zero-match retry path.
                CdpBackend.recoverContextDestroyed {
                    s.send[EvaluateObjectParams, EvaluateObjectResult](
                        CdpBackend.RuntimeEvaluateMethod,
                        EvaluateObjectParams(
                            s"(() => { const _r = $jsExpr; return _r.length ? _r : null; })()",
                            returnByValue = false,
                            awaitPromise = false,
                            contextId = ctxOpt
                        )
                    )
                }
                    .map { evalResult =>
                        evalResult.exceptionDetails match
                            case Present(details) =>
                                // Use ExceptionDetailsFormat.format to combine `text` AND `exception.description`. Chrome's
                                // `text` for a malformed-selector SyntaxError is just "Uncaught" on some platforms (JS/Native);
                                // the actual error message naming the bad selector lives in `exception.description`. Reading
                                // only `text` drops the diagnostic the test asserts on.
                                val msg = ExceptionDetailsFormat.format(details)
                                val msgOrDefault =
                                    if msg.isEmpty || msg == "Unknown script error" then "Runtime.evaluate exception" else msg
                                Abort.fail(BrowserProtocolErrorException(CdpBackend.RuntimeEvaluateMethod, msgOrDefault))
                            case Absent =>
                                evalResult.result.objectId match
                                    case Absent =>
                                        Chunk.empty[NodeRef]
                                    case Present(arrayObjectId) =>
                                        CdpBackend.getProperties(s, GetPropertiesParams(arrayObjectId, ownProperties = true))
                                            .map { props =>
                                                // Keep only numeric-indexed descriptors with a Present objectId (the array
                                                // elements; CDP also returns 'length' and possibly accessor props which we
                                                // skip). Sort by index defensively: CDP returns own props in insertion
                                                // order for arrays but we don't depend on that for cross-version safety.
                                                val indexed: Chunk[(Int, String)] = Chunk.from(props.result.flatMap { d =>
                                                    d.name.toIntOption match
                                                        case Some(i) =>
                                                            (d.value.flatMap(_.objectId): @unchecked) match
                                                                case Present(oid) => Seq((i, oid))
                                                                case Absent       => Seq.empty
                                                        case None => Seq.empty
                                                }.sortBy(_._1))
                                                // `Scope.run + Scope.ensure` releases each handle (per-element + array) on
                                                // success AND on Abort/Panic / interruption. Plain `Sync.ensure` does NOT
                                                // fire on Abort short-circuits, so a mid-loop describe failure would leak
                                                // the array handle plus any not-yet-described element handles until tab
                                                // teardown.
                                                Scope.run {
                                                    Scope.ensure(releaseObjectQuiet(s, arrayObjectId)).andThen {
                                                        Kyo.foreach(indexed) { case (_, elemObjectId) =>
                                                            Scope.run {
                                                                Scope.ensure(releaseObjectQuiet(s, elemObjectId)).andThen {
                                                                    // A node that vanished mid-snapshot makes the WHOLE snapshot
                                                                    // inconsistent; re-raise as a retryable
                                                                    // BrowserElementNotFoundException so the enclosing assertion
                                                                    // loop re-resolves a clean snapshot. Dropping just the stale
                                                                    // node instead would return a partial stable-looking set and
                                                                    // mask a genuinely flickering page.
                                                                    Abort.recover[BrowserProtocolErrorException] { e =>
                                                                        if isStaleNodeError(e) then
                                                                            Abort.fail(BrowserElementNotFoundException(
                                                                                Selector.toNode(selector).toString
                                                                            ))
                                                                        else Abort.fail(e)
                                                                    } {
                                                                        describeByObjectId(s, elemObjectId).map(id => NodeRef(id))
                                                                    }
                                                                }
                                                            }
                                                        }.map(Chunk.from)
                                                    }
                                                }
                                            }
                                end match
                        end match
                    }
            }
        }
    end resolveAll

    // --- Internal ---

    /** A CDP "Could not find node with given id" error means the node went stale between resolution steps: the DOM mutated under us (common
      * while a flickering element re-renders). It is a transient resolution miss, not a protocol fault: callers drop the node so the
      * enclosing assertion/retry loop re-resolves a clean snapshot, rather than aborting with a terminal protocol error.
      */
    private[internal] def isStaleNodeError(e: BrowserProtocolErrorException): Boolean =
        e.error.contains(CdpErrorStrings.StaleNodeErrorMessage)

    /** Two-step describe-by-objectId pipeline with lazy bootstrap:
      *   1. `DOM.requestNode(objectId)`: pushes the node path from the JS handle up to the document root into the agent's tracked tree and
      *      returns the agent-local `nodeId`.
      *   1. `DOM.describeNode(nodeId)`: resolves the nodeId to the real, globally-unique `backendNodeId`.
      *
      * Two-step is required because `DOM.describeNode(objectId)` directly does NOT push the node into the agent's tree, and Chrome returns
      * a placeholder `backendNodeId` (e.g. 3) that collides across distinct elements in fresh tabs. The pipeline still preserves the
      * objectId-stability property: the JS handle is the keying anchor for `requestNode`, NOT a `rootNodeId` from `DOM.getDocument` that
      * DOM mutations could invalidate.
      *
      * `requestNode` requires the agent's `m_document` to be set, which Chromium does NOT guarantee until `DOM.getDocument` has been called
      * for the current document. After a `Page.navigate`, `m_document` may briefly be null; in that window `requestNode` returns
      * `nodeId: 0`. We don't pre-empt this by issuing `getDocument` per resolve; concurrent fibers' `getDocument` calls race on the agent's
      * nodeId allocator and invalidate each other's nodeIds (each `getDocument` re-pushes the document root with a fresh nodeId generation,
      * leaving in-flight `describeNode(nodeId)` calls to fail with `Could not find node with given id`). Instead we lazy- bootstrap: if
      * `requestNode` returns `0`, call `getDocument` once and retry. Once the agent's `m_document` is set, subsequent resolves go straight
      * through without bootstrap, so the bootstrap cost is paid at most once per navigation per session.
      */
    private def describeByObjectId(s: CdpBackend, objectId: String)(using
        Frame
    ): Int < (Async & Abort[BrowserReadException]) =
        requestAndDescribe(s, objectId).map {
            case 0             => CdpBackend.getDocument(s, GetDocumentParams(Present(0))).andThen(requestAndDescribe(s, objectId))
            case backendNodeId => backendNodeId
        }

    /** Single-pass `requestNode → describeNode(nodeId)`. Returns the `backendNodeId` on success, or `0` if `requestNode` returned `nodeId:
      * 0` (agent has no document; caller is expected to bootstrap and retry).
      */
    private def requestAndDescribe(s: CdpBackend, objectId: String)(using
        Frame
    ): Int < (Async & Abort[BrowserReadException]) =
        CdpBackend.requestNode(s, RequestNodeParams(objectId)).map { req =>
            if req.nodeId == 0 then 0
            else
                CdpBackend.describeNodeByNodeId(s, DescribeNodeParams(req.nodeId)).map { desc =>
                    desc.node.backendNodeId
                }
        }

    /** Synchronous release of a CDP `objectId` handle. Awaits Chrome's response so the request drains before the surrounding scope's
      * `CdpClient.close` runs; otherwise a fire-and-forget release racing with tab teardown leaves the request in flight and forces `close`
      * to wait out its full grace period. Any release failure is swallowed via `Abort.run` because a stale handle past tab close is
      * harmless (Chrome GCs the JS object on execution-context teardown anyway).
      */
    private def releaseObjectQuiet(s: CdpBackend, objectId: String)(using Frame): Unit < Async =
        Abort.run(s.sendUnit("Runtime.releaseObject", ReleaseObjectParams(objectId))).unit

end Resolver
