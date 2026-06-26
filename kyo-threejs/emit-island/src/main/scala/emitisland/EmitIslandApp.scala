package emitisland

import demoharness.DemoMounts

/** The app-event per-app island: the self-running
  * client bundle the emit browser test and the launcher link through `head.moduleScript`.
  *
  * The `kyo-threejs-emit-island` project links this object as its main module initializer, so loading the
  * bundled ESModule on the page runs [[main]] without a separate bootstrap. The body mounts the FeedEmit
  * scene at the page's `#app` canvas via [[DemoMounts.mountFeedEmit]], which runs the real `Three.runMount`
  * GL pipeline (a clickable cube whose `onClick` calls `Three.Feed.emit`) AND calls `Three.Feed.connect`
  * for the color mirror, so the server's app-event handler can feed the bumped color back. The bundle
  * inlines three (esbuild), so the page needs no import map.
  */
object EmitIslandApp:

    def main(args: Array[String]): Unit =
        DemoMounts.mountFeedEmit("#app")

end EmitIslandApp
