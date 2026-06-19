package demo

import kyo.*

/** Server-driven 3D prop reactivity: a server-owned `SignalRef[Int]` tick that a SERVER-SIDE
  * background fiber advances on a fixed cadence, never a client `onFrame`. The cube's color and
  * rotation bind to the tick, so each server advance flows over kyo-ui's WebSocket and patches the
  * client-rendered 3D prop. A kyo-ui HUD echoes the same tick, so the server state and the on-canvas
  * 3D state are visibly correlated, and a Pause button toggles a server-owned `paused` signal that
  * idles the engine.
  *
  * This is the server-push counterpart to [[ReactiveCubeField]]: there a client `onFrame` advances
  * the phase; here the server owns the clock and pushes every change, exercising the server to client
  * prop path end to end.
  */
object ServerClock extends KyoApp:
    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            ui       <- ServerClockScene.ui
            handlers <- UI.runHandlers("/", DemoServe.head)(ui)
            server   <- HttpServer.init(port, "localhost")((handlers :+ DemoServe.islandHandler)*)
            _        <- Console.printLine(s"ServerClock running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end ServerClock

/** The scene-graph and kyo-ui builder for [[ServerClock]], holding the server-owned tick engine so
  * the `KyoApp` and any harness mount the same composition.
  */
object ServerClockScene:

    /** The viewing camera, framing the single driven cube head-on. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(position = Vec3(0, 0, 5), lookAt = Vec3.zero)

    /** Composes the kyo-ui tree: the embedded cube whose color and rotation bind to a server-owned
      * tick, a background fiber advancing that tick on a fixed cadence, a HUD echoing the tick and
      * the engine state, and a Pause button. The tree carries `< Async` because the engine fiber is
      * forked with `Fiber.initUnscoped`.
      */
    def ui(using Frame): UI < Async =
        for
            tick   <- Signal.initRef(0)
            paused <- Signal.initRef(false)
            // Server-side engine: advance the tick on a fixed cadence (no client onFrame). The loop
            // state is the next tick value; while paused it idles without touching the signal.
            _ <- Fiber.initUnscoped {
                Loop(0) { n =>
                    paused.get.map { isPaused =>
                        if isPaused then Async.sleep(150.millis).andThen(Loop.continue(n))
                        else tick.set(n).andThen(Async.sleep(500.millis)).andThen(Loop.continue(n + 1))
                    }
                }
            }
            cube = Three.mesh(
                Three.Geometry.box(1.6, 1.6, 1.6),
                Three.Material.standard().color(tick.map(t => Color.hsl(((t * 25) % 360).toDouble, 0.65, 0.5)))
            ).rotation(tick.map(t => Vec3(0.0, t * 0.4, 0.0)))
            scene = Three.scene(
                Three.Light.ambient(intensity = 0.4),
                Three.Light.directional(position = Vec3(5, 10, 5)),
                cube
            )
            embed = Three.embed(scene, camera).id("stage")
            hud = UI.div(
                UI.p(tick.map(t => s"server tick: $t")).id("tick-label"),
                UI.p(paused.map(p => if p then "paused" else "running")).id("state-label"),
                UI.button("Pause / Resume").id("pause-btn").onClick(paused.updateAndGet(!_))
            )
        yield UI.div(embed, hud)
        end for
    end ui
end ServerClockScene
