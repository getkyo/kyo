package demo

import kyo.*
import kyo.Three.foreachKeyed

/** The structural feed scene: ONE three.js scene
  * whose mesh COUNT and ARRANGEMENT are driven by a server-fed `Chunk[Int]` addressed by
  * [[FeedChunkScene.listId]].
  *
  * The fed value is a list of integer item ids. The scene binds the mirror `SignalRef[Chunk[Int]]` with
  * the EXISTING `.foreachKeyed(id)(render)` extension: each id renders one colored cube laid out left to
  * right by its position in the list. The server feeds the whole list snapshot by id (add an item, remove
  * an item, reorder the items), and the client's OWN keyed reconciler diffs it locally: a surviving id
  * reuses its live cube (GPU buffers survive), a new id materializes a cube, a dropped id disposes one. So
  * the visible mesh count and left-to-right arrangement track the fed list exactly.
  *
  * The field is held static (no `onFrame` spin) so the rendered cube COUNT maps directly to the lit
  * columns a screencast sampler reads: an add lights a new column, a remove darkens one, a reorder
  * recolors the columns. The structural change is the whole proof, read from real pixels.
  */
object FeedChunkScene:

    /** The string signal id the two halves agree on: the server feeds the item-id list addressed by this
      * id, and the island binds a mirror `SignalRef[Chunk[Int]]` under the same id.
      */
    val listId: String = "feed-list"

    /** The initial item-id list the mirror starts with (so the scene renders three cubes before any feed
      * arrives). The server feeds changes from here.
      */
    val initialItems: Chunk[Int] = Chunk(0, 1, 2)

    /** A fixed per-id color palette (packed `0xRRGGBB`), indexed by `id % size`, so each cube's color is a
      * stable function of its id: a reorder keeps each cube's color, making the rearrangement legible.
      */
    private val palette: Seq[Int] = Seq(0xff4040, 0x40ff40, 0x4080ff, 0xffd040, 0xff40ff, 0x40ffff)

    /** Renders one cube for item `id`, placed at slot `index` left to right and colored by `id`. The cube
      * spacing is wide enough that adding, removing, or reordering items visibly changes the arrangement.
      */
    private def cube(index: Int, id: Int)(using Frame): Three =
        val color = Three.Color(palette(((id % palette.size) + palette.size) % palette.size))
        Three.mesh(
            Three.Geometry.box(0.8, 0.8, 0.8),
            Three.Material.standard(color = color, roughness = Three.Normal(0.4))
        ).position(Three.Vec3((index - 2.5) * 1.2, 0.0, 0.0))
    end cube

    /** Builds the scene and returns it alongside the item-list mirror `SignalRef[Chunk[Int]]` the island
      * connects to the structural feed. The field of cubes is a `foreachKeyed` over the mirror (keyed by
      * id), wrapped in a `Group` that spins via `onFrame`. The mirror starts at [[initialItems]] so three
      * cubes render before any feed arrives.
      */
    def sceneWithMirror(using Frame): (Three.Ast.Scene, SignalRef[Chunk[Int]]) < Sync =
        for
            items <- Signal.initRef(initialItems)
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 1.0),
                Three.Light.directional(position = Three.Vec3(4, 6, 8)),
                indexedField(items)
            ),
            items
        )
        end for
    end sceneWithMirror

    /** The keyed field of cubes: one cube per item id, keyed by id (so a reorder reuses the live cube) and
      * positioned by its slot in the list (so the arrangement tracks the order). Uses the indexed
      * `foreach` render seam to read each item's position in the current list.
      */
    private def indexedField(items: SignalRef[Chunk[Int]])(using Frame): Three =
        // Pair each id with its index so the render places it by slot AND keys it by id. The mapped signal
        // carries (index, id) pairs; foreachKeyed keys on the id so a reorder reuses the cube, and the
        // render reads the index for the left-to-right position.
        items.map(list => list.zipWithIndex)
            .foreachKeyed((pair: (Int, Int)) => pair._1.toString) { pair =>
                val (id, index) = pair
                cube(index, id)
            }

    /** The viewing camera, pulled back to frame the full row of up to six cubes head-on (so each cube
      * occupies its own horizontal band a column sampler reads).
      */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Three.Vec3(0, 0, 8),
            lookAt = Three.Vec3(0, 0, 0)
        )

end FeedChunkScene
