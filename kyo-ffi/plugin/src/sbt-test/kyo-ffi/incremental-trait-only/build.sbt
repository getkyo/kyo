ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "inc_trait_lib",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "trivial.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,

        // Asserts that the second ffiGenerate invocation produces IDENTICAL file paths
        // with unchanged mtimes (cache hit) when neither trait sources nor config changed.
        TaskKey[Unit]("snapshotGenerateMtime") := {
            val out = ffiGenerate.value.map(f => s"${f.getName}:${f.lastModified()}").sorted.mkString("\n")
            IO.write(target.value / "mtime-generate.txt", out)
            streams.value.log.info(s"[inc-trait] snapshot -> $out")
        },
        TaskKey[Unit]("assertGenerateUnchanged") := {
            val prior = IO.read(target.value / "mtime-generate.txt")
            val now   = ffiGenerate.value.map(f => s"${f.getName}:${f.lastModified()}").sorted.mkString("\n")
            if (now != prior) sys.error(s"expected ffiGenerate to be cached.\nprior=$prior\nnow  =$now")
            streams.value.log.info("[inc-trait] ffiGenerate was cached (artifacts untouched).")
        }
    )
