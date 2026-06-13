ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId   := "extract_test",
        ffiExtractDir  := Some(baseDirectory.value / ".tmp" / "custom"),
        ffiScratchSize := 128 * 1024,
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version")
    )

// Write the resolved Test/javaOptions into a file so scripted can grep them.
lazy val dumpJavaOptions = taskKey[Unit]("Write Test / javaOptions to target/test-java-options.txt")
dumpJavaOptions := {
    val opts = (Test / javaOptions).value
    IO.createDirectory(target.value)
    IO.write(target.value / "test-java-options.txt", opts.mkString("\n"))
    streams.value.log.info("[test] Test/javaOptions = " + opts.mkString(" "))
}
