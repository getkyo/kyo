lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        ffiLibraryId := "test_lib",
        ffiIncludes  := Seq(baseDirectory.value / "include")
    )
