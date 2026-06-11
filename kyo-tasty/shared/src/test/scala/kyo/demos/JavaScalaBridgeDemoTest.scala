package kyo.demos

import kyo.*
import kyo.Tasty.*
import kyo.internal.TestClasspaths

/** Cross-language symbol lookup: the same accessor surface works for Java and Scala classes.
  *
  * Java symbols carry `Flag.JavaDefined` (`isJava == true`); Scala symbols do not. Otherwise the query surface
  * (`findClass`, `fullName`, `parentTypes`, `declarationIds`) is identical. The assertions pin the summary shape and
  * the `isJava` discriminator for a Scala fixture, which is resolvable on JVM, JS, and Native.
  */
class JavaScalaBridgeDemoTest extends kyo.test.Test[Any]:

    final case class ClassSummary(
        name: String,
        isJava: Boolean,
        parents: Chunk[String],
        members: Chunk[String]
    )

    private def summarize(classpath: Tasty.Classpath, fullName: String): Maybe[ClassSummary] =
        classpath.findClass(fullName) match
            case Absent => Maybe.Absent
            case Present(cls) =>
                val fullNameVal = classpath.fullName(cls)
                val parents     = cls.parentTypes.map(_.toString)
                val decls       = cls.declarationIds.flatMap(id => classpath.symbol(id).toChunk)
                Present(ClassSummary(
                    name = fullNameVal.asString,
                    isJava = cls.isJava,
                    parents = parents,
                    members = decls.map(_.name.asString)
                ))

    "summarize a Scala class via the bridge surface" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            summarize(classpath, "kyo.fixtures.SomeCaseClass") match
                case Present(summary) =>
                    assert(
                        summary.name == "kyo.fixtures.SomeCaseClass",
                        s"Expected full name 'kyo.fixtures.SomeCaseClass', got '${summary.name}'"
                    )
                    assert(!summary.isJava, "SomeCaseClass is Scala-defined; isJava must be false")
                    assert(
                        summary.members.contains("name") && summary.members.contains("count"),
                        s"Expected members 'name' and 'count', got: ${summary.members.mkString(", ")}"
                    )
                    succeed
                case Absent =>
                    fail("summarize(kyo.fixtures.SomeCaseClass) returned Absent")
        }
    }

    "an absent class summarizes to Absent" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            assert(
                summarize(classpath, "kyo.fixtures.DoesNotExist").isEmpty,
                "summarize of a non-existent class must return Absent"
            )
            succeed
        }
    }

end JavaScalaBridgeDemoTest
