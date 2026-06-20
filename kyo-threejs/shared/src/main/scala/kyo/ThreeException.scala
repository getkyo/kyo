package kyo

/** The typed error root of the module (the `UIException` analog), a small exception hierarchy under
  * [[KyoException]].
  *
  * Each leaf models a genuinely distinct failure mode with typed fields, not a discriminator string:
  * [[ThreeException.CanvasNotFound]] (a user error: the selector matched no canvas),
  * [[ThreeException.WebGLUnavailable]] (an environment fact: no GL context),
  * [[ThreeException.RenderFailure]] (a runtime failure: a frame threw),
  * [[ThreeException.AssetLoadFailed]] (an IO failure: a load failed), and
  * [[ThreeException.FeedUnavailable]] (a back-channel fact: `Three.Feed.emit` ran with no feed channel
  * bound). Every error pathway in `runMount` / `loadGltf` / `Three.toImage` / `Three.Feed.emit` surfaces
  * one of these as a typed `Abort` leaf, never a silent drop or a thrown exception.
  */
sealed abstract class ThreeException(message: => String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

object ThreeException:
    /** The mount selector matched no `<canvas>` element. */
    final case class CanvasNotFound(selector: String)(using Frame)
        extends ThreeException(s"No canvas matched: $selector")

    /** No WebGL context was available (e.g. headless without the right launch flags). */
    final case class WebGLUnavailable(detail: String)(using Frame)
        extends ThreeException(s"WebGL unavailable: $detail")

    /** A frame failed to render. */
    final case class RenderFailure(detail: String, cause: Throwable)(using Frame)
        extends ThreeException(detail, cause)

    /** An asset failed to load (network or parse failure). */
    final case class AssetLoadFailed(url: String, cause: Throwable)(using Frame)
        extends ThreeException(s"Failed to load: $url", cause)

    /** `Three.Feed.emit` was called with no feed channel bound (outside an island feed context): the
      * client app-event back-channel (`window.__kyoPostAppEvent`) is not installed, so the typed event for
      * `id` has nowhere to go. The typed `Abort` leaf keeps the back-channel total: a pre-connect or
      * out-of-context `emit` surfaces this rather than silently dropping the event.
      */
    final case class FeedUnavailable(id: String)(using Frame)
        extends ThreeException(s"No feed channel bound for emit id: $id")
end ThreeException
