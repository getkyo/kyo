addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.7")

val kyoVersion = "0.12.0-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "compilerplugin" % "0.11.17",
  "io.getkyo" %% "kyo-grpc-code-gen" % kyoVersion
)
