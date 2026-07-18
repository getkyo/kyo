package demo

import kyo.*
import kyo.Style.*
import kyo.UI.render

/** The signal flows both ways: a click on a 3D object selects it, and a kyo-ui panel edits the selected
  * object's own props.
  *
  * Two meshes, a cube and a sphere, each own their own `SignalRef[String]` (a hex colour) and
  * `SignalRef[Double]` (a scale). Clicking either one writes its identity into `selected`. The panel is a
  * single reactive region: `selected.render` swaps in whichever object's controls match the current
  * selection. Editing a control writes straight into that object's own ref, the SAME ref its mesh already
  * binds `.color`/`.scale` to, so there is no synchronising step between "the panel changed" and "the scene
  * changed": they are the same write.
  *
  * The natural guess is that switching the panel re-mounts the 3D canvas, the way switching a tab usually
  * throws its content away and rebuilds it. It does not. `Three.embed` is built ONCE, outside the reactive
  * panel region entirely; only the small panel `div` swaps its own content on selection. Nor do the two
  * objects share one pair of refs: the cube's colour and the sphere's colour are two independent
  * `SignalRef`s, so editing one never touches the other, and clicking back to a previously-edited object
  * shows the value exactly as you left it.
  *
  * Read next: `PlayableSnakeScene` (input driving state directly, rather than editing a prop).
  */
object SceneEditorScene:

    // Dark, because the canvas beside this panel clears to near-black: a light page would read as a hole
    // punched in a white sheet.
    private val text: Color   = Color.rgb(221, 227, 234)
    private val muted: Color  = Color.rgb(147, 161, 177)
    private val raised: Color = Color.rgb(27, 31, 39)

    private val pageStyle: Style =
        Style.column.gap(12.px).padding(16.px).fontFamily(FontFamily.SansSerif).color(text)

    private val panelStyle: Style =
        Style.row.gap(20.px).flexWrap(FlexWrap.wrap).align(Alignment.center)
            .padding(12.px, 16.px).bg(raised).rounded(8.px)

    private val buttonStyle: Style =
        Style.padding(8.px, 14.px).fontSize(15.px).rounded(6.px)
            .border(1.px, Color.rgb(58, 68, 83)).bg(Color.rgb(35, 42, 53)).color(text)
            .hover(_.bg(Color.rgb(46, 55, 69)))

    private val headingStyle: Style    = Style.fontSize(20.px).fontWeight(FontWeight.bold).color(text)
    private val fieldStyle: Style      = Style.column.gap(4.px)
    private val fieldLabelStyle: Style = Style.fontSize(13.px).color(muted)
    private val readoutStyle: Style    = Style.fontFamily(FontFamily.Monospace).fontSize(13.px).color(text)

    /** The identity of a selectable object: a closed, exhaustive alternative to a stringly-typed name. */
    enum ObjectId derives CanEqual:
        case Cube, Sphere

    /** One selectable object's own editable state: its colour (as the raw hex text a reader typed) and its
      * scale.
      */
    private def defaultColor(id: ObjectId): String = id match
        case ObjectId.Cube   => "#e05252"
        case ObjectId.Sphere => "#4d8fe0"

    private val defaultScale: Double = 1.0

    /** The scene, plus every ref the panel edits: the selection itself, and each object's own colour and
      * scale.
      */
    def scene(using
        Frame
    ): (Three.Ast.Scene, SignalRef[ObjectId], SignalRef[String], SignalRef[Double], SignalRef[String], SignalRef[Double]) < Sync =
        for
            selected    <- Signal.initRef(ObjectId.Cube)
            cubeColor   <- Signal.initRef(defaultColor(ObjectId.Cube))
            cubeScale   <- Signal.initRef(defaultScale)
            sphereColor <- Signal.initRef(defaultColor(ObjectId.Sphere))
            sphereScale <- Signal.initRef(defaultScale)
            cube = Three.mesh(
                Three.Geometry.box(),
                Three.Material.standard(roughness = Three.Normal(0.4)).color(cubeColor.map(hexToColor))
            )
                .position(Three.Vec3(-2, 0, 0))
                .scale(cubeScale.map(s => Three.Vec3(s, s, s)))
                .onClick(_ => selected.set(ObjectId.Cube))
            sphere = Three.mesh(
                Three.Geometry.sphere(0.8),
                Three.Material.standard(roughness = Three.Normal(0.4)).color(sphereColor.map(hexToColor))
            )
                .position(Three.Vec3(2, 0, 0))
                .scale(sphereScale.map(s => Three.Vec3(s, s, s)))
                .onClick(_ => selected.set(ObjectId.Sphere))
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 0.6),
                Three.Light.directional(intensity = 1.2, position = Three.Vec3(4, 8, 6)),
                cube,
                sphere
            ),
            selected,
            cubeColor,
            cubeScale,
            sphereColor,
            sphereScale
        )

    /** The camera, pulled back far enough to frame both objects side by side. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            fov = Three.Radians.deg(55),
            position = Three.Vec3(0, 2, 8),
            lookAt = Three.Vec3.zero
        )

    /** The page: the embedded scene beside a panel that shows whichever object is currently selected. */
    def ui(using Frame): UI < Sync =
        scene.map { case (built, selected, cubeColor, cubeScale, sphereColor, sphereScale) =>
            val panel = selected.render {
                case ObjectId.Cube   => editor(ObjectId.Cube, cubeColor, cubeScale)
                case ObjectId.Sphere => editor(ObjectId.Sphere, sphereColor, sphereScale)
            }
            UI.div(
                UI.div(panel).id("panel").style(panelStyle),
                Three.embed(built, camera).id("stage")
            ).id("scene-editor-demo").style(pageStyle)
        }

    /** The name a reader sees for each [[ObjectId]]. */
    private def nameOf(id: ObjectId): String = id match
        case ObjectId.Cube   => "Cube"
        case ObjectId.Sphere => "Sphere"

    /** The editor panel for one object: a hex-text colour field and a scale slider, both bound directly to
      * the object's own refs, plus a reset button that restores both to their defaults.
      */
    private def editor(id: ObjectId, color: SignalRef[String], scale: SignalRef[Double])(using Frame): UI =
        UI.div(
            UI.h2(s"Editing: ${nameOf(id)}").id("editor-title").style(headingStyle),
            UI.div(
                UI.label("Colour (hex)").style(fieldLabelStyle),
                UI.input.value(color).id("editor-color")
            ).style(fieldStyle),
            UI.div(
                UI.label("Scale").style(fieldLabelStyle),
                UI.rangeInput.min(0.5).max(2.0).step(0.05).value(scale).id("editor-scale"),
                UI.span(scale.map(s => f"$s%.2f")).id("editor-scale-value").style(readoutStyle)
            ).style(fieldStyle),
            UI.button("Reset").id("editor-reset").onClick {
                color.set(defaultColor(id)).andThen(scale.set(defaultScale))
            }.style(buttonStyle)
        ).id("editor-panel").style(panelStyle)

    /** Turns the `#rrggbb` a text field produces into a [[Three.Color]]. Total by construction: the digits
      * are checked before they are parsed, so a half-typed value falls back to white instead of throwing on
      * the render path.
      */
    private def hexToColor(hex: String): Three.Color =
        val digits = (if hex.startsWith("#") then hex.drop(1) else hex).toLowerCase
        val valid  = digits.length == 6 && digits.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))
        if valid then Three.Color(java.lang.Integer.parseInt(digits, 16)) else Three.Color.white
    end hexToColor

end SceneEditorScene
