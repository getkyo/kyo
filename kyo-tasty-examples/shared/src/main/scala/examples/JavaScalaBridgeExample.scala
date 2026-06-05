package examples

import kyo.*
import kyo.Tasty.*

/** Cross-language symbol lookup: same API works for `java.util.HashMap` and `scala.collection.mutable.HashMap`.
  *
  * Java symbols carry `Flag.JavaDefined` and have `javaSpecific` populated; Scala symbols don't. Otherwise the surface is identical.
  *
  * Updated for carry A8: Tasty.withClasspath replaces Classpath.initCached. declarations uses declarationIds;
  * fullName uses Tasty.fullName (effectful); parent types use parentTypes directly.
  */
object JavaScalaBridgeExample:

    final case class ClassSummary(
        name: String,
        isJava: Boolean,
        parents: Chunk[String],
        members: Chunk[String]
    )

    def summarize(fqn: String)(using Frame): Maybe[ClassSummary] < (Sync & Async & Abort[TastyError]) =
        // Unsafe: Symbol accessors require AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        Tasty.withClasspath(Seq("."), Maybe.Present(".kyo-tasty-cache")):
            for
                cp <- Tasty.classpath
                result <- cp.findClass(fqn) match
                    case Absent => Sync.defer(Maybe.Absent)
                    case Present(cls) =>
                        cp.fullName(cls).map: fullNameVal =>
                            given Classpath = cp
                            val parents     = cls.parentTypes.map(_.toString)
                            val decls       = cls.declarationIds.flatMap(id => cp.symbol(id).toChunk)
                            Present(ClassSummary(
                                name = fullNameVal.asString,
                                isJava = cls.isJava,
                                parents = parents,
                                members = decls.map(_.name.asString)
                            ))
            yield result
    end summarize

    /** Compare a Java class and its Scala counterpart side-by-side. */
    def compare(javaFqn: String, scalaFqn: String)(using Frame): String < (Sync & Async & Abort[TastyError]) =
        for
            javaSummary  <- summarize(javaFqn)
            scalaSummary <- summarize(scalaFqn)
        yield (javaSummary, scalaSummary) match
            case (Present(j), Present(s)) =>
                s"""Java   ${j.name} (members: ${j.members.size})
                   |Scala  ${s.name} (members: ${s.members.size})""".stripMargin
            case (Present(j), _) => s"Java ${j.name} found; Scala $scalaFqn not in classpath"
            case (_, Present(s)) => s"Scala ${s.name} found; Java $javaFqn not in classpath"
            case _               => s"Neither $javaFqn nor $scalaFqn found"

end JavaScalaBridgeExample
