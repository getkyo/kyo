package kyo

import kyo.internal.CdpBackend

/** Root of the kyo-browser exception hierarchy.
  *
  * `BrowserException` is the abstract supertype of every typed failure produced by the browser automation API. Concrete cases capture the
  * failure mode (connection lost, element not found, navigation failed, script error, assertion timed out) so they can be matched in
  * `Abort` error channels without inspecting messages or stack traces.
  *
  * The hierarchy layers two axes of categorization:
  *
  *   - Concrete cases such as [[BrowserConnectionLostException]] and [[BrowserElementNotFoundException]] are the values an action actually
  *     raises.
  *   - Marker traits ([[BrowserConnectionException]], [[BrowserElementException]], [[BrowserNavigationException]],
  *     [[BrowserScriptException]], [[BrowserAssertionException]]) group cases by topic so error handlers can recover uniformly across
  *     related failures.
  *
  * @see
  *   [[BrowserConnectionException]] / [[BrowserElementException]] / [[BrowserNavigationException]] / [[BrowserScriptException]] /
  *   [[BrowserAssertionException]] for the topical groupings.
  * @see
  *   [[kyo.Browser]] for the actions that raise these errors.
  */
sealed abstract class BrowserException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

/** Operation-row trait for actions that read page state (navigation, eval, screenshot, etc.).
  *
  * All concrete exception types that can surface from read-tier operations extend this trait. `BrowserMutationException` and
  * `BrowserAssertionException` are subtypes of this trait (mutation and assertion operations are also read-capable), so an
  * `Abort[BrowserReadException]` channel catches any browser failure except lifecycle/setup failures.
  */
sealed trait BrowserReadException extends BrowserException

/** Operation-row trait for actions that mutate page state or require element resolution.
  *
  * Extends [[BrowserReadException]]: every mutation failure is also a read failure. Concrete exception types for element resolution
  * (`BrowserElementNotFoundException`, `BrowserElementNotActionableException`) extend this trait.
  */
sealed trait BrowserMutationException extends BrowserReadException

/** Marker for failures rooted in the CDP connection (process death, protocol decode, etc.).
  *
  * @see
  *   [[BrowserConnectionLostException]], [[BrowserProtocolErrorException]].
  */
sealed trait BrowserConnectionException extends BrowserException

/** Marker for failures locating or interacting with a DOM element.
  *
  * @see
  *   [[BrowserElementNotFoundException]], [[BrowserElementNotActionableException]].
  */
sealed trait BrowserElementException extends BrowserException with BrowserReadException

/** Marker for failures triggered while loading a URL.
  *
  * @see
  *   [[BrowserNavigationFailedException]].
  */
sealed trait BrowserNavigationException extends BrowserException

/** Marker for failures evaluating user JavaScript via the Runtime domain.
  *
  * @see
  *   [[BrowserScriptErrorException]].
  */
sealed trait BrowserScriptException extends BrowserException

/** Operation-row trait for failures of `assert*` actions (waited-for conditions that did not hold).
  *
  * Also covers element-resolution failures inside assertions ([[BrowserElementNotFoundException]]). Extends [[BrowserMutationException]]
  * so an `Abort[BrowserReadException]` channel catches assertion failures uniformly.
  *
  * @see
  *   [[BrowserAssertionTimedOutException]], [[BrowserElementNotFoundException]].
  */
sealed trait BrowserAssertionException extends BrowserException with BrowserMutationException

/** Marker for failures rooted in iframe scoping (frame not an iframe, frame removed, execution context destroyed).
  *
  * @see
  *   [[BrowserIFrameInvalidException]].
  */
sealed trait BrowserIFrameException extends BrowserException

/** Marker for failures setting up the browser environment -- launching the executable, downloading or extracting the
  * `chrome-headless-shell` archive, creating user-data temp dirs, or detecting an unsupported platform. Distinct from
  * [[BrowserConnectionException]]: a setup failure happens before any CDP session exists, while a connection failure means an established
  * session has dropped.
  *
  * @see
  *   [[BrowserSetupFailedException]].
  */
sealed trait BrowserSetupException extends BrowserException

// --- Connection failures ---

/** The CDP connection terminated unexpectedly (e.g. the Chrome process exited).
  *
  * @see
  *   [[BrowserConnectionException]] for the topical marker.
  * @see
  *   [[BrowserSetupFailedException]] for the launch / download / install variant.
  */
final case class BrowserConnectionLostException(message: String, cause: Maybe[Throwable] = Absent)(using Frame)
    extends BrowserException(message, cause.fold[String | Throwable]("")(identity))
    with BrowserConnectionException with BrowserReadException derives CanEqual

/** Setup of the browser environment failed before any CDP session could be established.
  *
  * Raised by:
  *
  *   - [[kyo.internal.BrowserLauncher]] when the executable fails to start, the user-data temp dir cannot be created, or Chrome does not
  *     write the `DevToolsActivePort` file within the configured timeout.
  *   - [[kyo.internal.ChromeDownloader]] when the platform is unsupported, the binary cannot be fetched, the archive cannot be extracted,
  *     or the executable cannot be made runnable (`chmod +x`).
  *
  * @see
  *   [[BrowserSetupException]] for the topical marker.
  */
final case class BrowserSetupFailedException(message: String, cause: Maybe[Throwable] = Absent)(using Frame)
    extends BrowserException(message, cause.fold[String | Throwable]("")(identity))
    with BrowserSetupException derives CanEqual

/** Companion with smart constructors for [[BrowserSetupFailedException]]. */
object BrowserSetupFailedException:
    /** Builds an instance from a message and a raw `Throwable` cause. */
    def apply(message: String, cause: Throwable)(using Frame): BrowserSetupFailedException =
        BrowserSetupFailedException(message, Present(cause))
end BrowserSetupFailedException

/** A CDP method returned a protocol-level error or an undecodable result.
  *
  * @see
  *   [[BrowserConnectionException]] for the topical marker.
  * @see
  *   [[BrowserProtocolErrorException.decodeFailure]] and [[BrowserProtocolErrorException.unexpectedReply]] for the typical constructors.
  */
final case class BrowserProtocolErrorException(method: String, error: String)(using Frame)
    extends BrowserException(s"$method: $error")
    with BrowserConnectionException with BrowserReadException derives CanEqual

/** Companion with smart constructors for [[BrowserProtocolErrorException]]. */
object BrowserProtocolErrorException:
    /** Builds an instance describing a JSON-decode failure on a CDP reply. */
    def decodeFailure(method: String, result: String)(using Frame): BrowserProtocolErrorException =
        BrowserProtocolErrorException(method, s"decode failed: $result")

    /** Builds an instance describing a structurally unexpected CDP reply. */
    def unexpectedReply(method: String, value: String)(using Frame): BrowserProtocolErrorException =
        BrowserProtocolErrorException(method, s"unexpected reply: $value")

    /** Builds an instance describing a `Runtime.evaluate` failure on a library-internal JS expression.
      *
      * Internal JS templates are library-controlled, so an `exceptionDetails` payload from CDP indicates a defect in one of those templates
      * rather than a user-visible script error. Surfacing it as a typed `Abort` failure keeps the defect visible.
      */
    def internalEvalFailed(message: String)(using Frame): BrowserProtocolErrorException =
        BrowserProtocolErrorException(CdpBackend.RuntimeEvaluateMethod, s"kyo-browser internal JS evaluation failed: $message")
end BrowserProtocolErrorException

// --- Element failures ---

/** No element matched the given selector within the configured timeout.
  *
  * Implements both [[BrowserElementException]] and [[BrowserAssertionException]] because a "must exist" assertion that fails is a special
  * case of element lookup failure.
  *
  * @see
  *   [[BrowserElementNotActionableException]] for elements that match but cannot be used.
  */
final case class BrowserElementNotFoundException(selector: String)(using Frame)
    extends BrowserException(s"Element not found: $selector")
    with BrowserElementException with BrowserAssertionException with BrowserMutationException
    derives CanEqual

/** An element matched the selector but cannot accept the requested interaction.
  *
  * @see
  *   [[BrowserElementNotActionableException.Reason]] for the enumerated reasons.
  * @see
  *   [[BrowserElementNotFoundException]] for the no-match case.
  */
final case class BrowserElementNotActionableException(selector: String, reason: BrowserElementNotActionableException.Reason)(using Frame)
    extends BrowserException(s"Element not actionable: $selector -- ${reason.description}")
    with BrowserElementException with BrowserMutationException derives CanEqual

object BrowserElementNotActionableException:

    /** Reasons an element is not actionable.
      *
      * Each case carries diagnostic data describing the precise failure mode. The sealed trait declares a [[description]] method so
      * [[BrowserElementNotActionableException]] can compose a human-readable error message without a separate companion dispatch.
      */
    sealed trait Reason derives CanEqual, Schema:
        def description: String

    object Reason:

        case object NotAttached extends Reason:
            val description = "element is not attached to the DOM"

        sealed trait NotVisibleCause derives CanEqual, Schema
        object NotVisibleCause:
            case object DisplayNone      extends NotVisibleCause
            case object VisibilityHidden extends NotVisibleCause
            case object OpacityZero      extends NotVisibleCause
            case object ZeroComputedSize extends NotVisibleCause

            /** Sentinel for an unknown sub-cause string received over the wire. The raw CDP value is preserved so callers can inspect it. */
            final case class Other(raw: String) extends NotVisibleCause derives CanEqual, Schema
        end NotVisibleCause

        final case class NotVisible(cause: NotVisibleCause) extends Reason derives CanEqual, Schema:
            def description = cause match
                case NotVisibleCause.DisplayNone      => "element is not visible (display:none)"
                case NotVisibleCause.VisibilityHidden => "element is not visible (visibility:hidden)"
                case NotVisibleCause.OpacityZero      => "element is not visible (opacity:0)"
                case NotVisibleCause.ZeroComputedSize => "element is hidden (zero computed size)"
                case NotVisibleCause.Other(raw)       => s"element is not visible (unknown cause: $raw)"
        end NotVisible

        sealed trait DisabledKind derives CanEqual, Schema
        object DisabledKind:
            case object Attribute         extends DisabledKind
            case object AriaDisabled      extends DisabledKind
            case object FieldsetDisabled  extends DisabledKind
            case object PointerEventsNone extends DisabledKind

            /** Sentinel for an unknown sub-kind string received over the wire. The raw CDP value is preserved so callers can inspect it. */
            final case class Other(raw: String) extends DisabledKind derives CanEqual, Schema
        end DisabledKind

        final case class Disabled(via: DisabledKind) extends Reason derives CanEqual, Schema:
            def description = via match
                case DisabledKind.Attribute         => "element is disabled (via `disabled` attribute)"
                case DisabledKind.AriaDisabled      => "element is disabled (via `aria-disabled='true'`)"
                case DisabledKind.FieldsetDisabled  => "element is disabled (via ancestor `<fieldset disabled>`)"
                case DisabledKind.PointerEventsNone => "element is disabled (pointer-events:none)"
                case DisabledKind.Other(raw)        => s"element is disabled (unknown kind: $raw)"
        end Disabled

        final case class Rect(x: Int, y: Int, width: Int, height: Int) derives CanEqual, Schema

        final case class NotInViewport(rect: Rect, viewport: Rect) extends Reason derives CanEqual, Schema:
            def description =
                s"element is not in the viewport (element rect: ${rect.x},${rect.y} ${rect.width}x${rect.height}; viewport: ${viewport.width}x${viewport.height})"

        final case class ZeroSizedElement(width: Int, height: Int) extends Reason derives CanEqual, Schema:
            def description = s"element has zero size (width=$width, height=$height)"

        final case class OutsideHitTarget(actualHit: String) extends Reason derives CanEqual, Schema:
            def description = s"element is covered by another element ($actualHit)"

        final case class NotFillable(tagName: String) extends Reason derives CanEqual, Schema:
            def description = s"element is not a fillable input (expected INPUT, TEXTAREA, or contentEditable; got $tagName)"

        case object Unstable extends Reason:
            val description = "element is still moving -- its bounding rect did not stabilise across two animation frames"

        case object FillDesync extends Reason:
            val description = "the target's value did not reflect the intended text after typing -- framework likely rejected it"

    end Reason

end BrowserElementNotActionableException

// --- Navigation failures ---

/** Navigation to a URL failed (network error, navigation aborted, protocol error).
  *
  * @see
  *   [[BrowserNavigationException]] for the topical marker.
  */
final case class BrowserNavigationFailedException(url: String, error: String)(using Frame)
    extends BrowserException(s"Navigation failed: $url -- $error")
    with BrowserNavigationException with BrowserReadException derives CanEqual

/** `back` was called when the tab is already at the earliest history entry (no prior entry exists).
  *
  * Raised by [[Browser.back]] when `history.currentIndex == 0`. Callers that want browser-style no-op semantics at the boundary can recover
  * with `Abort.recover { case _: BrowserAlreadyAtHistoryStartException => () }`.
  *
  * @see
  *   [[BrowserNavigationException]] for the topical marker.
  * @see
  *   [[BrowserAlreadyAtHistoryEndException]] for the symmetric forward-boundary case.
  */
final case class BrowserAlreadyAtHistoryStartException(
    message: String = "back called when no prior history entry exists"
)(using Frame)
    extends BrowserException(message)
    with BrowserNavigationException with BrowserReadException derives CanEqual

/** `forward` was called when the tab is already at the latest history entry (no later entry exists).
  *
  * Raised by [[Browser.forward]] when `history.currentIndex == history.entries.size - 1`. Callers that want browser-style no-op semantics
  * at the boundary can recover with `Abort.recover { case _: BrowserAlreadyAtHistoryEndException => () }`.
  *
  * @see
  *   [[BrowserNavigationException]] for the topical marker.
  * @see
  *   [[BrowserAlreadyAtHistoryStartException]] for the symmetric back-boundary case.
  */
final case class BrowserAlreadyAtHistoryEndException(
    message: String = "forward called when no later history entry exists"
)(using Frame)
    extends BrowserException(message)
    with BrowserNavigationException with BrowserReadException derives CanEqual

// --- Script failures ---

/** Evaluation of user-supplied JavaScript raised an exception in the page context.
  *
  * @see
  *   [[BrowserScriptException]] for the topical marker.
  */
final case class BrowserScriptErrorException(error: String)(using Frame)
    extends BrowserException(s"Script evaluation failed: $error")
    with BrowserScriptException with BrowserReadException derives CanEqual

/** Evaluation returned valid JSON but decoding into the requested type failed.
  *
  * Raised by [[Browser.evalJson]] when the script executes without error but the returned JSON cannot be decoded into the requested type
  * `A`. Disjoint from [[BrowserScriptException]] so callers can differentiate a type-shape mismatch from a runtime script failure.
  */
final case class BrowserDecodingException(method: String, error: String)(using Frame)
    extends BrowserException(s"$method: JSON decode failed: $error")
    with BrowserReadException derives CanEqual

/** Raised when a public Browser API receives an invalid argument before any CDP call is issued.
  *
  * Examples: a non-absolute `toPath` passed to [[Browser.setDownloadBehavior]] or [[Browser.setFiles]].
  *
  * Extends [[BrowserReadException]] so callers using `Abort[BrowserReadException]` (the catch-all row) catch it without a separate handler.
  * The dedicated type lets test code distinguish "I called the API wrong" from a runtime navigation or element failure.
  */
final case class BrowserInvalidArgumentException(method: String, message: String)(using Frame)
    extends BrowserException(s"$method: $message")
    with BrowserReadException derives CanEqual

/** A capture operation exceeded its configured unit limit.
  *
  * `operation` names the public method ("screenshotFullPage", "screenshotMarks", "screenshotFrames").
  * `limit` and `reached` always share one unit. For band and mark caps `limit` is the cap (`maxBands`,
  * `maxMarks`) and `reached` is the count the page demanded, raised BEFORE any capture (the call is rejected
  * without partial work). For `screenshotFrames` the cap is checked frame-count-first: the frame cap reports
  * `limit = maxFrames` against the frame count reached, and the duration cap reports `limit = maxDurationMs`
  * against the elapsed milliseconds reached, so a caller can interpret the pair without knowing which cap fired.
  * Pre-CDP argument errors (negative coords, non-positive size) use [[BrowserInvalidArgumentException]] instead,
  * not this leaf.
  */
final case class BrowserCaptureLimitExceededException(operation: String, limit: Int, reached: Int)(using Frame)
    extends BrowserException(s"Capture limit exceeded for $operation: limit $limit, reached $reached")
    with BrowserReadException derives CanEqual

// --- Assertion failures ---

/** A waited-for condition did not become true before the deadline.
  *
  * @see
  *   [[BrowserAssertionException]] for the topical marker.
  * @see
  *   [[BrowserAssertionTimedOutException.notQuiesced]] for the DOM-quiescence variant.
  */
final case class BrowserAssertionTimedOutException(check: String, expected: String, actual: String)(using Frame)
    extends BrowserException(s"Assertion failed: $check -- expected $expected, got $actual")
    with BrowserAssertionException derives CanEqual

/** Companion with smart constructors for [[BrowserAssertionTimedOutException]]. */
object BrowserAssertionTimedOutException:
    /** Builds an instance whose `check` is auto-derived from the enclosing method via the implicit [[Frame]]. Used by the `assert*` /
      * `waitFor*` family where the check name matches the public method's name.
      */
    def apply(expected: String, actual: String)(using frame: Frame): BrowserAssertionTimedOutException =
        BrowserAssertionTimedOutException(frame.calleeName, expected, actual)

    /** Builds an instance describing a DOM-quiescence wait that timed out. */
    def notQuiesced(quiescenceWindow: Duration, deltaMs: Long, count: Long, deadline: Duration)(using
        Frame
    ): BrowserAssertionTimedOutException =
        BrowserAssertionTimedOutException(
            "DOM quiescence",
            s"no mutations for $quiescenceWindow",
            s"last mutation ${deltaMs}ms ago, observed $count mutations (deadline $deadline exhausted; " +
                s"raise via Browser.withConfig(_.mutationSettlementTimeout(<larger>)) if the page legitimately takes longer)"
        )
end BrowserAssertionTimedOutException

// --- Frame failures ---

/** A frame handle could not be resolved or has become invalid mid-scope. The `reason` field distinguishes the precise sub-case so callers
  * can pattern-match on the typed [[BrowserIFrameInvalidException.Reason]] instead of inspecting message text.
  *
  * @see
  *   [[BrowserIFrameException]] for the topical marker.
  * @see
  *   [[BrowserIFrameInvalidException.Reason]] for the enumerated sub-cases.
  */
final case class BrowserIFrameInvalidException(reason: BrowserIFrameInvalidException.Reason)(using Frame)
    extends BrowserException(s"Frame invalid: ${reason.describe}")
    with BrowserIFrameException with BrowserReadException derives CanEqual

object BrowserIFrameInvalidException:

    /** Enumerates the sub-cases distinguished by [[BrowserIFrameInvalidException]].
      *
      *   - [[Reason.NotAFrame]]: the selector matched an element, but it is not an `<iframe>` / `<frame>` / browsable `<object>`.
      *   - [[Reason.ContextNotObserved]]: the frame element is in the DOM but its document has not produced a default execution context yet
      *     (e.g. mid-load).
      *   - [[Reason.ContextDestroyed]]: a previously-observed execution context has been destroyed (frame detached, parent navigated, or
      *     the iframe element was removed from the DOM).
      *   - [[Reason.RootNotSeeded]]: the tab's root frame id has not yet been seeded by `attachTab`'s initial `Page.getFrameTree`.
      */
    enum Reason derives CanEqual:
        case NotAFrame
        case ContextNotObserved
        case ContextDestroyed
        case RootNotSeeded

        /** Human-readable description of the reason. */
        def describe: String = this match
            case NotAFrame          => "not an iframe"
            case ContextNotObserved => "execution context not observed"
            case ContextDestroyed   => "execution context destroyed"
            case RootNotSeeded      => "root frame id not yet seeded"
    end Reason

end BrowserIFrameInvalidException
