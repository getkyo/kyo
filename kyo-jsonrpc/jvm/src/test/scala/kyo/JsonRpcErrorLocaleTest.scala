package kyo

/** Runs in a fork whose default locale is Turkish (`locale-fork-settings` in build.sbt), where `"Init".toLowerCase` is `ınit`. An error
  * message names its lifecycle stage in ASCII on every machine. JVM only: Scala.js and Scala Native fold without a locale.
  */
class JsonRpcErrorLocaleTest extends JsonRpcTest:

    "the fork's default locale is Turkish" in {
        assert(java.util.Locale.getDefault.getLanguage == "tr", s"expected a Turkish default locale, got ${java.util.Locale.getDefault}")
        assert("I".toLowerCase == "ı")
    }

    "a lifecycle stage describes itself in ASCII" in {
        assert(JsonRpcLifecycleError.Stage.Init.describe == "init")
        assert(JsonRpcLifecycleError.Stage.Bind.describe == "bind")
        assert(JsonRpcLifecycleError(JsonRpcLifecycleError.Stage.Init).message == "Lifecycle error: init")
    }

end JsonRpcErrorLocaleTest
