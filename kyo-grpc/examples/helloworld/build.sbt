scalaVersion := "3.5.0"

libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-grpc-core" % kyo.grpc.compiler.BuildInfo.version,
    "io.grpc" % "grpc-netty" % "1.65.1"
)

Compile / PB.targets := Seq(
    kyo.grpc.gen() -> (Compile / sourceManaged).value / "scalapb",
    scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
)

Compile / scalacOptions += "-Wconf:src=.*/src_managed/main/scalapb/.*:silent"

run / fork := true

scalafmtConfig := file("../../../.scalafmt.conf").getCanonicalFile
