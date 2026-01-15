scalaVersion := "3.7.0"

libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-grpc-core" % kyo.grpc.compiler.BuildInfo.version,
    "io.grpc" % "grpc-netty-shaded" % "1.72.0"
)

Compile / PB.targets := Seq(
    kyo.grpc.gen() -> (Compile / sourceManaged).value / "scalapb",
    scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
)

Compile / scalacOptions += "-Wconf:src=.*/src_managed/main/scalapb/.*:silent"

run / fork := true

scalafmtConfig := file("../../../.scalafmt.conf").getCanonicalFile
