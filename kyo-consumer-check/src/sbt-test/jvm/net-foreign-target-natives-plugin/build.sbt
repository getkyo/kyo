// A JVM image built on this host for this host and one other pole, the way an image built on a laptop is deployed to
// a server. The JVM loader picks the pole at run time from the classpath, so both poles' classifier jars have to be on
// the runtime classpath the image is assembled from. The other pole is one every pooled publish carries, so this needs
// a publish with every pole's natives, as release.yml and release-probe.yml do; a host-only publish has none to give.
val hostOsArch = sys.props("kyo.hostOsArch")
val foreign    = if (hostOsArch == "linux-x86_64") "darwin-aarch64" else "linux-x86_64"

lazy val root = (project in file("."))
    .enablePlugins(KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %% "kyo-net" % sys.props("kyo.version"),
        kyoNativesTargets := Seq(hostOsArch, foreign),
        // Both poles have to be there, so a pole the release does not carry fails here rather than in production.
        kyoNativesSource := NativesSource.Jar,
        TaskKey[Unit]("writeRuntimeClasspath") := {
            IO.write(baseDirectory.value / "poles.txt", Seq(hostOsArch, foreign).mkString("", "\n", "\n"))
            IO.write(
                baseDirectory.value / "runtime-classpath.txt",
                (Runtime / fullClasspath).value.map(_.data.getName).sorted.mkString("", "\n", "\n")
            )
        }
    )
