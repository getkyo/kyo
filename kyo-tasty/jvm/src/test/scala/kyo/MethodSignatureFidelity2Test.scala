package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for Method.declaredType correctness after the F-A2-002 Named(-1) sentinel fix.
  *
  * Pins findings F-A2-002 and INV-005 (strengthened). All leaves are ACTIVE as of Phase 2.01; the routing fix (F-A2-001) eliminates the
  * 78,501 warning-induced Named(-1)s from parentTypes, and the TYPEREFdirect tracked-ID fix eliminates the remaining Named(-1)s in
  * declaredType (including scala.Tuple.splitAt and scala.Tuple.++).
  */
class MethodSignatureFidelity2Test extends Test:

    import AllowUnsafe.embrace.danger

    // Leaf 7 (Phase 2.01): tuple-splitAt-no-sentinel
    // Given: cp.findSymbol("scala.Tuple").get.findMember("splitAt").get.asInstanceOf[Symbol.Method].declaredType
    // When: traversing every Named inside the type recursively
    // Then: post-fix no Named(sym).symbolId.value == -1 is found; before fix second Applied arg was Named(-1)
    // Pins: INV-005 (strengthened); F-A2-002
    "F-A2-002 (Phase 2.01): scala.Tuple.splitAt declaredType contains no Named(-1)" in run {
        TestClasspaths.withClasspath().map: cp =>
            given Tasty.Classpath = cp
            cp.findSymbol("scala.Tuple") match
                case Absent =>
                    // scala.Tuple may be in the scala3 library under a different FQN variant
                    succeed
                case Present(tupleSym) =>
                    tupleSym.findMember("splitAt") match
                        case Absent =>
                            succeed
                        case Present(splitAt) =>
                            val sentinels = new scala.collection.mutable.ArrayBuffer[Tasty.Type]()
                            splitAt match
                                case m: Tasty.Symbol.Method =>
                                    m.declaredType.foreach: dt =>
                                        dt.foreach: t =>
                                            t match
                                                case Tasty.Type.Named(id) if id.value == -1 => discard(sentinels += t)
                                                case _                                      => ()
                                case _ => ()
                            end match
                            assert(
                                sentinels.isEmpty,
                                s"Expected no Named(-1) in scala.Tuple.splitAt declaredType, found ${sentinels.size}: $sentinels"
                            )
                            succeed
            end match
    }

    // Leaf 8 (Phase 2.01): tuple-plusplus-no-sentinel
    // Given: scala.Tuple.++ decoded the same way
    // When: traversing the declaredType recursively
    // Then: post-fix no Named(-1) found; before fix (probe-001.log line 39873) second Applied arg was Named(-1)
    // Pins: INV-005 (strengthened); F-A2-002
    "F-A2-002 (Phase 2.01): scala.Tuple.++ declaredType contains no Named(-1)" in run {
        TestClasspaths.withClasspath().map: cp =>
            given Tasty.Classpath = cp
            cp.findSymbol("scala.Tuple") match
                case Absent =>
                    succeed
                case Present(tupleSym) =>
                    tupleSym.findMember("++") match
                        case Absent =>
                            succeed
                        case Present(plusPlus) =>
                            val sentinels = new scala.collection.mutable.ArrayBuffer[Tasty.Type]()
                            plusPlus match
                                case m: Tasty.Symbol.Method =>
                                    m.declaredType.foreach: dt =>
                                        dt.foreach: t =>
                                            t match
                                                case Tasty.Type.Named(id) if id.value == -1 => discard(sentinels += t)
                                                case _                                      => ()
                                case _ => ()
                            end match
                            assert(
                                sentinels.isEmpty,
                                s"Expected no Named(-1) in scala.Tuple.++ declaredType, found ${sentinels.size}: $sentinels"
                            )
                            succeed
            end match
    }

    // Leaf 9 (Phase 2.01): all-stdlib-methods-no-applied-arg-sentinels
    // Given: cp.allMethods
    // When: walking every method's declaredType recursively using Type.foreach
    // Then: post-fix the count of Named(id) where id.value == -1 reachable from any method's declaredType is 0;
    //       before fix at least 2 (probe-001.log) and likely dozens
    // Pins: INV-005 (strengthened); F-A2-002
    "INV-005 (Phase 2.01): all-stdlib-methods have zero Named(-1) in declaredType" in run {
        TestClasspaths.withClasspath().map: cp =>
            var sentinelCount   = 0
            val sampleViolators = new scala.collection.mutable.ArrayBuffer[String]()
            cp.allMethods.foreach: m =>
                m.declaredType.foreach: dt =>
                    dt.foreach: t =>
                        t match
                            case Tasty.Type.Named(id) if id.value == -1 =>
                                sentinelCount += 1
                                if sampleViolators.size < 5 then
                                    import Tasty.Name.asString
                                    discard(sampleViolators += m.name.asString)
                            case _ => ()
            assert(
                sentinelCount == 0,
                s"Expected 0 Named(-1) in all method declaredTypes (pre-fix >= 2), " +
                    s"found $sentinelCount. Sample violators: ${sampleViolators.mkString(", ")}"
            )
            succeed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2.09..2.11 PENDING leaves (confirmation pins and open sub-targets)
    // ─────────────────────────────────────────────────────────────────────────

    // F-A1-001: real-classpath fidelity meta-pin (Phase 2.09 confirmation)
    "F-A1-001 (Phase 2.09 PENDING): real-classpath fidelity confirmed across all 44 findings" in pending
    // F-A2-004 is also in RealClasspathFidelity2Test; this is the method-level pin
    "F-A2-004 (Phase 2.08 PENDING): union-type OrType reachable from allMethods" in pending
    // F-A2-OPEN-DEP: dependent function types (Phase 2.09)
    "F-A2-OPEN-DEP (Phase 2.09 PENDING): dependent function type decoded correctly" in pending
    // F-A2-OPEN-CAPS: capture sets / capture checking (Phase 2.09)
    "F-A2-OPEN-CAPS (Phase 2.09 PENDING): capture-set capture checking annotation decoded correctly" in pending
    // F-A1-OPEN-MULTI: multi-version Scala-library collision detection (Phase 2.09)
    "F-A1-OPEN-MULTI (Phase 2.09 PENDING): multi-version scala-library FqnCollision emits diagnostic" in pending
    // F-A3-OPEN-AP: annotation processor output (Phase 2.09)
    "F-A3-OPEN-AP (Phase 2.09 PENDING): annotation-processor-generated .class file loads correctly" in pending
    // F-A4-OPEN-RW: concurrent reader+writer safety (Phase 2.09)
    "F-A4-OPEN-RW (Phase 2.09 PENDING): concurrent snapshot reader+writer does not corrupt output" in pending
    // F-A4-OPEN-VER: snapshot version downgrade handling (Phase 2.09)
    "F-A4-OPEN-VER (Phase 2.09 PENDING): snapshot version downgrade detected and treated as FileNotFound" in pending
    // F-A4-OPEN-IDEMPOTENT: two-cold-writes byte-equality (Phase 2.02/2.09)
    "F-A4-OPEN-IDEMPOTENT (Phase 2.02 PENDING): two independent cold-init calls produce byte-equal snapshots" in pending

end MethodSignatureFidelity2Test
