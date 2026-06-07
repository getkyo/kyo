package kyo

import kyo.Tasty.SymbolId

/** Tests for Classpath.modules bulk aggregator: returns all loaded ModuleDescriptors. */
class ClasspathModulesAggregatorTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def makeModule(name: String): Tasty.Java.Module.Descriptor =
        Tasty.Java.Module.Descriptor(
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
                fqnIndex = Dict.empty,
                packageIndex = Dict.empty,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict(
                    "java.base"    -> makeModule("java.base"),
                    "java.logging" -> makeModule("java.logging"),
                    "myApp"        -> makeModule("myApp")
                ),
                errors = Chunk.empty
            )

    "ClasspathModulesAggregatorTest: modules returns all loaded ModuleDescriptors" in {
        buildFixture.map: cp =>
            val mods = cp.modules
            assert(mods.length == 3, s"Expected 3 modules, got ${mods.length}")
            val names = mods.map(_.name).toSet
            assert(names.contains("java.base"), s"Expected java.base in ${names.mkString(", ")}")
            assert(names.contains("java.logging"), s"Expected java.logging in ${names.mkString(", ")}")
            assert(names.contains("myApp"), s"Expected myApp in ${names.mkString(", ")}")
            succeed
    }

    "ClasspathModulesAggregatorTest: modules returns empty Chunk when no modules are loaded" in {
        Tasty.withPickles(Chunk.empty)(Tasty.classpath).map: cp =>
            assert(cp.modules.isEmpty, s"Expected empty modules but got ${cp.modules.length}")
            succeed
    }

end ClasspathModulesAggregatorTest
