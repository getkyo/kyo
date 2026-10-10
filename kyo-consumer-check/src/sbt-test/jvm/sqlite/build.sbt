// A JVM application that depends on kyo-sql-sqlite and nothing else: the engine's natives ship inside the artifact.
lazy val root = (project in file("."))
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %% "kyo-sql-sqlite" % sys.props("kyo.version"),
        fork := true,
        javaOptions += "--enable-native-access=ALL-UNNAMED"
    )
