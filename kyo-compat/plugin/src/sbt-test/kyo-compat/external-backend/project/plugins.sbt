// The outer build's scriptedLaunchOpts set every property this fixture reads.
def prop(name: String): String =
    sys.props.getOrElse(name, sys.error(s"$name is not set: run this fixture through kyo-compat-plugin/scripted"))

addSbtPlugin("io.getkyo"          % "kyo-compat-plugin"             % prop("plugin.version"))
addSbtPlugin("com.eed3si9n"       % "sbt-projectmatrix"             % prop("projectmatrix.version"))
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % prop("crossproject.version"))
addSbtPlugin("org.scala-js"       % "sbt-scalajs"                   % prop("scalajs.version"))
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % prop("scalanative.version"))
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % prop("crossproject.version"))
