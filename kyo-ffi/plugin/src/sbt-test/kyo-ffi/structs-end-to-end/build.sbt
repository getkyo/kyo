ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// Struct end-to-end: exercises nested struct params, packed struct params,
// and return-position structs with String + Buffer fields.
lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "se_lib",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "se_lib.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,
        run / javaOptions  ++= Seq("--enable-native-access=ALL-UNNAMED"),
        run / fork := true
    )
