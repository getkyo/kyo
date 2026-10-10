import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

// An application whose tests drive Aeron and whose production code does not depend on it. The test binary links its
// own classpath, so the library the test-only dependency delivers has to reach the test link and be staged beside the
// test binary, and must stay out of the production link, where nothing would call it.
lazy val root = (project in file("."))
    .enablePlugins(ScalaNativePlugin, KyoNativesPlugin)
    .settings(
        scalaVersion := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo"     %%% "kyo-aeron" % sys.props("kyo.version") % Test,
        libraryDependencies += "org.scalameta" %%% "munit"     % "1.2.1"                  % Test,
        // The artifact has to carry the library for this target, so a hole in the release fails the build here.
        kyoNativesSource := NativesSource.Jar,
        nativeConfig ~= (_.withBaseName("consumer")),
        // Both links' configs as the linker reads them, rather than the tasks feeding them, so a test-only library that
        // never reached the test link's config fails here.
        TaskKey[Unit]("writeLinkConfigs") := {
            def render(config: scala.scalanative.build.NativeConfig): String =
                (config.compileOptions ++ config.linkingOptions).mkString("", "\n", "\n")
            IO.write(baseDirectory.value / "production-config.txt", render(nativeConfig.value))
            IO.write(baseDirectory.value / "test-config.txt", render((Test / nativeLink / nativeConfig).value))
        },
        TaskKey[Unit]("writeTestBinary") := IO.write(baseDirectory.value / "test-binary.txt", (Test / nativeLink).value.getAbsolutePath)
    )
