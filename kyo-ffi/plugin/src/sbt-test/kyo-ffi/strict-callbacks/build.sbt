ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// With ffiStrictCallbacks := true, a method whose resolved C symbol is on the
// retention allowlist (here: `signal`) but which has no Ffi.Guard parameter
// must fail the build at ffiGenerate time.
lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId       := "scbk_lib",
        ffiCSources        := Seq(baseDirectory.value / "src" / "main" / "c" / "scbk_lib.c"),
        ffiStrictCallbacks := true,
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version")
    )
