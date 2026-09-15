ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val checkBuildLevelDefaults = taskKey[Unit]("Fail unless every build-wide FFI key resolves at ThisBuild.")
lazy val checkCFlags = taskKey[Unit]("Fail unless the ThisBuild ffiCFlags append reached this project intact.")
lazy val checkTargetOsArch = taskKey[Unit]("Fail unless the ThisBuild ffiTargetOsArch value reached this project.")
lazy val writeArtifactName = taskKey[Unit]("Write the artifact name ffiCompile is expected to produce.")

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "blo_test",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "blo.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        checkBuildLevelDefaults := {
            // A key whose default moves back to projectSettings stops resolving at ThisBuild, which is
            // what discards a `ThisBuild / key := ...` and what leaves a `ThisBuild / key ++= ...` with
            // no base value to append to.
            val resolves = Seq(
                "ffiCCompiler"         -> (ThisBuild / ffiCCompiler).?.value.isDefined,
                "ffiCFlags"            -> (ThisBuild / ffiCFlags).?.value.isDefined,
                "ffiLinkFlags"         -> (ThisBuild / ffiLinkFlags).?.value.isDefined,
                "ffiStaticLink"        -> (ThisBuild / ffiStaticLink).?.value.isDefined,
                "ffiScratchSize"       -> (ThisBuild / ffiScratchSize).?.value.isDefined,
                "ffiExtractDir"        -> (ThisBuild / ffiExtractDir).?.value.isDefined,
                "ffiStrictBlocking"    -> (ThisBuild / ffiStrictBlocking).?.value.isDefined,
                "ffiStrictCallbacks"   -> (ThisBuild / ffiStrictCallbacks).?.value.isDefined,
                "ffiStrictDiscovery"   -> (ThisBuild / ffiStrictDiscovery).?.value.isDefined,
                "ffiTargetOsArch"      -> (ThisBuild / ffiTargetOsArch).?.value.isDefined,
                "ffiPrebuiltDir"       -> (ThisBuild / ffiPrebuiltDir).?.value.isDefined,
                "ffiPrebuiltPool"      -> (ThisBuild / ffiPrebuiltPool).?.value.isDefined,
                "ffiRequiredPlatforms" -> (ThisBuild / ffiRequiredPlatforms).?.value.isDefined,
                "ffiSystemLibraries"   -> (ThisBuild / ffiSystemLibraries).?.value.isDefined
            )
            val unreachable = resolves.collect { case (key, false) => key }
            if (unreachable.nonEmpty)
                sys.error(s"these build-wide keys do not resolve at ThisBuild: ${unreachable.mkString(", ")}")
            // ffiLibraries is the probe ffiCompileAll and the packaging checks use to decide which
            // projects enable the plugin, so a build-wide default would answer yes for every project.
            if ((ThisBuild / ffiLibraries).?.value.isDefined)
                sys.error("ffiLibraries resolves at ThisBuild; the plugin-enablement probe now matches every project")
        },
        writeArtifactName := {
            // The plugin's own spelling of the naming convention, so the shell side asserts against
            // what ffiCompile produces rather than re-deriving the host os, arch and extension.
            IO.createDirectory(target.value)
            IO.write(target.value / "artifact.txt", ffiArtifactName(ffiLibraryId.value, ffiHostOsArch))
        },
        checkCFlags := {
            val resolved = ffiCFlags.value
            if (!resolved.contains("-DKYO_FFI_BUILD_LEVEL=1"))
                sys.error(s"the ThisBuild append never reached the project: $resolved")
            // An append that lands as a replacement drops -O2 and every flag the build set before it.
            if (!resolved.contains("-O2"))
                sys.error(s"the ThisBuild append replaced the defaults instead of extending them: $resolved")
        },
        checkTargetOsArch := {
            val resolved = ffiTargetOsArch.value
            val expected = Some("linux-x86_64")
            if (resolved != expected)
                sys.error(s"set ThisBuild / ffiTargetOsArch to $expected, the project resolved $resolved")
        }
    )
