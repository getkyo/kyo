package kyo

/** Tests for the module-level lazy fallback Tasty.global: platform-specific empty fallback,
  * withClasspath override, and effect-row contract.
  *
  * The JVM scope-less fallback (`Tasty.global` cold-loading `java.class.path`) is intentionally
  * NOT exercised here. In the forked test JVM `java.class.path` is the full transitive test
  * classpath, so forcing the fallback cold-loads gigabytes and OOMs or hangs the fork. The JVM
  * fallback remains a production behavior; tests bind a fixture classpath via `withClasspath`
  * rather than relying on the scope-less path.
  */
class FallbackBindingTest extends kyo.test.Test[Any]:

    "JS fallback yields empty Classpath".onlyJs in {
        Tasty.classpath.map { classpath =>
            val n = classpath.symbols.size
            assert(n == 0, s"JS fallback must yield 0 symbols; got $n")
            succeed
        }
    }

    "Native fallback yields empty Classpath".onlyNative in {
        Tasty.classpath.map { classpath =>
            val n = classpath.symbols.size
            assert(n == 0, s"Native fallback must yield 0 symbols; got $n")
            succeed
        }
    }

    "Tasty.withClasspath(classpath) overrides the module-level fallback binding" in {
        val classpath = Tasty.Classpath(
            symbols = Chunk(
                Tasty.Symbol.Package(
                    Tasty.SymbolId(0),
                    Tasty.Name("root"),
                    Tasty.Flags.empty,
                    Tasty.SymbolId(-1),
                    Chunk.empty
                )
            ),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        Tasty.withClasspath(classpath) {
            Tasty.classpath.map { bound =>
                val n = bound.symbols.size
                assert(
                    n == 1,
                    s"withClasspath(classpath) must override the fallback; expected 1 symbol (fixture), got $n"
                )
                succeed
            }
        }
    }

    // bodyTree short-circuits for any symbol with no body bytes, regardless of active binding.
    "Tasty.bodyTree returns Maybe.Absent for a symbol with body = Maybe.Absent" in {
        // Use a synthetic Method with no body bytes; bodyTree must return Absent.
        val noBodyMethod = Tasty.Symbol.Method(
            id = Tasty.SymbolId(99),
            name = Tasty.Name("noBody"),
            flags = Tasty.Flags.empty,
            ownerId = Tasty.SymbolId(0),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            declaredType = Maybe.Absent,
            paramListIds = Chunk.empty,
            typeParamIds = Chunk.empty,
            annotations = Chunk.empty,
            javaMetadata = Maybe.Absent
        )
        Abort.run[TastyError](Tasty.bodyTree(noBodyMethod)).map {
            case Result.Success(maybe) =>
                assert(
                    !maybe.isDefined,
                    "Tasty.bodyTree must return Maybe.Absent for a symbol with body = Maybe.Absent"
                )
                succeed
            case Result.Failure(e) =>
                fail(s"Tasty.bodyTree raised unexpected TastyError: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "Tasty query operations carry < Sync in their effect row (compile-time check)" in {
        val _findClass: Maybe[Tasty.Symbol.Class] < Sync                           = Tasty.findClass("x")
        val _findClassLike: Maybe[Tasty.Symbol.ClassLike] < Sync                   = Tasty.findClassLike("x")
        val _findObject: Maybe[Tasty.Symbol.Object] < Sync                         = Tasty.findObject("x")
        val _findSymbol: Maybe[Tasty.Symbol] < Sync                                = Tasty.findSymbol("x")
        val _findPackage: Maybe[Tasty.Symbol.Package] < Sync                       = Tasty.findPackage("x")
        val _findModule: Maybe[Tasty.Java.Module.Descriptor] < Sync                = Tasty.findModule("x")
        val _findConcreteClass: Maybe[Tasty.Symbol.Class] < Sync                   = Tasty.findConcreteClass("x")
        val _findClassesByName: Chunk[Tasty.Symbol.Class] < Sync                   = Tasty.findClassesByName("x")
        val _findMethod: Maybe[Tasty.Symbol.Method] < Sync                         = Tasty.findMethod("x", "y")
        val _requireClass: Tasty.Symbol.Class < (Sync & Abort[TastyError])         = Tasty.requireClass("x")
        val _requireClassLike: Tasty.Symbol.ClassLike < (Sync & Abort[TastyError]) = Tasty.requireClassLike("x")
        val _requireObject: Tasty.Symbol.Object < (Sync & Abort[TastyError])       = Tasty.requireObject("x")
        val _requireSymbol: Tasty.Symbol < (Sync & Abort[TastyError])              = Tasty.requireSymbol("x")
        val _requirePackage: Tasty.Symbol.Package < (Sync & Abort[TastyError])     = Tasty.requirePackage("x")
        val _requireMethod: Tasty.Symbol.Method < (Sync & Abort[TastyError])       = Tasty.requireMethod("x", "y")
        val _allClassLike: Chunk[Tasty.Symbol.ClassLike] < Sync                    = Tasty.allClassLike
        val _allClasses: Chunk[Tasty.Symbol.Class] < Sync                          = Tasty.allClasses
        val _allObjects: Chunk[Tasty.Symbol.Object] < Sync                         = Tasty.allObjects
        val _allTraits: Chunk[Tasty.Symbol.Trait] < Sync                           = Tasty.allTraits
        val _allMethods: Chunk[Tasty.Symbol.Method] < Sync                         = Tasty.allMethods
        val _allVals: Chunk[Tasty.Symbol.Val] < Sync                               = Tasty.allVals
        val _allVars: Chunk[Tasty.Symbol.Var] < Sync                               = Tasty.allVars
        val _allFields: Chunk[Tasty.Symbol.Field] < Sync                           = Tasty.allFields
        val _allTypes: Chunk[Tasty.Symbol] < Sync                                  = Tasty.allTypes
        val _allPackages: Chunk[Tasty.Symbol.Package] < Sync                       = Tasty.allPackages
        discard(
            _findClass,
            _findClassLike,
            _findObject,
            _findSymbol,
            _findPackage,
            _findModule,
            _findConcreteClass,
            _findClassesByName,
            _findMethod,
            _requireClass,
            _requireClassLike,
            _requireObject,
            _requireSymbol,
            _requirePackage,
            _requireMethod,
            _allClassLike,
            _allClasses,
            _allObjects,
            _allTraits,
            _allMethods,
            _allVals,
            _allVars,
            _allFields,
            _allTypes,
            _allPackages
        )
        succeed
    }

end FallbackBindingTest
