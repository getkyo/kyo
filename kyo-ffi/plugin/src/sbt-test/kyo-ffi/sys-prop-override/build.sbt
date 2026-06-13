ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// sys-prop override test: the library is built by the `libBuilder` sub-project
// and lives under lib-builder/target/ffi/. The main project has NO ffiCSources
// so no META-INF/native/spo_lib is packaged. Consequently the only way `Ffi.load`
// can succeed is by honoring `-Dkyo.ffi.spo_lib.path=<abs>`.
lazy val libBuilder = (project in file("lib-builder"))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "spo_lib",
        ffiCSources  := Seq((ThisBuild / baseDirectory).value / "src" / "main" / "c" / "spo_lib.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version")
    )

lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "spo_lib",
        // Intentionally empty: the main project has no C sources so no lib is
        // packaged into META-INF/native. The library comes from libBuilder via
        // the -Dkyo.ffi.spo_lib.path override.
        ffiCSources := Nil,
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        // Custom run task that forks with -Dkyo.ffi.spo_lib.path set to the
        // dynamic path built by libBuilder.
        TaskKey[Unit]("runWithOverride") := {
            val path = libAbsolutePath.value
            val cp   = (Compile / fullClasspath).value
            val log  = streams.value.log
            log.info(s"[sys-prop-override] kyo.ffi.spo_lib.path=$path")
            val forkOpts = ForkOptions().withRunJVMOptions(Vector(
                "--enable-native-access=ALL-UNNAMED",
                s"-Dkyo.ffi.spo_lib.path=$path"
            ))
            val exit = Fork.java(forkOpts, Seq(
                "-cp",
                cp.files.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
                "spo.Main"
            ))
            if (exit != 0) sys.error(s"spo.Main exited with code $exit")
        }
    )

// Absolute path to the compiled shared library built by libBuilder.
lazy val libAbsolutePath = taskKey[String]("Absolute path to the compiled spo_lib shared library.")
libAbsolutePath := {
    // Ensure libBuilder has built the lib.
    (libBuilder / ffiCompile).value
    val targetDir = (libBuilder / target).value / "ffi"
    val files = Option(targetDir.listFiles).toSeq.flatten.filter { f =>
        val n = f.getName
        (n.startsWith("libspo_lib-") || n.startsWith("spo_lib-")) &&
            (n.endsWith(".so") || n.endsWith(".dylib") || n.endsWith(".dll"))
    }
    files.headOption.map(_.getAbsolutePath).getOrElse(
        sys.error(s"[sys-prop-override] no compiled spo_lib artifact in $targetDir")
    )
}
