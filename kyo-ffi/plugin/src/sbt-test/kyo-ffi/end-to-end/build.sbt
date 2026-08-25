ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "e2e_lib",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "e2e_lib.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,
        run / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        run / fork := true,
        Test / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Test / fork := true,
        // Regression guard for the GraalVM native-image reachability-metadata generation (emitter + plugin wiring):
        // force managed resources and assert the per-module manifest is produced with the expected downcalls,
        // reflection entry, and foreign tokens for this binding (2 Int methods -> jint, 1 Long method -> jlong).
        TaskKey[Unit]("checkReachabilityMetadata") := {
            val _ = (Compile / managedResources).value
            val f =
                (Compile / resourceManaged).value / "META-INF" / "native-image" / "io.getkyo" / name.value / "reachability-metadata.json"
            if (!f.exists) sys.error(s"[reachability] expected generated manifest at $f")
            val json         = IO.read(f)
            def need(s: String): Unit = if (!json.contains(s)) sys.error(s"[reachability] manifest missing '$s':\n$json")
            need("\"reflection\"")
            need("\"foreign\"")
            need("\"downcalls\"")
            need("e2e.E2eBindingsImpl")
            need("\"jint\"")
            need("\"jlong\"")
            need("\"captureCallState\": true")
            val downcalls = json.split("\"returnType\"").length - 1
            if (downcalls != 3)
                sys.error(s"[reachability] expected 3 downcalls (E2eBindings has 3 methods), got $downcalls:\n$json")
            streams.value.log.info(s"[reachability] OK: $downcalls downcalls, impl + tokens present in $f")
        }
    )
