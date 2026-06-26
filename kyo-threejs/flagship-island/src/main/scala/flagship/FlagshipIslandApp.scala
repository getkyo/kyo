package flagship

import demoharness.DemoMounts

/** The flagship per-app island: the self-running
  * client bundle the [[democlient.Flagship]] launcher links into its page through `head.moduleScript`.
  *
  * The `kyo-threejs-flagship-island` project links this object as its main module initializer, so loading
  * the bundled ESModule on the page runs [[main]] without a separate bootstrap. The body mounts the
  * Flagship scene at the page's `#app` canvas via [[DemoMounts.mountFlagship]], which runs the real
  * `Three.runMount` GL pipeline (the cube spins via client `onFrame`, the camera orbits via the bound
  * `OrbitControls`) AND connects BOTH server-fed mirrors (the auto-cycled color and the click-driven
  * scale), so the four behaviors run together on one cube. The bundle inlines three AND
  * OrbitControls (esbuild), so the page needs no import map and no separately-served three.
  */
object FlagshipIslandApp:

    def main(args: Array[String]): Unit =
        DemoMounts.mountFlagship("#app")

end FlagshipIslandApp
