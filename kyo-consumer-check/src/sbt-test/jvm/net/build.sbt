// A JVM application that depends on kyo-net plus the two per-platform jars kyo-net's README tells a JVM build to add
// for the native transport and BoringSSL. Both are forced: a forced backend or TLS provider that is unavailable fails
// closed rather than falling back to NIO and the JDK, so the round trip succeeds only if the jars' natives load.
val kyoVersion = sys.props("kyo.version")
val hostOsArch = sys.props("kyo.hostOsArch")

lazy val root = (project in file("."))
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies ++= Seq(
            "io.getkyo" %% "kyo-net" % kyoVersion,
            "io.getkyo" %% "kyo-net" % kyoVersion classifier hostOsArch,
            "io.getkyo" %% "kyo-net" % kyoVersion classifier s"$hostOsArch-boringssl"
        ),
        fork := true,
        javaOptions ++= Seq(
            "--enable-native-access=ALL-UNNAMED",
            "-Dkyo.net.tls=boringssl",
            "-Dkyo.net.backend=" + (if (hostOsArch.startsWith("darwin")) "kqueue" else "epoll")
        )
    )
