// Isolated experiment for Tag and opaque types. Pinned to the version and options the real build uses
// so that what is proven here transfers. Tag.scala and TagMacro.scala are the files ported back verbatim.
val scala3Version = "3.8.4"

lazy val commonSettings = Seq(
    scalaVersion := scala3Version,
    scalacOptions ++= Seq(
        "-encoding",
        "utf8",
        "-feature",
        "-unchecked",
        "-deprecation",
        "-Wvalue-discard",
        "-Wnonunit-statement",
        "-language:strictEquality",
        "-Xkind-projector:underscores",
        "-Xcheck-macros"
    ),
    // Older macro versions used for calibration trip -Xcheck-macros warnings; -Dwerror=false lifts the gate for those runs only.
    scalacOptions ++= (if (sys.props.get("werror").contains("false")) Nil else Seq("-Werror")),
    Test / scalacOptions --= Seq("-Wnonunit-statement"),
    libraryDependencies += "org.scalameta" %% "munit" % "1.1.1" % Test
)

lazy val core   = project.settings(commonSettings)
lazy val lib    = project.dependsOn(core).settings(commonSettings)
lazy val probes = project.dependsOn(core, lib).settings(commonSettings)
lazy val tests  = project.dependsOn(core, lib).settings(commonSettings)
// Must NOT compile: rows whose only observable is a real definition being refused.
lazy val negative = project.dependsOn(core).settings(commonSettings)
lazy val root     = (project in file(".")).aggregate(core, lib, probes, tests).settings(commonSettings)
