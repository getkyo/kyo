package demo

import kyo.*
import kyo.Three.foreachKeyed

/** The same `Signal` a server drives is a 3D prop, so pushing live data over the wire and animating a
  * scene are one mechanism, not two.
  *
  * A `SignalRef[Chunk[Bar]]` mirrors a server-owned metric feed: a bar chart of six columns, each keyed
  * by a stable id, its height and colour a pure function of that bar's current value. A server fiber (see
  * the `ServerViz` launcher) advances the mirror on its own schedule, and each new snapshot rides the
  * page's single WebSocket as ONE structural update (a `ReplaceSubtree` for the foreach's region), not as
  * a per-prop patch. It has to: a foreach renders each child FROM its datum, so those children's props are
  * not reachable by the prop-patch walk at all, and re-rendering the region is the only update path
  * foreach content has.
  *
  * What keeps that from being a full rebuild is the KEY. The client's `foreachKeyed` reconciler diffs the
  * arriving snapshot against the live bars by id: a bar whose value moved is disposed and re-materialized
  * from the new value (both its geometry and its colour are functions of it), while a bar whose value did
  * not move keeps its existing live object untouched, GPU buffers and all. So a reorder or a splice costs
  * nothing, and a tick that moves three of six columns rebuilds three cubes, not the scene.
  *
  * The natural assumption is that a server-pushed chart needs its own client-side machinery: a socket
  * handler, a redraw function, something explicitly "reactive to the network". None of that exists here.
  * `sceneWithMirror` returns an ordinary `SignalRef`, exactly the shape every other demo in this set binds
  * a form control or a click handler to; the only thing that differs about this one is WHO calls `.set` on
  * it, a server-side fiber instead of a page-local event, and the scene code cannot tell the difference.
  *
  * Read next: `GltfInspectorScene` (a different kind of asynchronous state: a one-shot asset load).
  */
object ServerVizScene:

    /** One column of the chart: a stable id (the reconciliation key) and its current value, held in
      * `0.0..1.0`.
      */
    final case class Bar(id: Int, value: Double) derives CanEqual

    /** The number of columns in the chart. */
    val barCount: Int = 6

    /** The mirror's starting snapshot: an uneven profile, so the chart reads as a chart before the first
      * server update arrives. A client-local mount has no server fiber to advance the mirror and shows
      * exactly this snapshot for the page's life, so a flat starting row would render the demo as six
      * identical blocks and teach nothing about what the bars mean.
      */
    val initial: Chunk[Bar] = Chunk(Bar(0, 0.35), Bar(1, 0.7), Bar(2, 0.5), Bar(3, 0.9), Bar(4, 0.25), Bar(5, 0.6))

    /** Builds the scene and returns it alongside the mirror `SignalRef[Chunk[Bar]]` a server fiber drives
      * (or a client-local mount leaves at [[initial]]).
      */
    def sceneWithMirror(using Frame): (Three.Ast.Scene, SignalRef[Chunk[Bar]]) < Sync =
        for
            bars <- Signal.initRef(initial)
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 1.0),
                Three.Light.directional(position = Three.Vec3(4, 6, 8)),
                bars.foreachKeyed(_.id.toString)(column)
            ),
            bars
        )

    /** Renders one bar: a box whose height and colour are pure functions of its current value, positioned
      * so its base sits on the floor and its columns spread left to right by id.
      */
    private def column(b: Bar)(using Frame): Three =
        val height = 0.4 + clamp01(b.value) * 3.0
        Three.mesh(
            Three.Geometry.box(0.8, height, 0.8),
            Three.Material.standard(color = colorFor(b.value), roughness = Three.Normal(0.4))
        ).position(Three.Vec3((b.id - (barCount - 1) / 2.0) * 1.2, height / 2.0, 0.0))
    end column

    /** Clamps a value into `0.0..1.0`, total against a feed that briefly overshoots the expected range. */
    private def clamp01(v: Double): Double = v.max(0.0).min(1.0)

    /** A blue-to-red gradient by value: low values read cool, high values read hot. */
    private def colorFor(value: Double): Three.Color =
        val t = clamp01(value)
        val r = (t * 255).toInt
        val b = ((1.0 - t) * 255).toInt
        Three.Color((r << 16) | (0x40 << 8) | b)
    end colorFor

    /** The viewing camera, pulled back to frame the full row of columns head-on. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Three.Vec3(0, 1.5, 9),
            lookAt = Three.Vec3(0, 0.5, 0)
        )

    /** The page: the chart, embedded.
      *
      * ONE builder, called by both sides of a server-driven page: the server renders it to the HTML it
      * ships, and the browser rebuilds it to hydrate onto that same HTML. Sharing the builder is what makes
      * the two trees agree on every `data-kyo-path`, which is how a server-pushed update finds the node it
      * belongs to. Two hand-written copies of this shape would agree until the day one of them changed.
      */
    def page(scene: Three.Ast.Scene)(using Frame): UI =
        UI.div(Three.embed(scene, camera).id("stage")).id("server-viz-demo")

end ServerVizScene
