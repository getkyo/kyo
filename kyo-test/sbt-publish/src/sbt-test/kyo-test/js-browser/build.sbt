// Runs the tests in Chrome the way a user's build does: the published plugin resolves kyo-test-browser and
// kyoTestBrowserEnv starts it. A page loads ES modules and classic scripts, so the link is an ES module here and the
// `test` script relinks it as a classic script.
lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaJSPlugin, SbtKyoTestPlugin)
  .settings(
    scalaVersion := sys.props("kyo.scalaVersion"),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    Test / jsEnv := kyoTestBrowserEnv.value
  )
