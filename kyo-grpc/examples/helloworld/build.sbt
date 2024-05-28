scalaVersion := "3.4.2"

val grpcVersion = "1.64.0"

val kyoVersion = "0.10.2+31-921c2f57+20240528-2234-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
  "io.grpc" % "grpc-netty" % grpcVersion,
  "io.getkyo" %% "kyo-core" % kyoVersion
)

run / fork := true

scalafmtConfig := file("../../../.scalafmt.conf").getCanonicalFile
