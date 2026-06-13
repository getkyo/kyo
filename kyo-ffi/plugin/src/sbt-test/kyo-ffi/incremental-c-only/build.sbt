import java.nio.file.Files

ThisBuild / scalaVersion := "3.8.3"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "inc_c_lib",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "lib.c"),

        // Marker files record when each task's work body actually ran. A task-body
        // wrapper around ffiCompile / ffiGenerate would be cleaner, but since the
        // body is fixed inside the plugin we instead probe the produced artifacts'
        // mtimes: a cache hit leaves the artifact untouched.
        TaskKey[Unit]("snapshotCompileMtime") := {
            val artifacts = ffiCompile.value
            val out       = target.value / "mtime-compile.txt"
            val value     = artifacts.map(f => s"${f.getName}:${f.lastModified()}").mkString("\n")
            IO.write(out, value)
            streams.value.log.info(s"[inc-c] snapshot -> $value")
        },
        TaskKey[Unit]("assertCompileUnchanged") := {
            val artifacts = ffiCompile.value
            val prior     = IO.read(target.value / "mtime-compile.txt")
            val now       = artifacts.map(f => s"${f.getName}:${f.lastModified()}").mkString("\n")
            if (now != prior) sys.error(s"expected ffiCompile to be cached (no rebuild).\nprior=$prior\nnow  =$now")
            streams.value.log.info("[inc-c] ffiCompile was cached (artifacts untouched).")
        },
        TaskKey[Unit]("assertCompileChanged") := {
            val artifacts = ffiCompile.value
            val prior     = IO.read(target.value / "mtime-compile.txt")
            val now       = artifacts.map(f => s"${f.getName}:${f.lastModified()}").mkString("\n")
            if (now == prior) sys.error(s"expected ffiCompile to have rebuilt after C source edit, but outputs are identical.\nprior=$prior")
            streams.value.log.info("[inc-c] ffiCompile rebuilt (artifacts refreshed).")
        }
    )
