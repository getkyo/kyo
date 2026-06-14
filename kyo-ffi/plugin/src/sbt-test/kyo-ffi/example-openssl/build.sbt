ThisBuild / scalaVersion := "3.8.3"
ThisBuild / version      := "0.1.0-SNAPSHOT"

// OpenSSL worked example. Real OpenSSL is not linked on the CI/build host, a
// stub C file implements the same ABI signatures so the binding + generator
// path is exercised end-to-end without a platform-specific dependency.
//
// Real-world users of this example would:
//   * remove `ffiCSources` (the stub)
//   * set `ffiLinkLibs := Seq("ssl", "crypto")`
//   * keep the identical TLS Scala trait
lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin)
    .settings(
        ffiLibraryId := "kyo_openssl_stub",
        ffiCSources  := Seq(baseDirectory.value / "src" / "main" / "c" / "kyo_openssl_stub.c"),
        libraryDependencies += "io.getkyo" %% "kyo-ffi" % sys.props("kyo.version"),
        Compile / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        Compile / fork := true,
        run / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED"),
        run / fork := true
    )
