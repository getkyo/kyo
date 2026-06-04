package kyo

import kyo.Tasty.SymbolId

/** Phase 08 followup for W-06-03: exercises `Classpath.modules` bulk aggregator.
  *
  * W-06-03 noted that design/02-design.md §Classpath typed-accessors lists `def modules: Chunk[ModuleDescriptor]` but Phase 06 only landed
  * `findModule` and `requireModule`. This test validates the new `modules` aggregator added in Phase 08.
  *
  * Pins: INV-005.
  */
class ClasspathModulesAggregatorTest extends Test:

    import AllowUnsafe.embrace.danger

    private def makeModule(name: String): Tasty.ModuleDescriptor =
        Tasty.ModuleDescriptor(
            name = name,
            version = Maybe.Absent,
            requires = Chunk.empty,
            exports = Chunk.empty,
            opens = Chunk.empty,
            uses = Chunk.empty,
            provides = Chunk.empty
        )

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            Tasty.Classpath.make(
                symbols = Chunk.empty,
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                fqnIndex = Map.empty,
                packageIndex = Map.empty,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map(
                    "java.base"    -> makeModule("java.base"),
                    "java.logging" -> makeModule("java.logging"),
                    "myApp"        -> makeModule("myApp")
                ),
                errors = Chunk.empty
            )

    // Given: a classpath with 3 module descriptors in moduleIndex
    // When: cp.modules
    // Then: returns Chunk of size 3 with the expected names
    "ClasspathModulesAggregatorTest: modules returns all loaded ModuleDescriptors" in run {
        buildFixture.map: cp =>
            val mods = cp.modules
            assert(mods.length == 3, s"Expected 3 modules, got ${mods.length}")
            val names = mods.map(_.name).toSet
            assert(names.contains("java.base"), s"Expected java.base in ${names.mkString(", ")}")
            assert(names.contains("java.logging"), s"Expected java.logging in ${names.mkString(", ")}")
            assert(names.contains("myApp"), s"Expected myApp in ${names.mkString(", ")}")
            succeed
    }

    // Given: a classpath with 0 modules
    // When: cp.modules
    // Then: returns an empty Chunk
    "ClasspathModulesAggregatorTest: modules returns empty Chunk when no modules are loaded" in run {
        Tasty.withPickles(Chunk.empty)(Tasty.classpath).map: cp =>
            assert(cp.modules.isEmpty, s"Expected empty modules but got ${cp.modules.length}")
            succeed
    }

end ClasspathModulesAggregatorTest
