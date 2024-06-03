scalaVersion := "3.4.2"

val grpcVersion = "1.64.0"

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
  "io.grpc" % "grpc-netty" % grpcVersion,
  "io.getkyo" %% "kyo-grpc-core" % kyo.grpc.compiler.BuildInfo.version
)

run / fork := true

scalafmtConfig := file("../../../.scalafmt.conf").getCanonicalFile
