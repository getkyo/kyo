scalaVersion := "3.4.2"

val grpcVersion = "1.64.0"

libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-grpc-core" % kyo.grpc.compiler.BuildInfo.version,
    "io.grpc" % "grpc-netty" % grpcVersion
)

Compile / PB.targets := Seq(
    kyo.grpc.gen() -> (Compile / sourceManaged).value / "scalapb",
    scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
)

run / fork := true

scalafmtConfig := file("../../../.scalafmt.conf").getCanonicalFile
