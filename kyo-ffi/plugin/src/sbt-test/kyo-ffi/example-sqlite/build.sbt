ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// SQLite worked example, open/exec/close against a stub C ABI. The canonical
// Panama benchmark target. Exercises the transient-callback pattern: the
// `exec` function invokes a row-callback synchronously per result row.
//
// Production bindings would:
//   * drop `ffiCSources`
//   * set `ffiLinkLibs := Seq("sqlite3")`
//   * keep the Scala trait identical
lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "kyo_sqlite_stub",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "kyo_sqlite_stub.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,
        run / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        run / fork := true
    )
