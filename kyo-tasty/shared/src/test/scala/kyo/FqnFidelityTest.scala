package kyo

import kyo.internal.TestClasspaths
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TypeUnpickler
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Fidelity tests for FQN computation correctness.
  *
  * Exercises `ClasspathOrchestrator.computeFqn` (halt walk at Package owners) and `TypeUnpickler`
  * (TYPEREFin always concatenates when qual is non-empty).
  *
  * Uses embedded fixture FQNs (kyo.fixtures.PlainClass, kyo.fixtures.SomeCaseClass,
  * kyo.fixtures.SomeObject) instead of stdlib classes, so all cases run cross-platform.
  */
class FqnFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "fqnIndex contains no doubled-package-segment keys" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val doubled = cp.indices.byFqn.toMap.keys.filter: k =>
                k.contains("scala.scala") || k.contains("kyo.kyo") || k.startsWith("<empty>.")
            assert(
                doubled.isEmpty,
                s"fqnIndex contained ${doubled.size} doubled-segment key(s): ${doubled.take(5).mkString(", ")}"
            )
            succeed
    }

    "cp.findClassLike(kyo.fixtures.PlainClass) returns Present" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            cp.findClassLike("kyo.fixtures.PlainClass") match
                case Present(sym) =>
                    assert(
                        sym.name.asString == "PlainClass",
                        s"Expected simple name 'PlainClass', got '${sym.name.asString}'"
                    )
                    succeed
                case Absent =>
                    val matching = cp.indices.byFqn.toMap.keys
                        .filter(k => k.contains("PlainClass") && k.contains("fixtures"))
                        .toSeq
                        .sorted
                        .take(3)
                    fail(
                        s"cp.findClassLike(kyo.fixtures.PlainClass) returned Absent. " +
                            s"Matching keys in fqnIndex: ${matching.mkString(", ")}"
                    )
    }

    "cp.findClassLike(kyo.fixtures.SomeCaseClass) and cp.findClassLike(kyo.fixtures.SomeTrait) return Present" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val caseClassResult = cp.findClassLike("kyo.fixtures.SomeCaseClass")
            val traitResult     = cp.findClassLike("kyo.fixtures.SomeTrait")
            val caseClassKeys   = cp.indices.byFqn.toMap.keys.filter(_.contains("SomeCaseClass")).toSeq.sorted.take(3)
            val traitKeys = cp.indices.byFqn.toMap.keys.filter(k => k.contains("SomeTrait") && k.startsWith("kyo")).toSeq.sorted.take(3)
            assert(
                caseClassResult.isDefined,
                s"cp.findClassLike(kyo.fixtures.SomeCaseClass) returned Absent. Related fqnIndex keys: ${caseClassKeys.mkString(", ")}"
            )
            assert(
                traitResult.isDefined,
                s"cp.findClassLike(kyo.fixtures.SomeTrait) returned Absent. Related fqnIndex keys: ${traitKeys.mkString(", ")}"
            )
            succeed
    }

    // TYPEREFin must concatenate even when qual FQN and simpleName are the same (Map.Map scenario).
    "TYPEREFin preserves legitimate same-name qualifications (Map.Map)" in {
        // Build a synthetic TYPEREFin where the qual FQN and simpleName both equal "Map".
        // Nat encoding: value n < 128 -> (n | 0x80).toByte (last byte with stop-bit set).
        def encNat(n: Int): Array[Byte] =
            if n < 128 then Array((n | 0x80).toByte)
            else Array((n >> 7).toByte, ((n & 0x7f) | 0x80).toByte)
        val names = Array(Tasty.Name("scala"), Tasty.Name("Map"))
        // qual = TYPEREFpkg nameRef=1 -> "Map"; tracked by the session as qualFqn="Map"
        // ns = TYPEREFpkg nameRef=1 (namespace, ignored in FQN reconstruction)
        val qualBytes    = TastyFormat.TYPEREFpkg.toByte +: encNat(1)
        val nsBytes      = TastyFormat.TYPEREFpkg.toByte +: encNat(1)
        val innerPayload = encNat(1) ++ qualBytes ++ nsBytes // nameRef=1 -> simpleName="Map"
        val fullBytes    = TastyFormat.TYPEREFin.toByte +: (encNat(innerPayload.length) ++ innerPayload)
        val arena        = TypeArena.canonical()
        val session      = new TypeUnpickler.DecodeSession(names, new mutable.HashMap(), arena)
        val view         = ByteView(fullBytes)
        Abort.run[TastyError]:
            Sync.defer:
                val decoded = TypeUnpickler.readTypeIntoSession(view, session)
                decoded match
                    case Tasty.Type.Named(sid) =>
                        import kyo.Tasty.SymbolId.value
                        val fqn = session.unresolvedIdToFqn.getOrElse(
                            sid.value,
                            s"<not-found; id=${sid.value}>"
                        )
                        assert(
                            fqn == "Map.Map",
                            s"Expected FQN 'Map.Map' but got '$fqn'."
                        )
                    case other =>
                        fail(s"Expected Named type from TYPEREFin decode but got: $other")
                end match
        .map:
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"TYPEREFin decode failed: $e")
            case Result.Panic(t)   => throw t
    }

    "cp.findSymbol(kyo.fixtures.SomeObject) returns Present(Symbol.Object)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            cp.findSymbol("kyo.fixtures.SomeObject") match
                case Present(sym: Tasty.Symbol.Object) =>
                    succeed
                case Present(sym) =>
                    fail(
                        s"cp.findSymbol(kyo.fixtures.SomeObject) returned Present but wrong kind: ${sym.kind}. " +
                            s"Expected Symbol.Object."
                    )
                case Absent =>
                    val related = cp.indices.byFqn.toMap.keys
                        .filter(k => k.startsWith("kyo.fixtures.SomeObject"))
                        .toSeq
                        .sorted
                        .take(5)
                    fail(
                        s"cp.findSymbol(kyo.fixtures.SomeObject) returned Absent. " +
                            s"Related fqnIndex keys: ${related.mkString(", ")}"
                    )
    }

    "cp.findSymbol(kyo.fixtures.SomeObject$) still returns Present (binary key preserved)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            cp.findSymbol("kyo.fixtures.SomeObject$") match
                case Present(_) =>
                    succeed
                case Absent =>
                    fail(
                        s"cp.findSymbol(kyo.fixtures.SomeObject$$) returned Absent; the primary binary key must remain indexed."
                    )
    }

    "kyo.fixtures.SomeObject and kyo.fixtures.SomeObject$ resolve to the same Symbol id" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val sourceLookup = cp.findSymbol("kyo.fixtures.SomeObject")
            val binaryLookup = cp.findSymbol("kyo.fixtures.SomeObject$")
            (sourceLookup, binaryLookup) match
                case (Present(s1), Present(s2)) =>
                    assert(
                        s1.id == s2.id,
                        s"Both keys resolve but to DIFFERENT symbols: source id=${s1.id}, binary id=${s2.id}. " +
                            s"Dual-index must point both keys to the same Symbol instance."
                    )
                    succeed
                case (Absent, _) =>
                    val related = cp.indices.byFqn.toMap.keys
                        .filter(k => k.contains("SomeObject") && k.startsWith("kyo"))
                        .toSeq
                        .sorted
                        .take(5)
                    fail(
                        s"cp.findSymbol(kyo.fixtures.SomeObject) returned Absent (source FQN not indexed). " +
                            s"Related keys: ${related.mkString(", ")}"
                    )
                case (_, Absent) =>
                    fail(
                        s"cp.findSymbol(kyo.fixtures.SomeObject$$) returned Absent; the primary binary key " +
                            s"must remain indexed."
                    )
                case (_, _) =>
                    fail(s"Unexpected match: sourceLookup=$sourceLookup binaryLookup=$binaryLookup")
            end match
    }

    "cp.findSymbol(nonexistent.Type) and cp.findSymbol(nonexistent.Type$) return Absent" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val r1 = cp.findSymbol("nonexistent.Type")
            val r2 = cp.findSymbol("nonexistent.Type$")
            assert(
                r1.isEmpty,
                s"cp.findSymbol(nonexistent.Type) returned Present; dual-index fabricated a spurious entry."
            )
            assert(
                r2.isEmpty,
                s"cp.findSymbol(nonexistent.Type$$) returned Present; dual-index fabricated a spurious entry."
            )
            succeed
    }

end FqnFidelityTest
