// JVM-only matrix; no JS/Native plugins needed (keeps per-test cost down).
sys.props.get("plugin.version") match {
    case Some(x) => addSbtPlugin("io.getkyo" % "kyo-compat-plugin" % x)
    case _       => sys.error("plugin.version not set")
}
addSbtPlugin("com.eed3si9n" % "sbt-projectmatrix" % "0.10.1")
