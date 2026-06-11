package kyo

import kyo.internal.TestClasspaths
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TypeUnpickler
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Fidelity tests for fully-qualified name computation correctness.
  *
  * Exercises `ClasspathOrchestrator.computeFullName` (halt walk at Package owners) and `TypeUnpickler`
  * (TYPEREFin always concatenates when qual is non-empty).
  *
  * Uses embedded fixture fully-qualified names (kyo.fixtures.PlainClass, kyo.fixtures.SomeCaseClass,
  * kyo.fixtures.SomeObject) instead of stdlib classes, so all cases run cross-platform.
  */
class FullNameFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "fullNameIndex contains no doubled-package-segment keys" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val doubled = classpath.indices.byFullName.toMap.keys.filter { k =>
                k.contains("scala.scala") || k.contains("kyo.kyo") || k.startsWith("<empty>.")
            }
            assert(
                doubled.isEmpty,
                s"fullNameIndex contained ${doubled.size} doubled-segment key(s): ${doubled.take(5).mkString(", ")}"
            )
            succeed
        }
    }

    "classpath.findClassLike(kyo.fixtures.PlainClass) returns Present" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findClassLike("kyo.fixtures.PlainClass") match
                case Present(symbol) =>
                    assert(
                        symbol.name.asString == "PlainClass",
                        s"Expected simple name 'PlainClass', got '${symbol.name.asString}'"
                    )
                    succeed
                case Absent =>
                    val matching = classpath.indices.byFullName.toMap.keys
                        .filter(k => k.contains("PlainClass") && k.contains("fixtures"))
                        .toSeq
                        .sorted
                        .take(3)
                    fail(
                        s"classpath.findClassLike(kyo.fixtures.PlainClass) returned Absent. " +
                            s"Matching keys in fullNameIndex: ${matching.mkString(", ")}"
                    )
        }
    }

    "classpath.findClassLike(kyo.fixtures.SomeCaseClass) and classpath.findClassLike(kyo.fixtures.SomeTrait) return Present" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val caseClassResult = classpath.findClassLike("kyo.fixtures.SomeCaseClass")
            val traitResult     = classpath.findClassLike("kyo.fixtures.SomeTrait")
            val caseClassKeys   = classpath.indices.byFullName.toMap.keys.filter(_.contains("SomeCaseClass")).toSeq.sorted.take(3)
            val traitKeys =
                classpath.indices.byFullName.toMap.keys.filter(k => k.contains("SomeTrait") && k.startsWith("kyo")).toSeq.sorted.take(3)
            assert(
                caseClassResult.isDefined,
                s"classpath.findClassLike(kyo.fixtures.SomeCaseClass) returned Absent. Related fullNameIndex keys: ${caseClassKeys.mkString(", ")}"
            )
            assert(
                traitResult.isDefined,
                s"classpath.findClassLike(kyo.fixtures.SomeTrait) returned Absent. Related fullNameIndex keys: ${traitKeys.mkString(", ")}"
            )
            succeed
        }
    }

    // TYPEREFin must concatenate even when qual fully-qualified name and simpleName are the same (Map.Map scenario).
    "TYPEREFin preserves legitimate same-name qualifications (Map.Map)" in {
        // Build a synthetic TYPEREFin where the qual fully-qualified name and simpleName both equal "Map".
        // Nat encoding: value n < 128 -> (n | 0x80).toByte (last byte with stop-bit set).
        def encNat(n: Int): Array[Byte] =
            if n < 128 then Array((n | 0x80).toByte)
            else Array((n >> 7).toByte, ((n & 0x7f) | 0x80).toByte)
        val names = Array(Tasty.Name("scala"), Tasty.Name("Map"))
        // qual = TYPEREFpkg nameRef=1 -> "Map"; tracked by the session as qualFullName="Map"
        // ns = TYPEREFpkg nameRef=1 (namespace, ignored in fully-qualified name reconstruction)
        val qualBytes    = TastyFormat.TYPEREFpkg.toByte +: encNat(1)
        val nsBytes      = TastyFormat.TYPEREFpkg.toByte +: encNat(1)
        val innerPayload = encNat(1) ++ qualBytes ++ nsBytes // nameRef=1 -> simpleName="Map"
        val fullBytes    = TastyFormat.TYPEREFin.toByte +: (encNat(innerPayload.length) ++ innerPayload)
        val arena        = TypeArena.canonical()
        val session      = new TypeUnpickler.DecodeSession(names, new mutable.HashMap(), arena)
        val view         = ByteView(fullBytes)
        Abort.run[TastyError] {
            Sync.defer {
                val decoded = TypeUnpickler.readTypeIntoSession(view, session)
                decoded match
                    case Tasty.Type.Named(sid) =>
                        import kyo.Tasty.SymbolId.value
                        val fullName = session.unresolvedIdToFullName.getOrElse(
                            sid.value,
                            s"<not-found; id=${sid.value}>"
                        )
                        assert(
                            fullName == "Map.Map",
                            s"Expected fully-qualified name 'Map.Map' but got '$fullName'."
                        )
                    case other =>
                        fail(s"Expected Named type from TYPEREFin decode but got: $other")
                end match
            }
        }
            .map {
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"TYPEREFin decode failed: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "classpath.findSymbol(kyo.fixtures.SomeObject) returns Present(Symbol.Object)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findSymbol("kyo.fixtures.SomeObject") match
                case Present(symbol: Tasty.Symbol.Object) =>
                    succeed
                case Present(symbol) =>
                    fail(
                        s"classpath.findSymbol(kyo.fixtures.SomeObject) returned Present but wrong kind: ${symbol.kind}. " +
                            s"Expected Symbol.Object."
                    )
                case Absent =>
                    val related = classpath.indices.byFullName.toMap.keys
                        .filter(k => k.startsWith("kyo.fixtures.SomeObject"))
                        .toSeq
                        .sorted
                        .take(5)
                    fail(
                        s"classpath.findSymbol(kyo.fixtures.SomeObject) returned Absent. " +
                            s"Related fullNameIndex keys: ${related.mkString(", ")}"
                    )
        }
    }

    "classpath.findSymbol(kyo.fixtures.SomeObject$) still returns Present (binary key preserved)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findSymbol("kyo.fixtures.SomeObject$") match
                case Present(_) =>
                    succeed
                case Absent =>
                    fail(
                        s"classpath.findSymbol(kyo.fixtures.SomeObject$$) returned Absent; the primary binary key must remain indexed."
                    )
        }
    }

    "kyo.fixtures.SomeObject and kyo.fixtures.SomeObject$ resolve to the same Symbol id" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val sourceLookup = classpath.findSymbol("kyo.fixtures.SomeObject")
            val binaryLookup = classpath.findSymbol("kyo.fixtures.SomeObject$")
            (sourceLookup, binaryLookup) match
                case (Present(s1), Present(s2)) =>
                    assert(
                        s1.id == s2.id,
                        s"Both keys resolve but to DIFFERENT symbols: source id=${s1.id}, binary id=${s2.id}. " +
                            s"Dual-index must point both keys to the same Symbol instance."
                    )
                    succeed
                case (Absent, _) =>
                    val related = classpath.indices.byFullName.toMap.keys
                        .filter(k => k.contains("SomeObject") && k.startsWith("kyo"))
                        .toSeq
                        .sorted
                        .take(5)
                    fail(
                        s"classpath.findSymbol(kyo.fixtures.SomeObject) returned Absent (source fully-qualified name not indexed). " +
                            s"Related keys: ${related.mkString(", ")}"
                    )
                case (_, Absent) =>
                    fail(
                        s"classpath.findSymbol(kyo.fixtures.SomeObject$$) returned Absent; the primary binary key " +
                            s"must remain indexed."
                    )
                case (_, _) =>
                    fail(s"Unexpected match: sourceLookup=$sourceLookup binaryLookup=$binaryLookup")
            end match
        }
    }

    "classpath.findSymbol(nonexistent.Type) and classpath.findSymbol(nonexistent.Type$) return Absent" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val r1 = classpath.findSymbol("nonexistent.Type")
            val r2 = classpath.findSymbol("nonexistent.Type$")
            assert(
                r1.isEmpty,
                s"classpath.findSymbol(nonexistent.Type) returned Present; dual-index fabricated a spurious entry."
            )
            assert(
                r2.isEmpty,
                s"classpath.findSymbol(nonexistent.Type$$) returned Present; dual-index fabricated a spurious entry."
            )
            succeed
        }
    }

end FullNameFidelityTest
