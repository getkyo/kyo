ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Callback end-to-end: a C function that takes a transient callback (qsort-style)
// and invokes it during the call. Exercises the per-call confined upcall arena.
lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "cbe_lib",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "cbe_lib.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,
        run / javaOptions  ++= Seq("--enable-native-access=ALL-UNNAMED"),
        run / fork := true
    )
