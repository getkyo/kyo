package kyo.internal

import kyo.*

/** Typed CDP id wrappers: opaque types that keep the various Chrome DevTools Protocol identifiers from being silently interchanged. */
private[kyo] object CdpTypes:
    /** A CDP `Target.TargetID`: identifies a browser target (page/tab/worker). */
    opaque type TargetId = String

    /** A CDP `Target.SessionID`: identifies an attached session to a target. */
    opaque type SessionId = String

    /** A CDP `DOM.NodeId`: an agent-local integer id for a DOM node. */
    opaque type NodeId = Int // CDP uses integer node IDs
    /** A CDP `Page.FrameId`: identifies a frame within a page. */
    opaque type FrameId = String

    /** A CDP `Runtime.ExecutionContextId`: identifies a JS execution context. */
    opaque type ExecutionContextId = Int

    /** Typed wrapper around a CDP backend node id. Opaque at the public boundary so callers cannot construct arbitrary refs.
      *
      * Equality is by `backendNodeId` (stable across DOM mutations): two `NodeRef`s that reference the same backend node via different
      * resolution paths are equal.
      *
      * Opaque (not `final case class`): zero runtime overhead; the CDP backend node id is the natural underlying representation.
      */
    opaque type NodeRef = Int

    object TargetId:
        def apply(s: String): TargetId             = s
        extension (id: TargetId) def value: String = id

    object SessionId:
        def apply(s: String): SessionId             = s
        extension (id: SessionId) def value: String = id

    object NodeId:
        def apply(i: Int): NodeId             = i
        extension (id: NodeId) def value: Int = id

    object FrameId:
        def apply(s: String): FrameId             = s
        extension (id: FrameId) def value: String = id

    object ExecutionContextId:
        def apply(i: Int): ExecutionContextId             = i
        extension (id: ExecutionContextId) def value: Int = id

    object NodeRef:
        def apply(backendNodeId: Int): NodeRef        = backendNodeId
        extension (r: NodeRef) def backendNodeId: Int = r

    given CanEqual[TargetId, TargetId]                     = CanEqual.derived
    given CanEqual[SessionId, SessionId]                   = CanEqual.derived
    given CanEqual[NodeId, NodeId]                         = CanEqual.derived
    given CanEqual[FrameId, FrameId]                       = CanEqual.derived
    given CanEqual[ExecutionContextId, ExecutionContextId] = CanEqual.derived
    given CanEqual[NodeRef, NodeRef]                       = CanEqual.derived

    // --- Event-type enums ---

    private[kyo] enum MouseEventType(val wire: String) derives CanEqual:
        case Moved    extends MouseEventType("mouseMoved")
        case Pressed  extends MouseEventType("mousePressed")
        case Released extends MouseEventType("mouseReleased")
    end MouseEventType

    object MouseEventType:
        /** Serialises as the CDP wire string. Used by [[MouseEventParams]].
          *
          * Unknown wire values surface as a typed `Result.Failure[UnknownVariantException]` via the surrounding `Json.decode` pipeline (the
          * throw is caught by `Result.catching[DecodeException]`).
          *
          * This `given` does NOT take a `using Frame`: a Frame-parameterised given cannot be resolved by `Schema.derived`'s implicit
          * search (the derivation macro has no enclosing Frame and `Frame.derive` errors inside the kyo package), so callers that derive
          * a wrapping case class like [[MouseEventParams]] would otherwise silently fall back to the default sealed-trait encoding
          * (`{"Pressed":{}}` instead of `"mousePressed"`). The decode failure path picks up the reader's frame via `Schema.init`.
          */
        given Schema[MouseEventType] = Schema.init[MouseEventType](
            writeFn = (v, w) => w.string(v.wire),
            readFn = r =>
                r.string() match
                    case "mouseMoved"    => Moved
                    case "mousePressed"  => Pressed
                    case "mouseReleased" => Released
                    case other           => throw UnknownVariantException(Seq("MouseEventType"), other)(using r.frame)
        )
    end MouseEventType

    private[kyo] enum KeyEventType(val wire: String) derives CanEqual:
        case Down extends KeyEventType("keyDown")
        case Up   extends KeyEventType("keyUp")

    object KeyEventType:
        /** Serialises as the CDP wire string. Used by [[DispatchKeyEventParams]].
          *
          * Unknown wire values surface as a typed `Result.Failure[UnknownVariantException]` via the surrounding `Json.decode` pipeline (the
          * throw is caught by `Result.catching[DecodeException]`).
          *
          * Frame-free given for the same derivation-search reason as [[MouseEventType]].
          */
        given Schema[KeyEventType] = Schema.init[KeyEventType](
            writeFn = (v, w) => w.string(v.wire),
            readFn = r =>
                r.string() match
                    case "keyDown" => Down
                    case "keyUp"   => Up
                    case other     => throw UnknownVariantException(Seq("KeyEventType"), other)(using r.frame)
        )
    end KeyEventType

end CdpTypes

/** CDP wire request/response parameter case classes for the kyo-browser CDP layer.
  *
  * Each `private[kyo]` case class in this file mirrors the JSON shape of a Chrome DevTools Protocol request parameter object or response
  * result object, grouped by CDP domain (Target, Page, Runtime, Emulation, Input, DOM, Network, Browser). They are serialised and
  * deserialised via their derived `Schema` instances and used across the CDP layer to issue commands and decode their replies.
  */

/** Target domain. */
final private[kyo] case class CreateTargetParams(url: String, browserContextId: Maybe[String] = Absent) derives Schema
final private[kyo] case class CreateTargetResult(targetId: String) derives Schema
final private[kyo] case class AttachParams(targetId: String, flatten: Boolean) derives Schema
final private[kyo] case class AttachResult(sessionId: String) derives Schema
final private[kyo] case class CloseTargetParams(targetId: String) derives Schema
final private[kyo] case class CreateBrowserContextResult(browserContextId: String) derives Schema
final private[kyo] case class DisposeBrowserContextParams(browserContextId: String) derives Schema

/** Page domain. */
final private[kyo] case class NavigateParams(url: String) derives Schema
final private[kyo] case class NavigateResult(frameId: String, loaderId: Maybe[String] = Absent) derives Schema
final private[kyo] case class NavigationEntry(id: Int, url: String, title: String) derives Schema
final private[kyo] case class NavigationHistory(currentIndex: Int, entries: Seq[NavigationEntry]) derives Schema
final private[kyo] case class NavigateToEntryParams(entryId: Int) derives Schema
final private[kyo] case class ReloadParams(ignoreCache: Boolean = false) derives Schema

/** `Page.getFrameTree`: returns the frame tree of the current page. Used by attachTab to seed the tab's `rootFrameId` and by the public
  * `Browser.IFrame.list` discovery surface. The tree is a recursive structure: each node has a `frame` payload plus zero-or-more
  * `childFrames`.
  */
final private[kyo] case class FrameInfo(id: String, parentId: Maybe[String] = Absent, url: String) derives Schema
final private[kyo] case class FrameTreeNode(frame: FrameInfo, childFrames: Maybe[Seq[FrameTreeNode]] = Absent) derives Schema
final private[kyo] case class GetFrameTreeResult(frameTree: FrameTreeNode) derives Schema

/** CDP `RemoteObject` modelled as a discriminated sum type on the `type` field.
  *
  * CDP wire shape: `{"type": "string", "value": "hello"}` (`number` / `boolean` carry their typed `value` likewise),
  * `{"type": "object", "description": "Promise"}` (non-serialisable references carry only the description),
  * `{"type": "undefined"}` (no fields). The flat-discriminator schema (kyo-schema's `.discriminator("type")`) encodes
  * and decodes this exact shape, mapping each variant case class to its CDP type string by name.
  *
  * Variants follow CDP's `RemoteObject.type` enum: `object`, `function`, `undefined`, `string`, `number`, `boolean`,
  * `symbol`, `bigint`. Lowercase case-class names with backticks match the wire discriminator values verbatim.
  *
  * Decoding is permissive: extra wire fields (e.g. `value: {}` on a `type=object` reply when CDP cannot serialise the
  * payload, or CDP's `subtype` / `className` / `objectId` siblings) are silently ignored.
  *
  * Declared BEFORE [[EvalResult]] / [[ExceptionDetails]] (and any other case class with a `RemoteObject` field) so that
  * kyo-schema's macro-time `Expr.summon[Schema[RemoteObject]]` resolves to the discriminator-flat `given` in this
  * companion. Implicit search across forward references inside a single file is unreliable for `derives Schema`.
  */
sealed private[kyo] trait RemoteObject derives CanEqual

private[kyo] object RemoteObject:

    /** CDP's optional human-readable description for non-serialisable references (e.g. `"Promise"` for `Promise` objects). Returns
      * `Absent` for variants that don't carry a description (`undefined`).
      */
    extension (ro: RemoteObject)
        def descriptionOpt: Maybe[String] = ro match
            case s: RemoteObject.`string`    => s.description
            case n: RemoteObject.`number`    => n.description
            case b: RemoteObject.`boolean`   => b.description
            case o: RemoteObject.`object`    => o.description
            case f: RemoteObject.`function`  => f.description
            case s: RemoteObject.`symbol`    => s.description
            case b: RemoteObject.`bigint`    => b.description
            case _: RemoteObject.`undefined` => Absent
    end extension

    final case class `string`(value: String, description: Maybe[String] = Absent) extends RemoteObject
        derives Schema, CanEqual
    final case class `number`(value: Double, description: Maybe[String] = Absent) extends RemoteObject
        derives Schema, CanEqual
    final case class `boolean`(value: Boolean, description: Maybe[String] = Absent) extends RemoteObject
        derives Schema, CanEqual
    final case class `object`(subtype: Maybe[String] = Absent, description: Maybe[String] = Absent) extends RemoteObject
        derives Schema, CanEqual
    final case class `function`(description: Maybe[String] = Absent) extends RemoteObject
        derives Schema, CanEqual
    final case class `undefined`() extends RemoteObject
        derives Schema, CanEqual
    final case class `symbol`(description: Maybe[String] = Absent) extends RemoteObject
        derives Schema, CanEqual
    final case class `bigint`(value: String, description: Maybe[String] = Absent) extends RemoteObject
        derives Schema, CanEqual

    given Schema[RemoteObject] = Schema.derived[RemoteObject].discriminator("type")
end RemoteObject

/** Runtime domain. */
final private[kyo] case class EvalParams(
    expression: String,
    returnByValue: Boolean = true,
    awaitPromise: Boolean = false,
    contextId: Maybe[Int] = Absent
) derives Schema
final private[kyo] case class EvalResult(result: RemoteObject, exceptionDetails: Maybe[ExceptionDetails] = Absent) derives Schema
final private[kyo] case class CdpCallFrame(
    functionName: String = "",
    url: String = "",
    lineNumber: Int = 0,
    columnNumber: Int = 0
) derives Schema
final private[kyo] case class CdpStackTrace(callFrames: Seq[CdpCallFrame] = Seq.empty) derives Schema
final private[kyo] case class ExceptionDetails(
    text: Maybe[String] = Absent,
    exception: Maybe[RemoteObject] = Absent,
    stackTrace: Maybe[CdpStackTrace] = Absent
) derives Schema

/** `Runtime.evaluate` variant that returns a JS handle (objectId) instead of a serialised value. The Resolver pipeline needs the handle so
  * that `DOM.describeNode` can resolve the element by objectId rather than by the racy `DOM.getDocument + DOM.querySelector(rootNodeId,
  * ...)` chain.
  */
final private[kyo] case class EvaluateObjectParams(
    expression: String,
    returnByValue: Boolean = false,
    awaitPromise: Boolean = false,
    contextId: Maybe[Int] = Absent
) derives Schema

final private[kyo] case class RemoteObjectRef(
    `type`: String,
    objectId: Maybe[String] = Absent
) derives Schema

final private[kyo] case class EvaluateObjectResult(
    result: RemoteObjectRef,
    exceptionDetails: Maybe[ExceptionDetails] = Absent
) derives Schema

/** `Runtime.releaseObject`: releases a JS handle previously produced by `Runtime.evaluate` / `Runtime.getProperties`. The Resolver pipeline
  * pairs every acquisition with a `Scope.run + Scope.ensure` release so handles drain on Abort short-circuits.
  */
final private[kyo] case class ReleaseObjectParams(objectId: String) derives Schema

/** `Runtime.getProperties`: enumerate own properties of an object handle. Resolver.resolveAll uses this to extract per-element handles from
  * an array objectId in a single CDP round-trip.
  */
final private[kyo] case class GetPropertiesParams(
    objectId: String,
    ownProperties: Boolean = true,
    accessorPropertiesOnly: Boolean = false
) derives Schema

final private[kyo] case class PropertyDescriptor(
    name: String,
    value: Maybe[RemoteObjectRef] = Absent
) derives Schema

final private[kyo] case class GetPropertiesResult(
    result: Seq[PropertyDescriptor]
) derives Schema

/** Emulation domain. */
final private[kyo] case class ViewportParams(width: Int, height: Int, deviceScaleFactor: Double = 1.0, mobile: Boolean = false)
    derives Schema

// Emulation media + background-color override
final private[kyo] case class EmulatedMediaFeature(name: String, value: String) derives Schema
final private[kyo] case class SetEmulatedMediaParams(media: Maybe[String] = Absent, features: Maybe[Seq[EmulatedMediaFeature]] = Absent)
    derives Schema
final private[kyo] case class RgbaColor(r: Int, g: Int, b: Int, a: Maybe[Double] = Absent) derives Schema
final private[kyo] case class SetDefaultBackgroundColorOverrideParams(color: Maybe[RgbaColor] = Absent) derives Schema

// Screencast (Page domain)
final private[kyo] case class StartScreencastParams(
    format: Maybe[String] = Absent,
    quality: Maybe[Int] = Absent,
    everyNthFrame: Maybe[Int] = Absent
) derives Schema
final private[kyo] case class ScreencastFrameAckParams(sessionId: Int) derives Schema
final private[kyo] case class ScreencastFrameMetadata(
    scrollOffsetX: Double,
    scrollOffsetY: Double,
    timestamp: Maybe[Double] = Absent,
    offsetTop: Maybe[Double] = Absent,
    pageScaleFactor: Maybe[Double] = Absent,
    deviceWidth: Maybe[Double] = Absent,
    deviceHeight: Maybe[Double] = Absent
) derives Schema
final private[kyo] case class ScreencastFrameWire(data: String, metadata: ScreencastFrameMetadata, sessionId: Int) derives Schema

// Runtime console events
final private[kyo] case class RemoteObjectValue(`type`: String, value: Maybe[String] = Absent, description: Maybe[String] = Absent)
    derives Schema
final private[kyo] case class CallFrameWire(url: Maybe[String] = Absent, lineNumber: Maybe[Int] = Absent, columnNumber: Maybe[Int] = Absent)
    derives Schema
final private[kyo] case class StackTraceWire(callFrames: Seq[CallFrameWire] = Nil) derives Schema
final private[kyo] case class ConsoleApiCalledWire(
    `type`: String,
    args: Seq[RemoteObjectValue] = Nil,
    timestamp: Maybe[Double] = Absent,
    stackTrace: Maybe[StackTraceWire] = Absent
) derives Schema
final private[kyo] case class ExceptionDetailsWire(
    text: String,
    url: Maybe[String] = Absent,
    lineNumber: Maybe[Int] = Absent,
    stackTrace: Maybe[StackTraceWire] = Absent,
    exception: Maybe[RemoteObjectValue] = Absent
) derives Schema
final private[kyo] case class ExceptionThrownWire(timestamp: Maybe[Double] = Absent, exceptionDetails: ExceptionDetailsWire) derives Schema

/** `Emulation.setFocusEmulationEnabled`: makes Chrome treat the page as if the tab were the system-focused window. Without this,
  * programmatic `el.focus()` calls update `document.activeElement` but do NOT dispatch focus/blur DOM events; framework listeners (kyo-ui,
  * React onFocus, etc.) silently miss user-flow events that originate from `Browser.fill` or `Browser.press`-on-unfocused. Issued once per
  * attached session inside `BrowserTabSetup.enableDomains`.
  */
final private[kyo] case class FocusEmulationParams(enabled: Boolean) derives Schema

/** `Page.captureScreenshot`. */
final private[kyo] case class ScreenshotClip(x: Double, y: Double, width: Double, height: Double, scale: Double = 1.0) derives Schema
final private[kyo] case class ScreenshotParams(
    format: Browser.ScreenshotFormat = Browser.ScreenshotFormat.Png,
    quality: Maybe[Int] = Absent,
    clip: Maybe[ScreenshotClip] = Absent,
    captureBeyondViewport: Maybe[Boolean] = Absent,
    fromSurface: Maybe[Boolean] = Absent
) derives Schema
final private[kyo] case class ScreenshotResult(data: String) derives Schema

/** `Page.printToPDF`. */
final private[kyo] case class PrintToPdfResult(data: String) derives Schema

/** Input domain. */
final private[kyo] case class DispatchKeyEventParams(
    `type`: CdpTypes.KeyEventType,
    key: Maybe[String] = Absent,
    text: Maybe[String] = Absent,
    code: Maybe[String] = Absent,
    windowsVirtualKeyCode: Maybe[Int] = Absent,
    modifiers: Maybe[Int] = Absent
) derives Schema

/** DOM domain. */
final private[kyo] case class GetDocumentParams(depth: Maybe[Int] = Absent) derives Schema
final private[kyo] case class GetBoxModelParams(backendNodeId: Int) derives Schema
final private[kyo] case class BoxModel(model: BoxModelContent) derives Schema
final private[kyo] case class BoxModelContent(content: Seq[Double]) derives Schema

/** `Input.dispatchMouseEvent`. */
final private[kyo] case class MouseEventParams(
    `type`: CdpTypes.MouseEventType,
    x: Int,
    y: Int,
    button: Maybe[String] = Absent,
    clickCount: Maybe[Int] = Absent
) derives Schema

/** Network domain. */
final private[kyo] case class NetworkGetCookiesParams(urls: Maybe[Seq[String]] = Absent) derives Schema

/** `Network.getCookies` returns the raw CDP wire shape (`CookieWire`); the public `Browser.Cookie` value type is built at the CDP boundary
  * by `CookieWire.toCookie`.
  */
final private[kyo] case class NetworkGetCookiesResult(cookies: Seq[CookieWire]) derives Schema

final private[kyo] case class NetworkSetCookieParams(
    name: String,
    value: String,
    url: Maybe[String] = Absent,
    domain: Maybe[String] = Absent,
    path: Maybe[String] = Absent,
    expires: Maybe[Double] = Absent,
    httpOnly: Maybe[Boolean] = Absent,
    secure: Maybe[Boolean] = Absent,
    sameSite: Maybe[String] = Absent
) derives Schema

final private[kyo] case class NetworkDeleteCookiesParams(
    name: String,
    url: Maybe[String] = Absent,
    domain: Maybe[String] = Absent
) derives Schema

/** DOM domain, file upload. */
final private[kyo] case class DescribeNodeParams(nodeId: Int) derives Schema

/** `DOM.describeNode` keyed by `backendNodeId`: used by the iframe resolution path (`Browser.IFrame.of`) to read the `frameId` field off a
  * NodeRef without a `DOM.requestNode` round-trip.
  */
final private[kyo] case class DescribeNodeByBackendIdParams(backendNodeId: Int, depth: Int = 1) derives Schema
final private[kyo] case class DescribeNodeResult(node: DescribedNode) derives Schema
final private[kyo] case class DescribedNode(backendNodeId: Int, frameId: Maybe[String] = Absent) derives Schema
final private[kyo] case class SetFileInputFilesParams(files: Seq[String], backendNodeId: Int) derives Schema

/** `DOM.requestNode`: pushes the path from the JS objectId's node to the document root into the agent's tracked tree, returning the
  * agent-local nodeId. Used by the Resolver pipeline before `DOM.describeNode(nodeId)`: describeNode by objectId alone does NOT populate
  * the tracked tree, and Chrome then returns a placeholder backendNodeId for the un-tracked node.
  */
final private[kyo] case class RequestNodeParams(objectId: String) derives Schema
final private[kyo] case class RequestNodeResult(nodeId: Int) derives Schema

/** Target domain, target listing. */
final private[kyo] case class TargetInfo(targetId: String, `type`: String, url: String) derives Schema
final private[kyo] case class GetTargetsResult(targetInfos: Seq[TargetInfo]) derives Schema

/** `Page.handleJavaScriptDialog`. */
final private[kyo] case class HandleJavaScriptDialogParams(accept: Boolean, promptText: String) derives Schema

/** `Page.addScriptToEvaluateOnNewDocument`. */
final private[kyo] case class AddScriptToEvaluateOnNewDocumentParams(source: String) derives Schema

/** CDP `Runtime.ExecutionContextDescription.auxData`. We only care about the `isDefault` flag and the `frameId` the context belongs to. */
final private[kyo] case class ExecutionContextAuxData(
    isDefault: Boolean = false,
    frameId: String = ""
) derives Schema

/** CDP `Runtime.ExecutionContextDescription`. */
final private[kyo] case class ExecutionContextDescription(
    id: Int,
    auxData: ExecutionContextAuxData = ExecutionContextAuxData()
) derives Schema

/** Params payload of `Runtime.executionContextCreated` events. */
final private[kyo] case class ExecutionContextCreatedParams(
    context: ExecutionContextDescription
) derives Schema

/** Params payload of `Runtime.executionContextDestroyed` events. */
final private[kyo] case class ExecutionContextDestroyedParams(
    executionContextId: Int
) derives Schema

/** Settlement-status JSON returned from the navigation gate's polling JS. */
final private[kyo] case class NavigationSettleState(
    ready: Boolean = false,
    url: String = "(unknown)",
    status: Int = 0
) derives Schema

/** `rect` payload of the actionability JS reply. Default-zero so a malformed wire payload (e.g. `actionable: true` with `rect: null`) still
  * decodes; the caller treats a zero-width / zero-height rect as `Hidden`.
  */
final private[kyo] case class ActionabilityRect(
    x: Int = 0,
    y: Int = 0,
    width: Int = 0,
    height: Int = 0
) derives Schema

/** Inner value of the actionability JS reply: the `{actionable, navigatesOnClick, rect, reason, ...}` object that the JS template returns.
  * Optional fields carry per-reason diagnostic data:
  *   - `notVisibleCause`: "DisplayNone" | "VisibilityHidden" | "OpacityZero" | "ZeroComputedSize"
  *   - `disabledKind`: "Attribute" | "AriaDisabled" | "FieldsetDisabled" | "PointerEventsNone"
  *   - `viewportRect`: viewport snapshot at the time of the NotInViewport check
  *   - `actualHit`: CSS selector fragment describing the covering element (OutsideHitTarget)
  *   - `tagName`: lowercase tag name of a non-fillable target (NotFillable)
  */
final private[kyo] case class ActionabilityValue(
    actionable: Boolean,
    navigatesOnClick: Boolean = false,
    rect: Maybe[ActionabilityRect] = Absent,
    reason: Maybe[String] = Absent,
    notVisibleCause: Maybe[String] = Absent,
    disabledKind: Maybe[String] = Absent,
    viewportRect: Maybe[ActionabilityRect] = Absent,
    actualHit: Maybe[String] = Absent,
    tagName: Maybe[String] = Absent
) derives Schema

/** CDP `RemoteObject` shape carrying an `ActionabilityValue` as its inline `value` (only present when the JS returned via
  * `returnByValue=true`).
  */
final private[kyo] case class ActionabilityRemoteObject(
    value: Maybe[ActionabilityValue] = Absent
) derives Schema

/** Top-level CDP response envelope for the actionability `Runtime.evaluate` call. */
final private[kyo] case class ActionabilityResponse(
    result: Maybe[ActionabilityRemoteObject] = Absent,
    exceptionDetails: Maybe[ExceptionDetails] = Absent
) derives Schema

/** Wire shape for the in-page `getBoundingClientRect()` snapshot used by `Browser.screenshotElement`. */
final private[kyo] case class BoundingRectWire(x: Double, y: Double, width: Double, height: Double) derives Schema

/** Wire shape for the full-page dimension probe used by `Browser.screenshotFullPage`. `content` is the total scroll height in CSS px,
  * `viewport` is the inner height, and `width` is the inner width (all from `window` / `document.documentElement`).
  */
final private[kyo] case class FullPageDimsWire(content: Int, viewport: Int, width: Int) derives Schema

/** Wire shape for the box-stable check in `Browser.screenshotElement`. `found` is false when the element does not exist; `ok` is false when
  * the element exists but its bounding rect is still moving. When both `found` and `ok` are true, the remaining fields hold the post-scroll
  * rect in CSS px.
  */
final private[kyo] case class ElementClipWire(
    found: Boolean = true,
    ok: Boolean,
    x: Double = 0,
    y: Double = 0,
    width: Double = 0,
    height: Double = 0
) derives Schema

/** Wire envelope used by `Browser.mockFetchResponse` to pass the mock definition to the JS interceptor via `JSON.parse`.
  *
  * `headers` is an ordered list of `(name, value)` pairs (rather than a `Map`-like shape) so the JS payload can preserve duplicate
  * header names (e.g. multiple `Set-Cookie` entries) and ordering. Encoded as `Chunk[MockHeader]` for JSON friendliness.
  */
final private[kyo] case class MockResponseEnvelope(status: Int, body: String, headers: Chunk[MockHeader]) derives Schema

/** Single `(name, value)` entry inside [[MockResponseEnvelope.headers]]. */
final private[kyo] case class MockHeader(name: String, value: String) derives Schema
