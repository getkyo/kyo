ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "inc_change_lib",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "trivial.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,

        // Asserts the generated impl implements every method of the CURRENT trait. After an incremental
        // trait edit that adds a method, ffiGenerate must regenerate from the new trait (fresh TASTy), not
        // the stale class-dir TASTy of the previous compile. Reads the generated *Impl.scala and checks the
        // method name is present, so it fails clearly even if a stale impl somehow still compiled.
        TaskKey[Unit]("assertImplHasIncTwo") := {
            val gen = (Compile / sourceManaged).value / "kyo-ffi"
            val impl = (gen ** "TrivialBindingsImpl.scala").get.headOption
                .getOrElse(sys.error(s"no TrivialBindingsImpl.scala generated under $gen"))
            val body = IO.read(impl)
            if (!body.contains("incChange2"))
                sys.error(s"generated impl is STALE: missing incChange2.\n--- $impl ---\n$body")
            streams.value.log.info("[inc-change] generated impl includes incChange2 (regenerated from the new trait).")
        }
    )
