ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Multi-library end-to-end: two independent C libs, two traits, both loaded
// and invoked from the same Main via Ffi.load.
lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraries := Seq(
            FfiLibrary("mle_alpha", Seq(baseDirectory.value / "src" / "main" / "c" / "alpha" / "alpha.c")),
            FfiLibrary("mle_beta",  Seq(baseDirectory.value / "src" / "main" / "c" / "beta"  / "beta.c"))
        ),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        run / javaOptions     ++= Seq("--enable-native-access=ALL-UNNAMED"),
        run / fork := true,
        Compile / fork := true
    )
