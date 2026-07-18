package demo

import kyo.*
import kyo.Style.*

/** Loading a real asset is a typed, Scope-managed effect, and the camera controls that let you inspect it
  * are an ordinary scene node, not page-level JavaScript bolted on afterward.
  *
  * `Three.loadGltf` returns `Asset.Gltf < (Async & Scope & Abort[ThreeException])`: the load is
  * asynchronous (it awaits the network), Scope-managed (the GPU buffers it allocates release when the
  * mount's Scope closes, load or no load), and its failure is a typed `Abort` leaf, not an exception that
  * can escape past the caller. `Three.controls` is not a side channel into the renderer; it is a value in
  * the scene tree exactly like a `Three.mesh`, so the client binds one live `OrbitControls` per mount and
  * disposes it with everything else when the scope closes.
  *
  * Because that failure is a value, the page RENDERS it: [[ui]] keeps `Abort[ThreeException]` in its row,
  * so whoever mounts this scene cannot compile until it says what a failed load looks like, and [[failed]]
  * is the answer. A model that does not load puts the typed error in the very `#panel-status` line that
  * reports a successful one. That is what a typed failure buys you, and it is why the failure is not merely
  * logged: a console message leaves a viewer staring at a black canvas with nothing on screen to explain
  * it.
  *
  * A reasonable guess is that turning the model with the mouse is the SAME rotation mechanism the earlier
  * demos use for a spinning cube, an `onFrame` handler advancing an angle signal. It is not: this model
  * carries no `onFrame` at all, so between drags it holds perfectly still. Every bit of motion you see
  * comes from `OrbitControls` moving the CAMERA around a stationary model, proven by the "toggle
  * auto-rotate" button, which does not touch the model either: it flips a `SignalRef[Boolean]` bound to the
  * controls node's reactive `autoRotate` prop (`Three.controls(...).autoRotate(signal)`), so each toggle
  * sets `autoRotate` on the one live `OrbitControls` without rebuilding the scene, the same `Bound.Ref`
  * grain a mesh's `.color(signal)` already uses.
  *
  * Read next: `FlagshipScene` (every idea in this reading order, on one cube, plus the live mount handle).
  */
object GltfInspectorScene:

    private val text: Color   = Color.rgb(221, 227, 234)
    private val raised: Color = Color.rgb(27, 31, 39)

    private val pageStyle: Style =
        Style.column.gap(12.px).padding(16.px).fontFamily(FontFamily.SansSerif).color(text)

    private val panelStyle: Style =
        Style.row.gap(20.px).flexWrap(FlexWrap.wrap).align(Alignment.center)
            .padding(12.px, 16.px).bg(raised).rounded(8.px)

    private val headingStyle: Style = Style.fontSize(20.px).fontWeight(FontWeight.bold).color(text)

    private val statusStyle: Style = Style.fontSize(15.px).color(text)

    private val buttonStyle: Style =
        Style.padding(8.px, 14.px).fontSize(15.px).rounded(6.px)
            .border(1.px, Color.rgb(58, 68, 83)).bg(Color.rgb(35, 42, 53)).color(text)
            .hover(_.bg(Color.rgb(46, 55, 69)))

    /** Loads the glTF at `url`, and returns the scene alongside a status ref describing what loaded and the
      * auto-rotate toggle ref the info panel's button flips.
      *
      * The URL is a parameter, never a constant here: the app that SERVES the model is the app that names
      * it, so the route a server answers and the URL a page asks for are one value in one place and cannot
      * drift apart.
      */
    def scene(url: String)(using
        Frame
    ): (Three.Ast.Scene, SignalRef[String], SignalRef[Boolean]) < (Async & Scope & Abort[ThreeException]) =
        for
            asset      <- Three.loadGltf(url)
            status     <- Signal.initRef(describe(asset))
            autoRotate <- Signal.initRef(false)
            root = asset.root
                .onPointerOver(_ => Log.info("pointer over model"))
                .onClick(_ => Log.info("clicked model"))
            controls = Three.controls(enableZoom = true, enableRotate = true).autoRotate(autoRotate)
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 0.6),
                Three.Light.directional(intensity = 1.2, position = Three.Vec3(5, 5, 5)),
                root,
                controls
            ),
            status,
            autoRotate
        )

    /** A short, honest description of what loaded: the clip and named-node counts the asset actually
      * carries, never a claim the loader cannot back up.
      */
    private def describe(asset: Asset.Gltf): String =
        s"Loaded: ${asset.nodes.size} named node(s), ${asset.animations.size} animation clip(s)."

    /** What a failed load says on the page, read off the typed error's own fields.
      *
      * `ThreeException` is sealed, so this is total: every way the load can fail has a sentence a viewer
      * can act on. `AssetLoadFailed` names the URL, which is the actionable fact when a page asks for a
      * model its server does not serve.
      */
    private def describe(failure: ThreeException): String =
        failure match
            case ThreeException.AssetLoadFailed(url, _)  => s"Load failed: $url could not be fetched or parsed."
            case ThreeException.CanvasNotFound(selector) => s"Load failed: no canvas matched $selector."
            case ThreeException.WebGLUnavailable(detail) => s"Load failed: WebGL is unavailable ($detail)."
            case ThreeException.RenderFailure(detail, _) => s"Load failed while rendering: $detail."

    /** The viewing camera, framing the loaded model centred at the origin; `Three.controls` orbits it.
      *
      * A three-quarter view, not a head-on one: face-on, a boxy model presents a single flat face and reads
      * as a 2D square, which tells a viewer nothing about the geometry that actually loaded.
      */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Three.Vec3(3.0, 2.2, 3.5),
            lookAt = Three.Vec3.zero
        )

    /** The page: the embedded model beside a small info panel showing what loaded, with a button toggling
      * the auto-rotate orbit.
      *
      * The load's failure stays in the row, which is what forces the page to deal with it: the entry that
      * mounts this cannot compile until it decides what a failed load looks like, and [[failed]] is that
      * decision.
      */
    def ui(url: String)(using Frame): UI < (Async & Scope & Abort[ThreeException]) =
        scene(url).map { case (built, status, autoRotate) =>
            val panel = UI.div(
                UI.h2("glTF inspector").id("panel-title").style(headingStyle),
                UI.p(status).id("panel-status").style(statusStyle),
                UI.button("Toggle auto-rotate").id("toggle-auto-rotate")
                    .onClick(autoRotate.updateAndGet(!_).unit).style(buttonStyle)
            ).id("panel").style(panelStyle)
            UI.div(
                panel,
                Three.embed(built, camera).id("stage")
            ).id("gltf-inspector-demo").style(pageStyle)
        }

    /** The page a failed load renders: the same panel and the same `#panel-status` line a successful load
      * describes itself in, carrying the typed failure instead of the description.
      *
      * It has no canvas, because there is no model. The message IS the page. A load that only logged its
      * error would leave a viewer staring at a black canvas with nothing on screen to explain it, which is
      * the one thing a typed failure exists to prevent.
      */
    def failed(failure: ThreeException)(using Frame): UI =
        val panel = UI.div(
            UI.h2("glTF inspector").id("panel-title").style(headingStyle),
            UI.p(describe(failure)).id("panel-status").style(statusStyle)
        ).id("panel").style(panelStyle)
        UI.div(panel).id("gltf-inspector-demo").style(pageStyle)
    end failed

end GltfInspectorScene
