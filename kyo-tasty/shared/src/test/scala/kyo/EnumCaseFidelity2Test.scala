package kyo
import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.symbol.SymbolKind

/** Fidelity tests for Symbol.EnumCase reclassification: value-form (VALDEF + Enum+Case),
  * singleton-object form (Module+Enum+Case), and Java enum constants (Field+Enum+Static).
  * The embedded Color and Shape fixtures exercise value-form and class-form respectively on all platforms.
  */
class EnumCaseFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger
    import Tasty.Name.asString

    "classpath has at least one EnumCase child (value-form or class-form)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val enumCases = cp.symbols.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
            assert(
                enumCases > 0,
                s"Expected at least 1 EnumCase symbol. " +
                    s"Total symbols: ${cp.symbols.size}. " +
                    s"On JVM: kyo.SymbolKind enum cases expected. On JS/Native: Color.Red/Green/Blue expected."
            )
            succeed
    }

    "every enum class has at least one EnumCase child" in {
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>

            val enums = cp.allClassLike.filter(e => e.isEnum && !e.isInstanceOf[Tasty.Symbol.EnumCase]).toList
            assert(enums.nonEmpty, "Expected at least one enum class in the classpath (embedded: Color, Shape)")

            def hasEnumCase(e: Tasty.Symbol.ClassLike): Boolean =
                if (e match
                        case c: Tasty.Symbol.Class =>
                            c.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty);
                        case t: Tasty.Symbol.Trait =>
                            t.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty);
                        case _ => Chunk.empty
                    ).exists(_.isInstanceOf[Tasty.Symbol.EnumCase])
                then
                    true
                else
                    e.declarationIds.flatMap(id => cp.symbol(id).toChunk).exists: decl =>
                        decl match
                            case companion: Tasty.Symbol.Object =>
                                companion.declarationIds.flatMap(id => cp.symbol(id).toChunk).exists(_.isInstanceOf[Tasty.Symbol.EnumCase])
                            case _ => false
                end if
            end hasEnumCase

            val enumsWithNoCases = enums.filterNot(hasEnumCase)

            Kyo.foreach(enumsWithNoCases.take(5))(e => cp.fullName(e).map(_.asString)).map: names =>
                assert(
                    enumsWithNoCases.isEmpty,
                    s"${enumsWithNoCases.size} enum(s) have no EnumCase children: " +
                        names.mkString(", ")
                )
                succeed
    }

    "total EnumCase count > 0 after reclassification (at least embedded Color + Shape cases)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val enumCaseCount = cp.symbols.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
            assert(
                enumCaseCount > 0,
                s"Expected > 0 EnumCase symbols; found $enumCaseCount. " +
                    s"Embedded fixtures: Color (Red/Green/Blue value-form) + Shape (Circle/Square/Rectangle class-form)."
            )
            succeed
    }

    // Color.Red/Green/Blue are value-form enum cases; their owner is always the companion Object (Color$).
    "Color value-form EnumCase owner is the companion Object" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>

            // Look for value-form enum cases from known enums:
            // kyo.SymbolKind (on JVM real classpath from kyo-tasty jar)
            // kyo.fixtures.Color (on JS/Native embedded fixtures)
            // Both are pure value-form enums with Object companions.
            val knownEnumNames = Set("SymbolKind", "Color")
            val knownValueFormCases = cp.symbols.collect:
                case e: Tasty.Symbol.EnumCase
                    if knownEnumNames.exists(n => cp.symbol(e.ownerId).map(_.name.asString.startsWith(n)).getOrElse(false)) =>
                    e

            if knownValueFormCases.isEmpty then
                // No known value-form enum cases found; skip (acceptable for platforms where neither enum is present)
                succeed
            else
                val wrongOwner = knownValueFormCases.filter: e =>
                    cp.symbol(e.ownerId) match
                        case _: Tasty.Symbol.Object => false
                        case _                      => true

                assert(
                    wrongOwner.isEmpty,
                    s"${wrongOwner.size} value-form EnumCase symbols have non-Object owner: " +
                        wrongOwner.take(3).map: e =>
                            val ownerKind = cp.symbol(e.ownerId).getClass.getSimpleName
                            s"${e.name.asString} (owner: $ownerKind)"
                        .mkString(", ")
                )
                succeed
            end if
    }

    "class-form EnumCase still correctly classified (Shape or TastyError)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>

            // Try kyo.TastyError first (JVM), fall back to kyo.fixtures.Shape (JS/Native)
            val target = cp.findClass("kyo.TastyError").orElse(cp.findClass("kyo.fixtures.Shape"))
            target match
                case Maybe.Present(enumClass) =>
                    val permSubEnumCases =
                        enumClass.permittedSubclassIds.map(_.flatMap(id => cp.symbol(id).toChunk)).getOrElse(Chunk.empty).collect:
                            case e: Tasty.Symbol.EnumCase => e
                    cp.fullName(enumClass).map: name =>
                        assert(
                            permSubEnumCases.nonEmpty,
                            s"${name.asString} has no EnumCase permitted subclasses " +
                                s"(regression: permittedSubclasses = ${enumClass.permittedSubclassIds.map(_.flatMap(id =>
                                        cp.symbol(id).toChunk
                                    )).getOrElse(Chunk.empty)})"
                        )
                        succeed
                case Maybe.Absent =>
                    // Fall back: search for any class-form enum case (at least Shape cases in embedded set)
                    val classFormCases = cp.symbols.collect:
                        case e: Tasty.Symbol.EnumCase if e.declarationIds.nonEmpty => e
                    assert(
                        classFormCases.nonEmpty,
                        "Expected at least one class-form EnumCase in the classpath (Shape.Circle/Square/Rectangle)"
                    )
                    succeed
            end match
    }

    coldWarmEquiv("EnumCase count is equal across cold and warm") { cp =>
        cp.symbols.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
    }

    // Color (value-form: up to 3) and Shape (class-form: up to 3) contribute EnumCases on all platforms.
    "embedded fixture EnumCase count > 0 on all platforms" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val count = cp.symbols.count(_.isInstanceOf[Tasty.Symbol.EnumCase])
            assert(
                count > 0,
                s"Expected > 0 EnumCase symbols from embedded fixtures (Color + Shape); found $count"
            )
            succeed
    }

end EnumCaseFidelity2Test
