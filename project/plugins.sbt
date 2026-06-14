addSbtPlugin("pl.project13.scala" % "sbt-jmh"        % "0.4.8")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"   % "2.5.6")
addSbtPlugin("com.github.sbt"     % "sbt-ci-release" % "1.11.2")
addSbtPlugin("com.eed3si9n"       % "sbt-buildinfo"  % "0.13.1")

// kyo-doctest-plugin: wired in-tree via project/build.sbt (same pattern as kyo-compat-plugin).
// The plugin's source is compiled directly into the meta-build, so build.sbt
// can reference `KyoDoctestPlugin` without an addSbtPlugin/ivy round trip.
// Auto-enables on JVM projects (allRequirements trigger + JvmPlugin requires).

// kyo-compat-plugin (in-tree plugin, wired in via project/build.sbt) needs
// these on the meta-build's compile classpath too; see project/build.sbt.
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % "1.3.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"                   % "1.21.0")
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.10")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")
// sbt-projectmatrix backs kyo-compat's per-backend row generation.
addSbtPlugin("com.eed3si9n" % "sbt-projectmatrix" % "0.10.1")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.5")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.6.4")

addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.6.1")

// addSbtPlugin("com.gradle" % "sbt-develocity" % "1.0.1")

// addSbtPlugin("com.github.sbt" % "sbt-jacoco" % "3.4.0")

// ---------------------------------------------------------------------------
// Source-link `kyo-ffi-plugin` into the meta-build so the main build's
// `kyo-ffi-it` sub-project can call `enablePlugins(KyoFfiPlugin)` without
// requiring a `publishLocal` round-trip.
//
// Approach: meta-build source-linking. The plugin's `CodegenBridge` extracts a
// bundled `kyo-ffi-codegen.jar` (plus Scala 3 runtime deps) from the classpath
// resource `/kyo-ffi-plugin/bundled.txt`. We replicate the resource generation
// in the meta-build by delegating to the plugin sub-project's existing
// `Compile / resourceManaged` output.
//
// Bootstrap order:
//   1. `sbt kyo-ffi-plugin/compile`  (generates bundled resources)
//   2. `sbt kyo-ffi-it/test`          (picks up the bundled resources via the resourceGenerator below)
//
// On CI, step 1 is equivalent to any other `compile` already in the matrix.
// ---------------------------------------------------------------------------

Compile / unmanagedSourceDirectories ++= {
    val pluginRoot = baseDirectory.value.getParentFile / "kyo-ffi" / "plugin"
    Seq(pluginRoot / "src" / "main" / "scala")
}

// Bundle the plugin's codegen resources into the meta-build's classpath.
// The plugin's own build.sbt writes these to
// `kyo-ffi-plugin/target/scala-2.12/resource_managed/main/kyo-ffi-plugin/`.
Compile / resourceGenerators += Def.task {
    val log            = sLog.value
    val outDir         = (Compile / resourceManaged).value / "kyo-ffi-plugin"
    val pluginRoot     = baseDirectory.value.getParentFile / "kyo-ffi" / "plugin"
    val resolvedResDir = pluginRoot / "target" / "scala-2.12" / "sbt-1.0" / "resource_managed" / "main" / "kyo-ffi-plugin"
    if (!resolvedResDir.exists) {
        log.info(
            s"[kyo-ffi-it] bundled plugin resources not found at $resolvedResDir; " +
                "run `sbt kyo-ffi-plugin/compile` once, then reload."
        )
        Seq.empty[File]
    } else {
        IO.createDirectory(outDir)
        IO.listFiles(resolvedResDir).toList.map { src =>
            val dest = outDir / src.getName
            IO.copyFile(src, dest)
            dest
        }
    }
}.taskValue

libraryDependencies ++= Seq(
    "org.typelevel" %% "scalac-options" % "0.1.9"
)
