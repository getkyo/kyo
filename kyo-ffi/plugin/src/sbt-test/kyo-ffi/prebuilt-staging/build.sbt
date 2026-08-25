ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        // `local` is compiled on this machine. `foreign` is supplied only by the staged prebuilts, so
        // it is declared with no C sources: the shape a publish host uses for a platform it does not
        // build itself. Declaring it keeps the id valid for bindings and records it in the
        // library-state manifest, instead of it looking like a library whose build failed.
        ffiLibraries := Seq(
            FfiLibrary("local", Seq(baseDirectory.value / "src" / "main" / "c" / "local.c")),
            FfiLibrary("foreign", Nil)
        ),
        ffiPrebuiltDir := Some(baseDirectory.value / "prebuilt"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version")
    )
