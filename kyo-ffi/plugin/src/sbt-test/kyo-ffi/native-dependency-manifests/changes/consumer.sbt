import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")

lazy val consumer = (project in file("consumer"))
    .enablePlugins(KyoFfiPlugin, ScalaNativePlugin)
    .settings(
        libraryDependencies += "kyo.ffi.scripted" %%% "producer" % "0.1.0-SNAPSHOT",
        // The wiring kyo-net/README.md "Scala Native builds" asks a consumer for.
        nativeConfig := {
            val base = nativeConfig.value
            base
                .withLinkingOptions(base.linkingOptions ++ ffiNativeDependencyLinkingOptions.value)
                .withCompileOptions(base.compileOptions ++ ffiNativeDependencyCompileOptions.value)
        },
        TaskKey[Unit]("checkRun") := {
            val bin = (Compile / nativeLink).value
            val out = scala.sys.process.Process(bin.getAbsolutePath).!!
            streams.value.log.info(s"[native-dependency-manifests] ${bin.getName}: ${out.trim}")
            if (!out.contains("prodm_hypot=5.0")) sys.error(s"expected prodm_hypot=5.0 from ${bin.getAbsolutePath}, got: $out")
        },
        TaskKey[Unit]("checkDependencyLinkFlags") := {
            val flags = ffiNativeDependencyLinkingOptions.value
            streams.value.log.info(s"[native-dependency-manifests] ffiNativeDependencyLinkingOptions = ${flags.mkString("[", ", ", "]")}")
            if (!flags.contains("-lm"))
                sys.error(
                    s"expected the producer's -lm among ffiNativeDependencyLinkingOptions, read off its published jar; got ${flags.mkString("[", ", ", "]")}"
                )
        },
        // The plugin wires the same flags into the link itself, so with the explicit form above they reach it twice; they must land once.
        checkLinkedOnce := linkedOnce((Compile / nativeLink / nativeConfig).value.linkingOptions, streams.value.log)
    )

lazy val checkLinkedOnce = taskKey[Unit]("The producer's -lm reaches the link exactly once.")

def linkedOnce(linkingOptions: Seq[String], log: Logger): Unit = {
    log.info(s"[native-dependency-manifests] nativeLink linkingOptions = ${linkingOptions.mkString("[", ", ", "]")}")
    val count = linkingOptions.count(_ == "-lm")
    if (count != 1) sys.error(s"expected the producer's -lm once in the link, found it $count times: $linkingOptions")
}

// No nativeConfig wiring at all: the plugin folds the producer's flags into the link on its own.
lazy val autoConsumer = (project in file("auto-consumer"))
    .enablePlugins(KyoFfiPlugin, ScalaNativePlugin)
    .settings(
        libraryDependencies += "kyo.ffi.scripted" %%% "producer" % "0.1.0-SNAPSHOT",
        Compile / scalaSource := (ThisBuild / baseDirectory).value / "consumer" / "src" / "main" / "scala",
        checkLinkedOnce := linkedOnce((Compile / nativeLink / nativeConfig).value.linkingOptions, streams.value.log),
        TaskKey[Unit]("checkRun") := {
            val bin = (Compile / nativeLink).value
            val out = scala.sys.process.Process(bin.getAbsolutePath).!!
            if (!out.contains("prodm_hypot=5.0")) sys.error(s"expected prodm_hypot=5.0 from ${bin.getAbsolutePath}, got: $out")
        }
    )
