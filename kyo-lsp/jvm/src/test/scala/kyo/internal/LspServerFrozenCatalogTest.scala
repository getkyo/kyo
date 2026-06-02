package kyo.internal

import kyo.*

/** Verifies that no handler-mutation methods exist on LspServer.
  *
  * The server catalog is frozen at init time. No add/remove/set handler methods must appear
  * on the LspServer extension surface. This test reflects LspServer companion methods and
  * asserts none match the mutation pattern.
  */
class LspServerFrozenCatalogTest extends Test:

    private val mutationPattern = "(?i)(add|remove|set)(handler|route|catalog)".r

    "LspServer companion has no add/remove/set handler methods" in run {
        val companionMethods = classOf[LspServer.type].getMethods.map(_.getName).toSet
        val mutations        = companionMethods.filter(name => mutationPattern.findFirstIn(name).isDefined)
        assert(mutations.isEmpty, s"Found mutation methods on LspServer: ${mutations.mkString(", ")}")
    }

    "LspServer.Unsafe has no add/remove/set handler methods" in run {
        val unsafeMethods = classOf[LspServer.Unsafe].getMethods.map(_.getName).toSet
        val mutations     = unsafeMethods.filter(name => mutationPattern.findFirstIn(name).isDefined)
        assert(mutations.isEmpty, s"Found mutation methods on LspServer.Unsafe: ${mutations.mkString(", ")}")
    }

    "LspServer init methods are all named init/initWith/initUnscoped/initUnscopedWith" in run {
        val companionMethods = classOf[LspServer.type].getMethods.map(_.getName).toSet
        // Filter out Scala-generated default-parameter accessors (e.g. init$default$3).
        val initMethods   = companionMethods.filter(n => n.startsWith("init") && !n.contains("$"))
        val validPrefixes = Set("init", "initWith", "initUnscoped", "initUnscopedWith")
        val invalidInits  = initMethods.filterNot(n => validPrefixes.exists(p => n == p))
        assert(invalidInits.isEmpty, s"Unexpected init method names: ${invalidInits.mkString(", ")}")
    }

end LspServerFrozenCatalogTest
