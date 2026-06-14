ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "e2e_lib",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "e2e_lib.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,
        run / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        run / fork := true,
        Test / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Test / fork := true
    )
