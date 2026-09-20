// The jvm/net fixture's round trip without the two classifier lines that fixture spells out: kyo-natives-plugin puts
// the host's native transport and BoringSSL jars on the runtime classpath. Both are forced, so a native that is
// missing or does not load fails the round trip rather than degrading to NIO and JDK TLS.
val kyoVersion = sys.props("kyo.version")
val hostOsArch = sys.props("kyo.hostOsArch")

lazy val root = (project in file("."))
    .enablePlugins(KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %% "kyo-net" % kyoVersion,
        // This application publishes too, which is where delivering natives through `libraryDependencies` would do
        // damage: the classifier would reach its POM and pin this build host's architecture onto everyone who then
        // depends on it. The test asserts the POM is clean.
        organization := "com.example",
        version      := "0.1.0",
        fork         := true,
        javaOptions ++= Seq(
            "--enable-native-access=ALL-UNNAMED",
            "-Dkyo.net.tls=boringssl",
            "-Dkyo.net.backend=" + (if (hostOsArch.startsWith("darwin")) "kqueue" else "epoll")
        )
    )
