sys.props.get("plugin.version") match {
    case Some(x) => addSbtPlugin("io.getkyo" % "sbt-kyo-doctest" % x)
    case _       => sys.error("plugin.version not set")
}
