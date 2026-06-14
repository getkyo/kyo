ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "inc_rename_lib",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "trivial.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,

        // After renaming the binding trait AlphaBindings to BetaBindings, ffiGenerate must (a) regenerate
        // from fresh TASTy so the old trait is no longer "discovered", and (b) remove the now-stale
        // AlphaBindingsImpl.scala (#34). If the old impl survived it would reference a trait that no longer
        // exists and fail compilation; if a stale class-dir TASTy were read, AlphaBindings would still be
        // discovered and its impl kept. This asserts the old impl is gone and the new one is present.
        TaskKey[Unit]("assertRenamed") := {
            val gen   = (Compile / sourceManaged).value / "kyo-ffi"
            val alpha = (gen ** "AlphaBindingsImpl.scala").get
            val beta  = (gen ** "BetaBindingsImpl.scala").get
            if (alpha.nonEmpty)
                sys.error(s"stale impl survived rename: ${alpha.map(_.getAbsolutePath).mkString(", ")}")
            if (beta.isEmpty)
                sys.error(s"renamed impl not generated: no BetaBindingsImpl.scala under $gen")
            streams.value.log.info("[inc-rename] AlphaBindingsImpl removed, BetaBindingsImpl generated.")
        }
    )
