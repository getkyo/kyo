addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.7")

val kyoVersion = "0.10.2+79-29a3c8a3+20240603-2055-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "compilerplugin" % "0.11.15",
  "io.getkyo" %% "kyo-grpc-code-gen" % kyoVersion
)
