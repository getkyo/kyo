import scala.scalanative.sbtplugin.ScalaNativePlugin

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")
ThisBuild / version      := "0.1.0-SNAPSHOT"

// The published jar is what a consumer on any OS reads, and the release publishes Native from one Linux host, so the jar has to answer
// for every OS a consumer can be on, whatever OS packaged it.
lazy val perOs = (project in file("."))
    .enablePlugins(KyoFfiPlugin, ScalaNativePlugin)
    .settings(
        name := "per-os",
        ffiLibraries := Seq(
            FfiLibrary(
                id = "per_os",
                cSources = Seq(baseDirectory.value / "src" / "main" / "c" / "per_os.c"),
                linkLibs = Seq("m"),
                linkLibsByOs = Map("linux" -> Seq("dl"))
            )
        ),
        TaskKey[Unit]("checkPackagedManifests") := {
            val jar    = (Compile / packageBin).value
            val prefix = "META-INF/kyo-ffi/native-link-flags/"
            val zip    = new java.util.zip.ZipFile(jar)
            val actual =
                try {
                    import scala.collection.JavaConverters._
                    zip.entries.asScala.filter(e => !e.isDirectory && e.getName.startsWith(prefix)).map { e =>
                        val text = scala.io.Source.fromInputStream(zip.getInputStream(e), "UTF-8").mkString
                        e.getName.stripPrefix(prefix) -> text.linesIterator.map(_.trim).filter(_.nonEmpty).toList
                    }.toMap
                } finally zip.close()
            val expected = Map(
                "per-os-linux.flags"      -> List("-lm", "-ldl"),
                "per-os-linux-musl.flags" -> List("-lm", "-ldl"),
                "per-os-darwin.flags"     -> List("-lm")
            )
            streams.value.log.info(s"[native-manifests-per-os] ${jar.getName} carries $actual")
            val wrong = expected.filter { case (file, flags) => !actual.get(file).contains(flags) }
            if (wrong.nonEmpty)
                sys.error(
                    s"packaged link-flag manifests do not answer for every OS: expected ${wrong.mkString(", ")}; the jar carries $actual"
                )
        }
    )
