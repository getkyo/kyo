addSbtPlugin("org.scala-native" % "sbt-scala-native"   % sys.props("scalanative.version"))
// One kyo plugin. kyo-ffi-plugin rides in transitively and stays inert, being noTrigger, so nothing a binding author
// needs runs in this application's build.
addSbtPlugin("io.getkyo"        % "kyo-natives-plugin" % sys.props("kyo.version"))
