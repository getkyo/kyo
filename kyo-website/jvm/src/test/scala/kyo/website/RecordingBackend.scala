package kyo.website

import kyo.*
import kyo.internal.HtmlRenderer

/** Renders a UI to the initial HTML body fragment using the same renderer `DomBackend` calls.
  *
  * Byte-identical to what `runMount` would produce for the initial frame: `DomBackend.mountInto`
  * calls `HtmlRenderer.render(ui, Seq.empty)` at DomBackend.scala:31 and then sets
  * `container.innerHTML`. Calling `HtmlRenderer.render` directly here produces the identical body
  * fragment without a real DOM.
  *
  * Use this in JVM tests instead of the JS-only `UI.runMount`. `HtmlRenderer` is `private[kyo]`;
  * `kyo.website` is a subpackage of `kyo`, so the access is visible.
  */
object RecordingBackend:

    /** Renders `ui` to the initial HTML body fragment.
      *
      * @param ui
      *   The UI tree to render.
      * @return
      *   The HTML string that `DomBackend.mountInto` would set as `container.innerHTML`.
      */
    def render(ui: UI)(using Frame): String < Sync =
        HtmlRenderer.render(ui, Seq.empty)

end RecordingBackend
