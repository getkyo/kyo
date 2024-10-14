addSbtPlugin("pl.project13.scala" % "sbt-jmh"        % "0.4.7")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"   % "2.5.2")
addSbtPlugin("com.github.sbt"     % "sbt-ci-release" % "1.8.0")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.17.0")

addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.5")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")

addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.6.1")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.2.1")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.4")

// addSbtPlugin("com.gradle" % "sbt-develocity" % "1.0.1")

// addSbtPlugin("com.github.sbt" % "sbt-jacoco" % "3.4.0")

libraryDependencies ++= Seq(
    "org.typelevel" %% "scalac-options" % "0.1.7"
)
