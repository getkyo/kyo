ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "cf_lib",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "cf.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,

        // Reproduce-first guard for the codegen-version cache-key gap (#255). ffiGenerate keys its
        // FileFunction.cached config sentinel on platform/library/flags/system-libs/include-dirs but,
        // before the fix, NOT on the bundled codegen version. So a plugin/codegen change with unchanged
        // binding sources is a cache hit and reuses the previously generated *Impl.scala (the new
        // codegen never runs until a `clean`). The fix folds a codegen fingerprint into the config
        // sentinel, so this asserts config.hash carries a `codegen=<sha256>` segment. It FAILS before
        // the fix (no such segment) and PASSES after.
        TaskKey[Unit]("assertCodegenFingerprint") := {
            val generated = ffiGenerate.value
            if (generated.isEmpty) sys.error("ffiGenerate produced no sources; cannot inspect config.hash")
            val sentinels =
                (target.value ** "config.hash").get.filter(_.getParentFile.getName == "kyo-ffi-generate")
            if (sentinels.isEmpty) sys.error("no kyo-ffi-generate/config.hash sentinel found under target/")
            sentinels.foreach { f =>
                val content = IO.read(f)
                if (!content.matches(""".*\|codegen=[0-9a-f]{64}.*"""))
                    sys.error(s"config.hash omits the codegen fingerprint (cache-key gap #255): [$content]")
            }
            streams.value.log.info(
                s"[codegen-fp] config.hash carries a codegen fingerprint in ${sentinels.size} sentinel(s)."
            )
        }
    )
