// A consumer wired exactly as kyo-test/README.md documents the plugin path: enable the plugin and
// declare nothing else. The plugin has to supply the runner artifact for this platform and register
// the framework class.
lazy val root = project
  .in(file("."))
  .enablePlugins(SbtKyoTestPlugin)
  .settings(
    scalaVersion := sys.props("kyo.scalaVersion")
  )
