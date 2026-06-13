ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        // #37: declare libraries in REVERSE topological order; expect the plugin
        // to topo-sort and compile `a` before `b` (since `b.c` includes `a.h`).
        ffiLibraries := Seq(
            FfiLibrary(
                id        = "b",
                cSources  = Seq(baseDirectory.value / "src" / "main" / "c" / "b" / "b.c"),
                cHeaders  = Seq(baseDirectory.value / "src" / "main" / "c" / "b" / "b.h"),
                dependsOn = Seq("a")
            ),
            FfiLibrary(
                id       = "a",
                cSources = Seq(baseDirectory.value / "src" / "main" / "c" / "a" / "a.c"),
                cHeaders = Seq(baseDirectory.value / "src" / "main" / "c" / "a" / "a.h")
            )
        ),
        // Make `a`'s header available to b.c via -I.
        ffiIncludes := Seq(baseDirectory.value / "src" / "main" / "c" / "a"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / fork := true,

        // Topological-order assertion: dump the cc command list and verify the
        // first entry produces lib-a, second produces lib-b.
        TaskKey[Unit]("assertCcOrder") := {
            val cmds = ffiDumpCcCommand.value
            if (cmds.size != 2) sys.error(s"expected 2 cc invocations, got ${cmds.size}: $cmds")
            val first  = cmds(0).mkString(" ")
            val second = cmds(1).mkString(" ")
            // The plugin embeds the library id in the output filename: `lib<id>-<os>-<arch>.<ext>`.
            if (!first.contains("liba-"))
                sys.error(s"expected first cc invocation to build library 'a' (output liba-...), got: $first")
            if (!second.contains("libb-"))
                sys.error(s"expected second cc invocation to build library 'b' (output libb-...), got: $second")
            streams.value.log.info(s"[multi-library-deps] cc order OK: a before b")
        }
    )
