ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "autodiscover",
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,
        run / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        run / fork := true
    )
