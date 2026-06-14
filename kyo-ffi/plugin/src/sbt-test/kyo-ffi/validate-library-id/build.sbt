ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        // Multi-library mode declaring only "actual"; the binding declares library = "missing".
        ffiLibraries := Seq(
            FfiLibrary("actual", Seq(baseDirectory.value / "src" / "main" / "c" / "actual.c"))
        ),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / fork := true
    )
