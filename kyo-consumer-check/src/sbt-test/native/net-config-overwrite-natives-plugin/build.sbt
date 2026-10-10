import scala.scalanative.build.Discover
import scala.scalanative.build.NativeConfig
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// kyo-natives-plugin enabled, and a `nativeConfig :=` that builds a config from scratch rather than on the existing
// value. A project's settings apply after an auto-plugin's, so this replaces everything the plugin folded in.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin, KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-net" % sys.props("kyo.version"),
        nativeConfig := NativeConfig.empty
            .withClang(Discover.clang())
            .withClangPP(Discover.clangpp())
            .withCompileOptions(Discover.compileOptions())
            .withLinkingOptions(Discover.linkingOptions())
            .withBaseName("consumer"),
        // Runs one link task and passes only when the plugin's fold check refused it, by its message. A `->` step
        // accepts any failure, including one from a check that has nothing to do with the overwrite.
        commands += Command.single("expectFoldRefusal") { (state, task) =>
            val key = task match {
                case "nativeLink"            => Compile / nativeLink
                case "nativeLinkReleaseFast" => Compile / nativeLinkReleaseFast
                case "nativeLinkReleaseFull" => Compile / nativeLinkReleaseFull
                case other                   => sys.error(s"no link task named $other")
            }
            Project.runTask(key, state) match {
                case Some((next, Inc(incomplete))) =>
                    val messages = Incomplete.allExceptions(incomplete).map(e => String.valueOf(e.getMessage)).toSeq
                    if (messages.exists(_.contains("which kyo-natives-plugin added"))) next
                    else sys.error(s"$task failed, but not by the fold check: ${messages.mkString("; ")}")
                case Some((_, Value(_))) => sys.error(s"$task linked a binary whose nativeConfig lost the plugin's fold")
                case None                => sys.error(s"$task is not defined")
            }
        }
    )
