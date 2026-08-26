// ScalaJSPlugin is taken transitively from sbt-kyo-test, never declared here. That is what makes
// this sub-build fail if kyo-test-sbt's sbt-scalajs pin drifts behind the version kyo builds its
// artifacts with: a sub-build carrying its own pin would mask the mismatch.
lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaJSPlugin, SbtKyoTestPlugin)
  .settings(
    scalaVersion := sys.props("kyo.scalaVersion"),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
  )
