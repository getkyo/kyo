package kyo.ffi.sbt

/** Detects the surrounding crossProject platform from the project's enabled auto-plugins.
  *
  * Consumed by `KyoFfiPlugin` to auto-default `ffiTargetPlatform`, so that a plain
  * `enablePlugins(KyoFfiPlugin, ScalaNativePlugin)` or `enablePlugins(KyoFfiPlugin,
  * ScalaJSPlugin)` declaration is sufficient, users do not need to hand-wire
  * `ffiTargetPlatform` on top of a `crossProject` setup.
  */
private[sbt] object PlatformDetect {

    sealed trait Platform {
        def name: String
    }
    case object Jvm extends Platform {
        val name: String = "JVM"
    }
    case object Native extends Platform {
        val name: String = "Native"
    }
    case object Js extends Platform {
        val name: String = "JS"
    }

    /** sbt exposes the enabled auto-plugin list as a plain setting, and the plugin-class
      * simpleName convention (`ScalaNativePlugin`, `ScalaJSPlugin`) is stable across releases.
      *
      * The Native check runs before the JS one so a project that enables both still resolves to a
      * platform rather than falling through to the JVM default, which would compile the C for the
      * wrong target.
      */
    def detectFromAutoPlugins(pluginNames: Set[String]): Platform =
        if (containsSuffix(pluginNames, "ScalaNativePlugin")) Native
        else if (containsSuffix(pluginNames, "ScalaJSPlugin")) Js
        else Jvm

    private def containsSuffix(labels: Set[String], suffix: String): Boolean =
        labels.exists(l => l == suffix || l.endsWith("." + suffix) || l.endsWith("$" + suffix))
}
