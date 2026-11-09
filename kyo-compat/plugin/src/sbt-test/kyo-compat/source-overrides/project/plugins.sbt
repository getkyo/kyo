sys.props.get("plugin.version") match {
    case Some(x) => addSbtPlugin("io.getkyo" % "kyo-compat-plugin" % x)
    case _       => sys.error("plugin.version not set")
}
addSbtPlugin("org.scala-js"     % "sbt-scalajs"      % "1.22.0")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")
