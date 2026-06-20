package controlsisland

import demoharness.DemoMounts

/** The Option-Y ORBIT-CONTROLS per-app island (design 02-design-r2, Decision D-001, DY-06): the
  * self-running client bundle the controls browser test links through `head.moduleScript`.
  *
  * The `kyo-threejs-controls-island` project links this object as its main module initializer, so loading
  * the bundled ESModule on the page runs [[main]] without a separate bootstrap. The body mounts the
  * Controls scene at the page's `#app` canvas via [[DemoMounts.mountControls]], which runs the real
  * `Three.runMount` GL pipeline over a STATIC object plus a `Three.controls(autoRotate = true)` node, so
  * the island binds a live `OrbitControls` and the camera orbits the static scene each frame. The bundle
  * inlines three AND OrbitControls (esbuild), so the page needs no import map.
  */
object ControlsIslandApp:

    def main(args: Array[String]): Unit =
        DemoMounts.mountControls("#app")

end ControlsIslandApp
