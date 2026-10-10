// kyo-net as a test-only dependency. Its classifier jars belong on the test classpath, where the tests load them, and
// must stay off the runtime classpath an application image is built from. The test forces the native transport and
// BoringSSL, so a classifier jar that never reached the test classpath fails its round trip rather than degrading to
// NIO and JDK TLS.
val kyoVersion = sys.props("kyo.version")
val hostOsArch = sys.props("kyo.hostOsArch")

lazy val root = (project in file("."))
    .enablePlugins(KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo"     %% "kyo-net" % kyoVersion % Test,
        libraryDependencies += "org.scalameta" %% "munit"   % "1.2.1"    % Test,
        Test / fork  := true,
        Test / javaOptions ++= Seq(
            "--enable-native-access=ALL-UNNAMED",
            "-Dkyo.net.tls=boringssl",
            "-Dkyo.net.backend=" + (if (hostOsArch.startsWith("darwin")) "kqueue" else "epoll")
        ),
        TaskKey[Unit]("writeClasspaths") := {
            def render(cp: Classpath): String = cp.map(_.data.getName).sorted.mkString("", "\n", "\n")
            IO.write(baseDirectory.value / "runtime-classpath.txt", render((Runtime / fullClasspath).value))
            IO.write(baseDirectory.value / "test-classpath.txt", render((Test / fullClasspath).value))
        }
    )
