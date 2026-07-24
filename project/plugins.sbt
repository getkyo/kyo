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
addSbtPlugin("org.scala-js"       % "sbt-scalajs"                   % "1.22.0")
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.12")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")
// sbt-projectmatrix backs kyo-compat's per-backend row generation.
addSbtPlugin("com.eed3si9n" % "sbt-projectmatrix" % "0.11.0")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.6")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.7.0")

addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.6.1")

// addSbtPlugin("com.gradle" % "sbt-develocity" % "1.0.1")

// addSbtPlugin("com.github.sbt" % "sbt-jacoco" % "3.4.0")

// ---------------------------------------------------------------------------
// Source-link `kyo-ffi-plugin` into the meta-build so the main build's
// `kyo-ffi-it` sub-project can call `enablePlugins(KyoFfiPlugin)` without
// requiring a `publishLocal` round-trip.
//
// Approach: meta-build source-linking. The main build's `kyo-ffi-it` sets
// `ffiCodegenClasspath` to the codegen project's own classpath, so the source-linked
// plugin loads the codegen from there directly (no publishLocal round-trip, no reload).
// ---------------------------------------------------------------------------

Compile / unmanagedSourceDirectories ++= {
    val pluginRoot = baseDirectory.value.getParentFile / "kyo-ffi" / "plugin"
    Seq(pluginRoot / "src" / "main" / "scala")
}
