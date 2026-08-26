// ScalaNativePlugin is taken transitively from sbt-kyo-test, never declared here, so a pin that
// drifts behind the version kyo builds its artifacts with fails this sub-build instead of hiding.
//
// This is also the only sub-build that proves the platform-suffixed artifact actually reaches the
// linked binary: the JVM jar compiles fine against a Native project but contributes no NIR, so a
// wrong cross-version links clean and runs nothing.
lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaNativePlugin, SbtKyoTestPlugin)
  .settings(
    scalaVersion := sys.props("kyo.scalaVersion")
  )
