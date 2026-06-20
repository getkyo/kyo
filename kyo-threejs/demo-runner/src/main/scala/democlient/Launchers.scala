package democlient

import kyo.*

/** Client-mount launchers for the five pure-animation demos. Each is a `KyoApp` that serves the
  * demo's client-mount page (via [[DemoClientServe]]) on a local port and awaits; opening the printed
  * URL runs the demo IN THE BROWSER through `Three.runMount`, so the scene animates locally.
  *
  * Each takes an optional port as the first argument (default ephemeral). One `KyoApp` per demo: no
  * shared dispatcher. The `demoClientX` command aliases in build.sbt launch each one.
  */

/** Base for the client-mount launchers: parses the optional port from the first argument (default 0,
  * an ephemeral port) so each demo can be pinned to a fixed URL or left to pick a free port.
  */
trait ClientDemoApp extends KyoApp:
    /** The port to bind: the first argument parsed as an int, else 0 (ephemeral). */
    protected def port: Int =
        args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
end ClientDemoApp

/** Serves [[demo.BouncingBallsScene]] as a client mount: 24 spheres fall under gravity and bounce. */
object BouncingBalls extends ClientDemoApp:
    run {
        DemoClientServe.serve(
            "BouncingBalls",
            "24 cyan spheres fall and bounce off the floor",
            "mountBouncingBalls",
            """mountBouncingBalls("#app")""",
            port
        )
    }
end BouncingBalls

/** Serves [[demo.ReactiveCubeFieldScene]] as a client mount: a wave ripples across an 8x8 cube grid. */
object ReactiveCubeField extends ClientDemoApp:
    run {
        DemoClientServe.serve(
            "ReactiveCubeField",
            "a color-and-height wave ripples across the 8x8 cube grid",
            "mountReactiveCubeField",
            """mountReactiveCubeField("#app")""",
            port
        )
    }
end ReactiveCubeField

/** Serves [[demo.Snake3DScene]] as a client mount: the snake steps across the grid each Clock tick. */
object Snake3D extends ClientDemoApp:
    run {
        DemoClientServe.serve(
            "Snake3D",
            "the green snake steps across the grid toward the red food",
            "mountSnake3D",
            """mountSnake3D("#app")""",
            port
        )
    }
end Snake3D

/** Serves [[demo.SolarSystemScene]] as a client mount: the earth orbits the sun. */
object SolarSystem extends ClientDemoApp:
    run {
        DemoClientServe.serve(
            "SolarSystem",
            "the blue earth orbits the yellow sun",
            "mountSolarSystem",
            """mountSolarSystem("#app")""",
            port
        )
    }
end SolarSystem

/** Serves [[demo.GltfViewerScene]] as a client mount: the loaded glTF model spins. */
object GltfViewer extends ClientDemoApp:
    run {
        DemoClientServe.serve(
            "GltfViewer",
            "the loaded glTF cube rotates",
            "mountGltfViewer",
            s"""mountGltfViewer("#app", "${DemoClientServe.modelPath}")""",
            port
        )
    }
end GltfViewer
