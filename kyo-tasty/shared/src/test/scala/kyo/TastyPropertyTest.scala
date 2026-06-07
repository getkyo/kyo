package kyo

/** Property tests: every .tasty file in the embedded fixture set decodes without sentinels or unknown tags.
  *
  * Verifies zero UnknownTagInPosition errors in cp.errors and zero Named(-1) sentinels in allMethods
  * declaredTypes. Cross-platform via the embedded fixture set.
  */
class TastyPropertyTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // embedded fixture.tasty files decode with zero unknown-tag errors
    "embedded fixture .tasty files decode with zero UnknownTagInPosition errors" in {
        kyo.internal.TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val unknownTag = cp.errors.collect:
                case TastyError.UnknownTagInPosition(tag, pos) =>
                    s"tag=$tag position=$pos"
            assert(
                unknownTag.isEmpty,
                s"Embedded fixture set produced UnknownTagInPosition errors: ${unknownTag.take(3).mkString(", ")}"
            )
            succeed
    }

    // embedded fixture: zero Named(-1) in allMethods declaredType
    "embedded fixture: zero Named(-1) in allMethods declaredType" in {
        kyo.internal.TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
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
