addSbtPlugin("pl.project13.scala" % "sbt-jmh"        % "0.4.8")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"   % "2.5.6")
addSbtPlugin("com.github.sbt"     % "sbt-ci-release" % "1.11.2")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.20.2")

addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.10")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")

addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.8.2")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.4")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.6.3")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.5")

// addSbtPlugin("com.gradle" % "sbt-develocity" % "1.0.1")

// addSbtPlugin("com.github.sbt" % "sbt-jacoco" % "3.4.0")

libraryDependencies ++= Seq(
    "org.typelevel" %% "scalac-options"           % "0.1.8",
    "org.scala-js"  %% "scalajs-env-jsdom-nodejs" % "1.1.1"
)
