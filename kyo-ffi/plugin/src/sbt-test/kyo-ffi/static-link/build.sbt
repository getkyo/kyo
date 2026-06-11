ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId  := "static_test",
        ffiCSources   := Seq(baseDirectory.value / "src" / "main" / "c" / "main.c"),
        // staticLink folds named link libs into the .so via the GNU ld / lld static
        // toggle (`-Wl,-Bstatic <libs> -Wl,-Bdynamic`); with no link libs it is a
        // no-op, so declare one so the toggle appears in the cc command.
        ffiLinkLibs   := Seq("foo"),
        ffiStaticLink := true,
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version")
    )

// Write the resolved cc command(s) to a file so the scripted test can grep
// for the static-link toggle. `ffiDumpCcCommand` avoids invoking the compiler,
// so the fictitious link lib never has to resolve. Flag plumbing is the property
// under test.
lazy val dumpCcCommand = taskKey[Unit]("Write ffiDumpCcCommand output to target/cc-cmd.txt")
dumpCcCommand := {
    val cmds = ffiDumpCcCommand.value
    IO.createDirectory(target.value)
    IO.write(target.value / "cc-cmd.txt", cmds.map(_.mkString(" ")).mkString("\n"))
    streams.value.log.info("[test] cc command: " + cmds.map(_.mkString(" ")).mkString(" | "))
}
