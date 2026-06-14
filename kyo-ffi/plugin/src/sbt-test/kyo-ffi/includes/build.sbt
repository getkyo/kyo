ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "incl_test",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "main.c"),
        // ffiCHeaders drives both rebuild-tracking AND -I<dir> derivation (parent dir).
        ffiCHeaders  := Seq(baseDirectory.value / "include" / "foo.h"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / fork := true
    )
