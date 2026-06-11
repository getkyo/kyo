ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "noop_lib",
        ffiStrictDiscovery := true,
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / fork := true
    )
