addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.7")

val kyoVersion = "0.19.0+288-ef403b33+20250706-0017-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.thesamet.scalapb" %% "compilerplugin" % "0.11.17",
  "io.getkyo" %% "kyo-grpc-code-gen" % kyoVersion
)
