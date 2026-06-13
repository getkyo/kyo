ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "zig_test",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "main.c"),
        ffiCCompiler := "zig cc",
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version")
    )

// Detect zig at task-evaluation time; the scripted test skips when zig is absent.
lazy val zigAvailable = taskKey[Boolean]("Probe whether `zig` is on PATH")
zigAvailable := {
    val log = streams.value.log
    val probe = try {
        import sys.process._
        val exit = Process(Seq("zig", "version")).!(ProcessLogger(_ => (), _ => ()))
        exit == 0
    } catch {
        case _: Throwable => false
    }
    if (probe) log.info("[test] zig is available; proceeding with ffiCompile")
    else log.info("[test] zig not on PATH; skipping ffiCompile invocation")
    probe
}

lazy val compileIfZig = taskKey[Unit]("Run ffiCompile only when zig is available")
compileIfZig := Def.taskDyn {
    if (zigAvailable.value) Def.task { ffiCompile.value; () }
    else Def.task { streams.value.log.info("[test] skipped"); () }
}.value
