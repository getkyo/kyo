// A JVM project whose only doctest setup is the plugin line in project/plugins.sbt, as kyo-doctest's README says: the
// plugin resolves the kyo-doctest runner itself.
lazy val root = (project in file("."))
    .settings(scalaVersion := sys.props("kyo.scalaVersion"))
