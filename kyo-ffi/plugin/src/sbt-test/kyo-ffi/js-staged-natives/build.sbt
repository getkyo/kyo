import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import org.scalajs.linker.interface.ModuleKind
import org.scalajs.linker.interface.OutputPatterns

ThisBuild / scalaVersion := sys.props("kyo.scalaVersion")
ThisBuild / version      := "0.1.0-SNAPSHOT"

// A library that binds its own C, packaged as a jar the way a published Scala.js artifact is: the plugin files the native under
// kyo-ffi/native/<os>-<arch>/ in it.
lazy val lib = project
    .in(file("lib"))
    .enablePlugins(KyoFfiPlugin, ScalaJSPlugin)
    .settings(
        ffiLibraryId := "sn_lib",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "sn_lib.c"),
        libraryDependencies += "io.getkyo" %%% "kyo-ffi" % sys.props("kyo.version"),
        exportJars := true
    )

// One application per module kind. Neither binds C of its own; each wraps its link with ffiWithJsNatives, which copies the natives the
// classpath carries, the library's jar included, beside the linked output.
def application(id: String, kind: ModuleKind): Project =
    Project(id, file(id))
        .enablePlugins(ScalaJSPlugin)
        .dependsOn(lib)
        .settings(
            Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "app" / "src" / "main" / "scala",
            scalaJSLinkerConfig ~= { config =>
                val kinded = config.withModuleKind(kind)
                if (kind == ModuleKind.ESModule) kinded.withOutputPatterns(OutputPatterns.fromJSFile("%s.mjs")) else kinded
            },
            scalaJSUseMainModuleInitializer := true,
            Compile / mainClass             := Some("sn.Main"),
            Compile / fastLinkJS := ffiWithJsNatives(Compile / fastLinkJS, Compile / fastLinkJS / scalaJSLinkerOutputDirectory, Runtime).value
        )

lazy val esm = application("esm", ModuleKind.ESModule)
lazy val cjs = application("cjs", ModuleKind.CommonJSModule)
