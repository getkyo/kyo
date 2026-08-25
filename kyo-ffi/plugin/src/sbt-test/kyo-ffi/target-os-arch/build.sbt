ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// The host tag the plugin resolves, and a DIFFERENT tag to point ffiTargetOsArch at. Deriving the
// cross tag from the host keeps the test meaningful on every runner: whatever this machine is, the
// crossed project targets something it is not.
val hostTag = ffiHostOsArch
val crossTag = {
    val cut  = hostTag.lastIndexOf('-')
    val os   = hostTag.substring(0, cut)
    val arch = hostTag.substring(cut + 1)
    s"$os-${if (arch == "x86_64") "aarch64" else "x86_64"}"
}

// Unset ffiTargetOsArch: the host, exactly as before the setting existed.
lazy val hostDefault = (project in file("host-default"))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "tgt",
        ffiCSources  := Seq((ThisBuild / baseDirectory).value / "src" / "main" / "c" / "tgt.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version")
    )

// Same sources, a foreign target. Only the NAMING and LAYOUT contract is under test here: no
// toolchain cross flags are passed, so the compiler still emits this host's ISA. A real cross-build
// adds them as well (e.g. ffiCFlags += "-arch x86_64" for darwin-x86_64 on an arm64 Mac).
lazy val crossed = (project in file("crossed"))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId    := "tgt",
        ffiCSources     := Seq((ThisBuild / baseDirectory).value / "src" / "main" / "c" / "tgt.c"),
        ffiTargetOsArch := Some(crossTag),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version")
    )

// Hand the two tags to the shell side so it asserts against the plugin's own resolution instead of
// re-deriving the host os/arch itself (the divergence this change exists to remove).
lazy val writeTags = taskKey[Unit]("Write the host and cross os-arch tags to target/tags.txt")
writeTags := {
    IO.createDirectory(target.value)
    IO.write(target.value / "tags.txt", s"$hostTag\n$crossTag\n")
}
