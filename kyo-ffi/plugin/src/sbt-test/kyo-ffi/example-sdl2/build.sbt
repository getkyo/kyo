ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// SDL2 worked example, event-loop style callbacks against a stub C ABI.
//
// Production bindings against real SDL2 would:
//   * drop `ffiCSources` (the stub)
//   * set `ffiLinkLibs := Seq("SDL2")`
//   * keep the Scala trait identical
//
// The stub implements a tiny event pump + window handle so the transient
// callback path (poll-style) is fully exercised without requiring SDL2 on
// the build host.
lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "kyo_sdl2_stub",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "kyo_sdl2_stub.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,
        run / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        run / fork := true
    )
