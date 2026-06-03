package kyo

/** Property tests: every .tasty file in the embedded fixture set decodes without sentinels or unknown tags.
  *
  * Probes:
  *   - Zero unknown tags: no `TastyError.UnknownTagInPosition` in cp.errors.
  *   - Zero Named(-1): no method declaredType reachable from cp.allMethods carries SymbolId(-1).
  *
  * Cross-platform: uses the embedded fixture set via TestClasspaths.withClasspath(). No JVM filesystem required.
  *
  * Proposal 5 of Phase 2.04-strict (HARD RULE 13).
  */
class TastyPropertyTest extends Test:

    import AllowUnsafe.embrace.danger

    // Leaf 1: embedded fixture .tasty files decode with zero unknown-tag errors
    "PROP-001: embedded fixture .tasty files decode with zero UnknownTagInPosition errors" in run {
        kyo.internal.TestClasspaths.withClasspath().map: cp =>
            val unknownTag = cp.errors.collect:
                case TastyError.UnknownTagInPosition(tag, pos) =>
                    s"tag=$tag position=$pos"
            assert(
                unknownTag.isEmpty,
                s"Embedded fixture set produced UnknownTagInPosition errors: ${unknownTag.take(3).mkString(", ")}"
            )
            succeed
    }

    // Leaf 2: embedded fixture: zero Named(-1) in allMethods declaredType
    "PROP-002: embedded fixture: zero Named(-1) in allMethods declaredType" in run {
        kyo.internal.TestClasspaths.withClasspath().map: cp =>
            import kyo.Tasty.SymbolId.value as idValue
            var sentinelCount   = 0
            val sampleViolators = new scala.collection.mutable.ArrayBuffer[String]()
            cp.allMethods.foreach: m =>
                m.declaredType.foreach: dt =>
                    dt.foreach:
                        case Tasty.Type.Named(id) if idValue(id) == -1 =>
                            sentinelCount += 1
                            if sampleViolators.size < 5 then
                                import Tasty.Name.asString
                                discard(sampleViolators += m.name.asString)
                        case _ => ()
            assert(
                sentinelCount == 0,
                s"Embedded fixture set: found $sentinelCount Named(-1) sentinels in allMethods declaredType. " +
                    s"Sample: ${sampleViolators.mkString(", ")}"
            )
            succeed
    }

end TastyPropertyTest
