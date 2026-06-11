ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "stale_lib",
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / fork := true,
        // After deleting the only binding source, ffiGenerate has no sources to compile, so it
        // removes the orphaned generated impl (#34) rather than leaving zinc to compile an impl
        // for a trait that no longer exists.
        TaskKey[Unit]("assertImplGone") := {
            val out  = (Compile / sourceManaged).value / "kyo-ffi"
            val impl = out / "stale" / "StaleBindingsImpl.scala"
            if (impl.exists()) sys.error(s"expected $impl to have been removed; still present.")
            streams.value.log.info("[stale] StaleBindingsImpl.scala correctly removed.")
        },
        TaskKey[Unit]("assertImplPresent") := {
            val out  = (Compile / sourceManaged).value / "kyo-ffi"
            val impl = out / "stale" / "StaleBindingsImpl.scala"
            if (!impl.exists()) sys.error(s"expected $impl to be present after compile; missing.")
            streams.value.log.info(s"[stale] $impl present.")
        }
    )
