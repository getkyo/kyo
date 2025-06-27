addSbtPlugin("pl.project13.scala" % "sbt-jmh"        % "0.4.7")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"   % "2.5.4")
addSbtPlugin("com.github.sbt"     % "sbt-ci-release" % "1.11.1")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.19.0")

addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.8")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")

addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.7.1")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.3.1")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.4")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.6.3")

// addSbtPlugin("com.gradle" % "sbt-develocity" % "1.0.1")

// addSbtPlugin("com.github.sbt" % "sbt-jacoco" % "3.4.0")

libraryDependencies ++= Seq(
    "org.typelevel" %% "scalac-options" % "0.1.8"
)
