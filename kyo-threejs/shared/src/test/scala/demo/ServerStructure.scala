package demo

import kyo.*
import kyo.Three.foreachKeyed

/** Server-driven 3D STRUCTURE reactivity: the set of cubes is a server-owned `SignalRef[Chunk[Int]]`
  * of ids, rendered with `foreachKeyed` so each id owns one persistent live mesh. kyo-ui buttons
  * mutate the server-owned list, and each mutation runs server-side and pushes the new STRUCTURE over
  * kyo-ui's WebSocket:
  *
  *   - Add appends a fresh id, splicing in one new mesh.
  *   - Remove drops the last id, disposing exactly one mesh's GL resources.
  *   - Reverse flips the order; because the cubes are keyed by id, the same live `Object3D`s are
  *     reordered rather than rebuilt, and each cube's x-position (bound to its current index) slides
  *     it to the new slot, so identity-preserving reorder is visible on canvas.
  *
  * A HUD echoes the current id list. This is the structural counterpart to [[ServerClock]]: that
  * pushes prop values, this pushes scene structure.
  */
object ServerStructure extends KyoApp:
    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            ui       <- ServerStructureScene.ui
            handlers <- UI.runHandlers("/", DemoServe.head)(ui)
            server   <- HttpServer.init(port, "localhost")((handlers :+ DemoServe.islandHandler)*)
            _        <- Console.printLine(s"ServerStructure running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end ServerStructure

/** The scene-graph and kyo-ui builder for [[ServerStructure]], holding the server-owned id list and
  * the buttons that mutate it.
  */
object ServerStructureScene:

    /** The viewing camera, pulled back to frame the row of cubes. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(fov = Radians.deg(60), position = Vec3(0, 2, 10), lookAt = Vec3.zero)

    /** A stable per-id hue so a cube keeps its color as it slides between slots, making an
      * identity-preserving reorder legible.
      */
    private def colorFor(id: Int): Color = Color.hsl(((id * 57) % 360).toDouble, 0.65, 0.55)

    /** Composes the kyo-ui tree: the embedded keyed cube row, a HUD echoing the id list, and the
      * Add / Remove / Reverse buttons that mutate the server-owned list. The tree carries `< Sync`
      * because all state is created with `Signal.initRef` and no fiber is forked.
      */
    def ui(using Frame): UI < Sync =
        for
            ids    <- Signal.initRef(Chunk(0, 1, 2))
            nextId <- Signal.initRef(3)
            cubes = ids.foreachKeyed(_.toString) { id =>
                val slot = ids.map(_.indexOf(id))
                Three.mesh(
                    Three.Geometry.box(1.0, 1.0, 1.0),
                    Three.Material.standard(color = colorFor(id))
                ).position(slot.map(i => Vec3((i - 1) * 2.0, 0.0, 0.0)))
            }
            scene = Three.scene(
                Three.Light.ambient(intensity = 0.5),
                Three.Light.directional(position = Vec3(4, 8, 6)),
                cubes
            )
            embed = Three.embed(scene, camera).id("stage")
            controls = UI.div(
                UI.button("Add").id("add-btn").onClick(
                    nextId.get.map(n => ids.updateAndGet(_.append(n)).andThen(nextId.set(n + 1)))
                ),
                UI.button("Remove").id("remove-btn").onClick(
                    ids.updateAndGet(c => if c.isEmpty then c else c.dropRight(1))
                ),
                UI.button("Reverse").id("reverse-btn").onClick(
                    ids.updateAndGet(c => Chunk.from(c.reverse))
                )
            )
            hud = UI.div(UI.p(ids.map(c => s"ids: [${c.mkString(", ")}]")).id("ids-label"))
        yield UI.div(embed, controls, hud)
        end for
    end ui
end ServerStructureScene
