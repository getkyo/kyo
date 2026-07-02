package demo

import kyo.*
import kyo.Three.foreachKeyed

/** The shared scene+page builder for the server-push STRUCTURAL bridge browser test
  * (`ThreeStructuralBridgeBrowserTest`): a keyed
  * `foreachKeyed` region of cubes bound to a server `Signal[Chunk[Item]]`. ONE function so the
  * server (`UI.runHandlers`) and the client hydrate island rebuild the IDENTICAL tree from the SAME
  * shared `SignalRef`, so `data-kyo-path` agrees by construction and a later server
  * `.set(...)` re-reconciles the client's live scene through the ordinary `boundProps`/
  * `StructuralRegion` wire path (`ReplaceSubtree`), no bespoke feed mechanism.
  *
  * Each `Item` carries its own `x` position and `hex` color, so distinct keys render as distinct,
  * spatially-separated cubes: a browser test identifies which key is present by WHERE a cube sits,
  * with no extra tagging machinery needed on the live three.js object.
  */
object ServerBridgeStructuralScene:

    /** One keyed cube: `key` drives the foreach diff's identity, `x` and `hex` make each key's
      * rendered cube spatially and visually distinct so a browser test can read back which keys are
      * currently present from the live scene graph alone.
      */
    final case class Item(key: String, x: Double, hex: Int) derives CanEqual, Schema

    /** The initial keyed set both the server (SSR) and the client (hydrate) seed their `SignalRef`
      * with, so the first materialize is identical on both sides before any server-driven
      * splice. Three cubes at distinct, non-adjacent X positions so a later splice/reorder's effect
      * on the live position set is unambiguous.
      */
    def seedItems: Chunk[Item] = Chunk(
        Item("a", -2.0, 0xff0000),
        Item("b", 0.0, 0x00ff00),
        Item("c", 2.0, 0x0000ff)
    )

    /** The viewing camera, framing the whole row of cubes. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(position = Three.Vec3(0, 0, 8), lookAt = Three.Vec3.zero)

    /** The scene: a keyed foreach of small cubes, one per `Item`, positioned along X and colored per
      * `hex`. `foreachKeyed` is the structural region (`Ast.Foreach`, `Bound.Ref`-free): its ENTIRE
      * keyed set is the reactive unit, re-reconciled as one `ReplaceSubtree` snapshot per `items`
      * change, never a per-cube prop patch. `Ast.Foreach.children` is UNCONDITIONALLY `Chunk.empty`
      * (`Three.scala:347`), so a foreach-rendered item's own `Bound.Ref` fields are NEVER reached by
      * `ReactiveUI.normalize`'s `backendChildren`/`boundProps` walk -- there is no `SetProp` wire path
      * for one, by construction; only the whole-snapshot `ReplaceSubtree` reaches foreach content at
      * all. A per-item `Bound.Ref` would therefore only ever be reactive under a CLIENT-LOCAL mount
      * (`Three.embed`/`Three.runMount` with no server bridge), never through this test's SSR+WS path.
      */
    def scene(items: Signal[Chunk[Item]])(using Frame): Three.Ast.Scene =
        Three.scene(
            items.foreachKeyed(_.key) { item =>
                Three.mesh(Three.Geometry.box(0.6, 0.6, 0.6), Three.Material.basic().color(Three.Color(item.hex)))
                    .position(Three.Vec3(item.x, 0, 0))
            }
        )

    /** Builds the page body: the embedded row of cubes (via `scene`), bound to `items`, the ONE
      * server-owned `SignalRef` the test drives directly via `.set(...)`.
      */
    def ui(items: Signal[Chunk[Item]])(using Frame): UI =
        UI.div(Three.embed(scene(items), camera).id("stage"))

end ServerBridgeStructuralScene
