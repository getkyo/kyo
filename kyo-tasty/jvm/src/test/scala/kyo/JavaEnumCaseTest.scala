package kyo

/** Verifies that Java enum constants on the jrt:/ platform-modules classpath decode as Symbol.EnumCase (not Symbol.Field with EnumFlag).
  * Uses java.lang.annotation.RetentionPolicy as the representative Java enum.
  */
class JavaEnumCaseTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "Java enum constants (RetentionPolicy) are Symbol.EnumCase" in {
        // Decode only RetentionPolicy from java.base instead of the whole module (~7,000 classfiles): this leaf
        // inspects a single Java enum, so a class-scoped jrt:/ load keeps it fast and off the shared cold-load path.
        val loadRetentionPolicy =
            Tasty.Classpath.initWithPlatformModulesFiltered(
                Seq.empty,
                Set("java.base"),
                Set("java.lang.annotation.RetentionPolicy")
            )
        loadRetentionPolicy.map { classpath =>
            classpath.findClass("java.lang.annotation.RetentionPolicy") match
                case Maybe.Present(rp) =>
                    val allDecls = rp.declarationIds.flatMap(id => classpath.symbol(id).toChunk)
                    val enumCaseDecls = allDecls.collect {
                        case e: Tasty.Symbol.EnumCase => e
                    }
                    assert(
                        enumCaseDecls.nonEmpty,
                        s"java.lang.annotation.RetentionPolicy has no EnumCase declarations. " +
                            s"All declarations: ${allDecls.toList.take(5).map(d =>
                                    d.name.asString + ":" + d.getClass.getSimpleName
                                ).mkString(", ")}"
                    )
                    val expectedNames = Set("RUNTIME", "CLASS", "SOURCE")
                    val foundNames    = enumCaseDecls.map(_.name.asString).toSet
                    val missing       = expectedNames -- foundNames
                    assert(
                        missing.isEmpty,
                        s"RetentionPolicy EnumCase declarations missing: ${missing.mkString(", ")}; found: ${foundNames.mkString(", ")}"
                    )
                    succeed
                case Maybe.Absent =>
                    fail("java.lang.annotation.RetentionPolicy not found on platform-modules classpath")
            end match
        }
    }

end JavaEnumCaseTest
