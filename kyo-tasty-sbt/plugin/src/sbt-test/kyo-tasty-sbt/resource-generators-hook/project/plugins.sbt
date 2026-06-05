sys.props.get("plugin.version") match {
    case Some(x) => addSbtPlugin("io.getkyo" % "kyo-tasty-sbt" % x)
    case _       => sys.error("plugin.version not set")
}
