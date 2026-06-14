ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// With ffiStrictBlocking := true, a method whose resolved C symbol is on the
// blocking allowlist (here: `read`) but which lacks @Ffi.blocking must fail the
// build at ffiGenerate time.
lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId      := "sblk_lib",
        ffiCSources       := Seq(baseDirectory.value / "src" / "main" / "c" / "sblk_lib.c"),
        ffiStrictBlocking := true,
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version")
    )
