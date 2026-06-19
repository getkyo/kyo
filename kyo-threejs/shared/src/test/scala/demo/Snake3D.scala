package demo

import kyo.*
import kyo.Three.foreachKeyed
import kyo.Three.render

/** A playable 3D Snake on a grid: a `SignalRef` holds the game state, a `Clock`-clocked
  * `onFrame` hook steps the snake each tick, and `foreachKeyed` reactive reconciliation renders
  * one cube per body segment keyed by segment id so GPU buffers survive each step.
  *
  * Demonstrates reactive game state, keyed reconciliation (no full-scene rebuilds on tick), and
  * the `ThreeFrames.Clock` fixed-interval source. The step ticker is a `Group` with an `onFrame` hook
  * added as a scene child; `Group` implements `Animated` so the ticker carries no geometry. The
  * head wraps at the grid edges so the snake stays inside the playfield.
  */
object Snake3D extends KyoApp:
    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            scene <- Snake3DScene.scene
            ui = UI.div(Three.embed(scene, Snake3DScene.camera, Snake3DScene.frames).id("app"))
            handlers <- UI.runHandlers("/", DemoServe.head)(ui)
            server   <- HttpServer.init(port, "localhost")((handlers :+ DemoServe.islandHandler)*)
            _        <- Console.printLine(s"Snake3D running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end Snake3D

/** The scene-graph builder for [[Snake3D]], shared by the `KyoApp` and the visual-review harness so
  * both mount the same compiled scene.
  */
object Snake3DScene:

    final case class Segment(id: Int, pos: Vec3) derives CanEqual
    final case class Game(body: Chunk[Segment], dir: Vec3, food: Vec3, nextId: Int) derives CanEqual

    /** A grid-walking snake: a `SignalRef[Game]` holds state, `foreachKeyed` reconciles one cube per
      * segment by id, and a `Group.onFrame` steps the game each tick.
      */
    def scene(using Frame): Three.Ast.Scene < Sync =
        val initialBody = Chunk(
            Segment(0, Vec3(0, 0, 0)),
            Segment(1, Vec3(-1, 0, 0)),
            Segment(2, Vec3(-2, 0, 0)),
            Segment(3, Vec3(-3, 0, 0))
        )
        for
            game <- Signal.initRef(Game(initialBody, Vec3.unitX, Vec3(3, 0, -2), 4))
            cubes = game.map(_.body).foreachKeyed(_.id.toString) { seg =>
                Three.mesh(
                    Three.Geometry.box(0.9, 0.9, 0.9),
                    Three.Material.standard(
                        color = Color.green,
                        emissive = Color(0x114411)
                    )
                ).position(seg.pos)
            }
            food = game.render { g =>
                Three.mesh(
                    Three.Geometry.sphere(0.4),
                    Three.Material.standard(
                        color = Color.red,
                        emissive = Color(0x440000)
                    )
                ).position(g.food)
            }
            ticker = Three.group().onFrame(_ => game.updateAndGet(step))
        yield Three.scene(
            Three.Light.ambient(intensity = 0.6),
            Three.Light.directional(intensity = 1.2, position = Vec3(4, 8, 6)),
            cubes,
            food,
            ticker
        )
        end for
    end scene

    /** The angled camera framing the snake on the playfield, looking down at the grid centre. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            fov = Radians.deg(70),
            position = Vec3(0, 5, 8),
            lookAt = Vec3(0, 0, 0)
        )

    /** The fixed-interval step source driving the snake. */
    val frames: ThreeFrames = ThreeFrames.Clock(150.millis)

    /** Half-extent of the square playfield: cells run from `-gridHalf` to `+gridHalf` on each axis. */
    private val gridHalf = 5

    private def step(g: Game): Game =
        val head    = g.body.headMaybe.getOrElse(Segment(0, Vec3.zero))
        val newHead = Segment(g.nextId, wrap(head.pos + g.dir))
        Game(newHead +: g.body.dropRight(1), g.dir, g.food, g.nextId + 1)
    end step

    /** Wraps a position so the snake re-enters from the opposite edge when it leaves the grid. */
    private def wrap(pos: Vec3): Vec3 =
        def fold(v: Double): Double =
            if v > gridHalf then -gridHalf.toDouble
            else if v < -gridHalf then gridHalf.toDouble
            else v
        Vec3(fold(pos.x), fold(pos.y), fold(pos.z))
    end wrap
end Snake3DScene
