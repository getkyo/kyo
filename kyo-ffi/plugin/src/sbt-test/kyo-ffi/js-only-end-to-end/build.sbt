import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// JS-only end-to-end: Scala.js project + koffi-loaded native lib.
// The C lib itself is compiled by ffiCompile (host JVM toolchain) and the JS
// generator emits koffi-driven bindings.
lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin, ScalaJSPlugin)
    .settings(
        ffiLibraryId := "je_lib",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "je_lib.c"),
        libraryDependencies += "io.getkyo" %%% "kyo-ffi" % sys.props("kyo.version"),
        scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
        scalaJSUseMainModuleInitializer := true
    )
