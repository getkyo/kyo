lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaNativePlugin, SbtKyoTestPlugin)
  .settings(
    scalaVersion := sys.props("kyo.scalaVersion"),
    // A fixed output directory, so the script names IR files without the Scala version in the path.
    Test / classDirectory := target.value / "test-classes"
  )
