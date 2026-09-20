import org.scalajs.linker.interface.ModuleKind
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

// One enablePlugins on the crossProject, which is what the module READMEs tell an application to write. Each leg
// then picks up its own half of the delivery through the two sub-plugins' allRequirements trigger: the JVM leg puts
// the classifier jar on its classpath, the Native leg links the engine into the binary, and the JS leg writes it
// where koffi resolves it. The three legs share one source file, so what differs between them is the delivery.
lazy val app = crossProject(JVMPlatform, NativePlatform, JSPlatform)
    .crossType(CrossType.Full)
    .in(file("."))
    .enablePlugins(KyoNativesPlugin)
    .settings(
        scalaVersion                      := sys.props("kyo.scalaVersion"),
        libraryDependencies += "io.getkyo" %%% "kyo-sql-doltlite" % sys.props("kyo.version"),
        // The artifact has to carry the engine for this target on every leg that takes one, so a hole in the release
        // fails the build rather than leaving one leg quietly without it.
        kyoNativesSource := NativesSource.Jar
    )
    .jvmSettings(
        fork := true,
        javaOptions += "--enable-native-access=ALL-UNNAMED"
    )
    .nativeSettings(
        nativeConfig ~= (_.withBaseName("consumer"))
    )
    .jsSettings(
        scalaJSUseMainModuleInitializer := true,
        scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
    )

lazy val appJVM    = app.jvm
lazy val appNative = app.native
lazy val appJS     = app.js
