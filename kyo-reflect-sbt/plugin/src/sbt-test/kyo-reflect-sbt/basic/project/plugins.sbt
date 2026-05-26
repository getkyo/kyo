sys.props.get("plugin.version") match {
    case Some(x) => addSbtPlugin("io.getkyo" % "kyo-reflect-sbt" % x)
    case _       => sys.error("plugin.version not set")
}
