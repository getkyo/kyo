package kyo.natives.sbt

/** Which runtime a project builds for, read from the auto-plugins it enables.
  *
  * The per-platform wiring lives in plugins that require the platform's own plugin, so by the time settings apply the
  * platform is already decided. This exists for the settings that are shared and still have to answer differently,
  * which is the JVM classpath contribution: adding a carrier jar to a Native or JS project would put a second copy of
  * every library into its artifact.
  */
private[sbt] sealed trait Platform {

    /** This platform's name in a [[kyo.ffi.sbt.NativeDelivery]] declaration's platform scope. */
    def declarationName: String
}

private[sbt] object Platform {
    case object Jvm    extends Platform { val declarationName = "jvm"    }
    case object Native extends Platform { val declarationName = "native" }
    case object Js     extends Platform { val declarationName = "js"     }

    /** The platform `pluginLabels` names. The class names are sbt-scala-native's and sbt-scalajs' own and are stable
      * across their releases, which is why matching on them is sound.
      */
    def of(pluginLabels: Set[String]): Platform =
        if (has(pluginLabels, "ScalaNativePlugin")) Native
        else if (has(pluginLabels, "ScalaJSPlugin")) Js
        else Jvm

    /** sbt builds a label as the plugin object's class name with the trailing `$` stripped, so a label is the
      * fully qualified name and the simple name is what follows the last dot.
      */
    private def has(labels: Set[String], simpleName: String): Boolean =
        labels.exists(l => l == simpleName || l.endsWith("." + simpleName))
}
