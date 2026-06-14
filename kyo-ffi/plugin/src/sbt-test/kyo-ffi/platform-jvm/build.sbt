lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        scalaVersion := "3.8.3",
        ffiLibraryId := "test_jvm_lib",
        TaskKey[Unit]("checkPlatform") := {
            val p = ffiTargetPlatform.value
            if (p != "JVM") sys.error(s"expected ffiTargetPlatform=JVM, got '$p'")
            streams.value.log.info(s"[platform-jvm] ffiTargetPlatform = $p")
        }
    )
