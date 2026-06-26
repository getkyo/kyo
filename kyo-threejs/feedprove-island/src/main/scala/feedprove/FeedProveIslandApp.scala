package feedprove

import demoharness.DemoMounts

/** The FeedProve per-app island: the self-running
  * client bundle the [[democlient.FeedProve]] launcher links into its page through `head.moduleScript`.
  *
  * The `kyo-threejs-feedprove-island` project links this object as its main module initializer, so
  * loading the bundled ESModule on the page runs [[main]] without a separate bootstrap. The body mounts
  * the FeedProve scene at the page's `#app` canvas via [[DemoMounts.mountFeedProve]], which runs the real
  * `Three.runMount` GL pipeline (the cube spins via client `onFrame`) AND calls `Three.Feed.connect` for
  * the color mirror, so an inbound `HostUpdate(SignalUpdate(colorId, encoded))` the server feeds over the
  * WebSocket steps the cube's color. The bundle inlines three (esbuild), so the page needs no import map
  * and no separately-served three: it links this one self-contained ESM and nothing else, exactly as the
  * server-push demos link the generic island.
  *
  * This is the per-app entry the locked `Three.Feed.run` page links: the server half (the launcher) runs
  * only the `ui` builder to learn the fed ids and fork the feed observers; this client half owns, builds,
  * and animates the real scene.
  */
object FeedProveIslandApp:

    def main(args: Array[String]): Unit =
        DemoMounts.mountFeedProve("#app")

end FeedProveIslandApp
