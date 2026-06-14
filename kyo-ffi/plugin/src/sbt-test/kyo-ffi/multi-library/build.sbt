ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        // Multi-library mode: two independent shared libs in one project.
        ffiLibraries := Seq(
            FfiLibrary("alpha", Seq(baseDirectory.value / "src" / "main" / "c" / "alpha" / "alpha.c")),
            FfiLibrary("beta",  Seq(baseDirectory.value / "src" / "main" / "c" / "beta"  / "beta.c"))
        ),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / fork := true
    )
