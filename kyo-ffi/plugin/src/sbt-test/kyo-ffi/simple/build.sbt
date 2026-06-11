lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        scalaVersion := "3.8.3",
        ffiLibraryId := "test_lib",
        ffiIncludes  := Seq(baseDirectory.value / "include")
    )
