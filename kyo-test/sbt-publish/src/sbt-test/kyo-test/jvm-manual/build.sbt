// The wiring kyo-test/README.md documents for consumers who do not use the plugin, kept executable
// so a rename of the framework class or a change to the artifact coordinate fails here rather than
// silently invalidating the README.
lazy val root = project
  .in(file("."))
  .settings(
    scalaVersion := sys.props("kyo.scalaVersion"),
    libraryDependencies += "io.getkyo" %% "kyo-test-runner" % sys.props("plugin.version") % Test,
    Test / testFrameworks += new TestFramework("kyo.test.runner.SbtFramework")
  )
