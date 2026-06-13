import org.scalajs.sbtplugin.ScalaJSPlugin

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin, ScalaJSPlugin)
    .settings(
        scalaVersion := "3.8.3",
        ffiLibraryId := "test_js_lib",
        TaskKey[Unit]("checkPlatform") := {
            val p = ffiTargetPlatform.value
            if (p != "JS") sys.error(s"expected ffiTargetPlatform=JS, got '$p'")
            streams.value.log.info(s"[platform-js] ffiTargetPlatform = $p")
        }
    )
