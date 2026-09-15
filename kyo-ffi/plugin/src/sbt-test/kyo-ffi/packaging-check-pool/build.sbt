ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        // `publish / skip` is how the command decides a project requires no platform, so the
        // completeness half stays quiet here and the format half is what this fixture reads. That
        // half is the one a pool feeds: `pooled` is declared with no sources, the shape a publish
        // host uses for a library whose natives every producer supplies.
        publish / skip := true,
        ffiLibraries := Seq(
            FfiLibrary("local", Seq(baseDirectory.value / "src" / "main" / "c" / "local.c")),
            FfiLibrary("pooled", Nil)
        ),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version")
    )
