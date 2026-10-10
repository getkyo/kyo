// A JVM application that depends on kyo-aeron and nothing else: the Aeron client and driver ship inside the artifact.
lazy val root = (project in file("."))
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %% "kyo-aeron" % sys.props("kyo.version"),
        fork := true,
        javaOptions += "--enable-native-access=ALL-UNNAMED"
    )
