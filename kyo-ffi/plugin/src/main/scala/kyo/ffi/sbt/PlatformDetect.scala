package kyo.ffi.sbt

/** Detects the surrounding crossProject platform.
  *
  * Strategy: inspect the set of task-key labels defined in the project, Scala Native
  * projects expose `nativeLink`, Scala.js projects expose `fastLinkJS`. Falls back to
  * JVM if neither is present.
  *
  * Consumed by `KyoFfiPlugin` to auto-default `ffiTargetPlatform`, so that a plain
  * `enablePlugins(KyoFfiPlugin, ScalaNativePlugin)` or `enablePlugins(KyoFfiPlugin,
  * ScalaJSPlugin)` declaration is sufficient, users do not need to hand-wire
  * `ffiTargetPlatform` on top of a `crossProject` setup.
  */
private[sbt] object PlatformDetect {

    sealed trait Platform {
        def name: String
        def codegenName: String
    }
    case object Jvm extends Platform {
        val name: String        = "JVM"
        val codegenName: String = "JVM"
    }
    case object Native extends Platform {
        val name: String        = "Native"
        val codegenName: String = "Native"
    }
    case object Js extends Platform {
        val name: String        = "JS"
        val codegenName: String = "JS"
    }

    /** Heuristic detection based on task-key labels defined in the project.
      *
      *  - Presence of `nativeLink`   ⇒ Scala Native.
      *  - Presence of `fastLinkJS`   ⇒ Scala.js.
      *  - Neither                    ⇒ JVM.
      *
      * The `nativeLink` check is intentionally before `fastLinkJS`: a misconfigured
      * project that somehow enables both plugins would still compile (we pick one)
      * instead of silently falling through to JVM.
      */
    def detectFromSettings(definedKeys: Set[String]): Platform =
        if (definedKeys.contains("nativeLink")) Native
        else if (definedKeys.contains("fastLinkJS")) Js
        else Jvm

    /** Auto-plugin-based detection, preferred, because sbt exposes the enabled
      * auto-plugin list as a plain setting. The plugin-class-name simpleName
      * convention (`ScalaNativePlugin`, `ScalaJSPlugin`) is stable across releases.
      */
    def detectFromAutoPlugins(pluginNames: Set[String]): Platform =
        if (containsSuffix(pluginNames, "ScalaNativePlugin")) Native
        else if (containsSuffix(pluginNames, "ScalaJSPlugin")) Js
        else Jvm

    private def containsSuffix(labels: Set[String], suffix: String): Boolean =
        labels.exists(l => l == suffix || l.endsWith("." + suffix) || l.endsWith("$" + suffix))
}
