import scala.scalanative.sbtplugin.ScalaNativePlugin

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin, ScalaNativePlugin)
    .settings(
        scalaVersion := "3.8.3",
        ffiLibraryId := "test_native_lib",
        TaskKey[Unit]("checkPlatform") := {
            val p = ffiTargetPlatform.value
            if (p != "Native") sys.error(s"expected ffiTargetPlatform=Native, got '$p'")
            streams.value.log.info(s"[platform-native] ffiTargetPlatform = $p")
        },
        TaskKey[Unit]("checkFfiCompileIsNoOp") := {
            val r = ffiCompile.value
            if (r.nonEmpty) sys.error(s"expected ffiCompile to be empty on Native, got ${r.mkString(", ")}")
            streams.value.log.info("[platform-native] ffiCompile returned no artifacts (correct).")
        }
    )
