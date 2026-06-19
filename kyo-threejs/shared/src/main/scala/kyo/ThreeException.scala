package kyo

/** The typed error root of the module (the `UIException` analog), a small exception hierarchy under
  * [[KyoException]].
  *
  * Each leaf models a genuinely distinct failure mode with typed fields, not a discriminator string:
  * [[ThreeException.CanvasNotFound]] (a user error: the selector matched no canvas),
  * [[ThreeException.WebGLUnavailable]] (an environment fact: no GL context),
  * [[ThreeException.RenderFailure]] (a runtime failure: a frame threw), and
  * [[ThreeException.AssetLoadFailed]] (an IO failure: a load failed). Every error pathway in
  * `runMount` / `loadGltf` / `Three.toImage` surfaces one of these as a typed `Abort` leaf, never a
  * silent drop or a thrown exception.
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
end ThreeException
