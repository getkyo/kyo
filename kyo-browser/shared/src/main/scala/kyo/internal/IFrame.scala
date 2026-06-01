package kyo.internal

import CdpTypes.*
import kyo.*

/** Internal handle for a discovered frame.
  *
  * The handle carries the CDP `frameId` plus the `executionContextId` observed for that frame at discovery time. The contextId is
  * snapshotted, NOT looked up lazily; same-origin frames keep the same contextId for the life of the frame.
  */
final private[kyo] case class IFrameHandle(
    frameId: FrameId,
    executionContextId: ExecutionContextId
) derives CanEqual

/** Iframe resolution helpers used by `Browser.iframe` / `Browser.iframes`. */
private[kyo] object IFrameResolver:

    /** Frame-tree flattening helper for `Browser.iframes`. Depth-first preorder: parent first, then each child subtree in turn. */
    def flattenFrameTree(node: FrameTreeNode): Seq[FrameId] =
        FrameId(node.frame.id) +: node.childFrames.getOrElse(Seq.empty).flatMap(flattenFrameTree)

    /** One pass of the iframe-resolution pipeline used by `Browser.iframe`. Resolves the selector to a backend node ref, then asks CDP for
      * the matching `frameId` (present only on `<iframe>` / `<frame>` / browsable `<object>`), then looks up the frame's execution context
      * in the per-tab map.
      *
      * Distinguishes the three failure modes via typed aborts:
      *
      *   - selector miss -> `BrowserElementNotFoundException`
      *   - matched but not a frame element -> `BrowserIFrameInvalidException` (`reason = BrowserIFrameInvalidException.Reason.NotAFrame`)
      *   - matched a frame but its execution context has not been observed yet -> `BrowserIFrameInvalidException` (`reason =
      *     BrowserIFrameInvalidException.Reason.ContextNotObserved`)
      */
    def resolveIFrameHandle(selector: Selector)(using
        Frame
    ): IFrameHandle < (Browser & Abort[BrowserReadException]) =
        Resolver.resolveOne(selector).map {
            case Absent =>
                Abort.fail(BrowserElementNotFoundException(kyo.Browser.selectorNodeDescription(Selector.toNode(selector))))
            case Present(ref) =>
                kyo.Browser.use[IFrameHandle, Async & Abort[BrowserReadException]] { tab =>
                    val s = tab.session
                    CdpBackend.describeNodeByBackendId(s, DescribeNodeByBackendIdParams(ref.backendNodeId)).map { res =>
                        res.node.frameId match
                            case Absent =>
                                Abort.fail(BrowserIFrameInvalidException(BrowserIFrameInvalidException.Reason.NotAFrame))
                            case Present(fidRaw) =>
                                val fid = FrameId(fidRaw)
                                tab.frameContexts.get.map { ctxMap =>
                                    ctxMap.get(fid) match
                                        case Present(cid) => IFrameHandle(fid, cid)
                                        case Absent =>
                                            Abort.fail(
                                                BrowserIFrameInvalidException(BrowserIFrameInvalidException.Reason.ContextNotObserved)
                                            )
                                }
                    }
                }
        }
end IFrameResolver
