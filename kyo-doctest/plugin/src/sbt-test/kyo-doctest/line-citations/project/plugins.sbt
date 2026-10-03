sys.props.get("plugin.version") match {
    case Some(x) => addSbtPlugin("io.getkyo" % "kyo-doctest-plugin" % x)
    case _       => sys.error("plugin.version not set")
}
