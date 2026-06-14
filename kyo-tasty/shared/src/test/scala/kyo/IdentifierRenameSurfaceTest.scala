package kyo

import kyo.test.Test
import scala.compiletime.testing.typeCheckErrors

/** Compile-gate tests verifying that renamed public identifiers are accessible under their new names and that
  * old abbreviated names produce compile errors. Covers macros (`classFullName`), classpath types
  * (`FullNameCollision`, `ConstantPoolEntry`), internal helpers (`FullNameNormalizer`, `FullNameCanonicalizer`,
  * `ClassFullNameMacro`), and `TastyError` field names (`fullName`).
  */
class IdentifierRenameSurfaceTest extends Test[Any]:

    "classFullName macro exists and returns the dotted class name" in {
        val name: String = Tasty.classFullName[List[Int]]
        assert(name == "scala.collection.immutable.List")
    }

    "classFqn macro gone (old name does not compile)" in {
        val errors = typeCheckErrors("kyo.Tasty.classFqn[Int]")
        assert(errors.nonEmpty)
    }

    "Classpath.FullNameCollision exists under new name" in {
        val c = Tasty.Classpath.FullNameCollision("shop.Dog", Chunk.empty)
        assert(c.fullName == "shop.Dog")
    }

    "Classpath.FqnCollision gone (old name does not compile)" in {
        val errors = typeCheckErrors("kyo.Tasty.Classpath.FqnCollision")
        assert(errors.nonEmpty)
    }

    "FullNameNormalizer exists under new name" in {
        val errors = typeCheckErrors("kyo.internal.tasty.symbol.FullNameNormalizer")
        assert(errors.isEmpty)
    }

    "FqnNormalizer gone (old name does not compile)" in {
        val errors = typeCheckErrors("kyo.internal.tasty.symbol.FqnNormalizer")
        assert(errors.nonEmpty)
    }

    "FullNameCanonicalizer exists under new name" in {
        val errors = typeCheckErrors("kyo.internal.tasty.symbol.FullNameCanonicalizer")
        assert(errors.isEmpty)
    }

    "FqnCanonicalizer gone (old name does not compile)" in {
        val errors = typeCheckErrors("kyo.internal.tasty.symbol.FqnCanonicalizer")
        assert(errors.nonEmpty)
    }

    "ConstantPoolEntry exists under new name" in {
        val errors = typeCheckErrors("kyo.internal.tasty.classfile.ConstantPoolEntry")
        assert(errors.isEmpty)
    }

    "CpEntry gone (old name does not compile)" in {
        val errors = typeCheckErrors("kyo.internal.tasty.classfile.CpEntry")
        assert(errors.nonEmpty)
    }

    "ClassFullNameMacro exists under new name" in {
        val errors = typeCheckErrors("kyo.internal.tasty.macros.ClassFullNameMacro")
        assert(errors.isEmpty)
    }

    "ClassFqnMacro gone (old name does not compile)" in {
        val errors = typeCheckErrors("kyo.internal.tasty.macros.ClassFqnMacro")
        assert(errors.nonEmpty)
    }

    "TastyError.FullNameCollisionError has fullName field" in {
        val e: TastyError.FullNameCollisionError = TastyError.FullNameCollisionError("kyo.Foo")
        assert(e.fullName == "kyo.Foo")
    }

    "TastyError.SymbolNotFound has fullName field" in {
        val e: TastyError.SymbolNotFound = TastyError.SymbolNotFound("kyo.Bar")
        assert(e.fullName == "kyo.Bar")
    }

    "TastyError.NotFound has fullName field" in {
        val e: TastyError.NotFound = TastyError.NotFound("kyo.Baz")
        assert(e.fullName == "kyo.Baz")
    }

    "TastyError.InvalidFullName has fullName field" in {
        val e: TastyError.InvalidFullName = TastyError.InvalidFullName("kyo.X", "reason")
        assert(e.fullName == "kyo.X")
    }

    "TastyError.InvalidFullName old abbreviated field name does not compile" in {
        val errors = typeCheckErrors("""
            val e = kyo.TastyError.InvalidFullName("", "r")
            val _: String = e.oldAbbreviatedFieldName
        """)
        assert(errors.nonEmpty)
    }

end IdentifierRenameSurfaceTest
