package kyo.reflect.examples

import kyo.*
import kyo.Reflect.*

/** Cross-language symbol lookup: same API works for `java.util.HashMap` and `scala.collection.mutable.HashMap`.
  *
  * Java symbols carry `Flag.JavaDefined` and have `javaSpecific` populated; Scala symbols don't. Otherwise the surface is identical.
  *
  * Updated for v3 Phase 3: findClass, parents, and declarations are now pure values.
  */
object JavaScalaBridgeExample:

    final case class ClassSummary(
        name: String,
        isJava: Boolean,
        parents: Chunk[String],
        members: Chunk[String]
    )

    def summarize(fqn: String)(using Frame): Maybe[ClassSummary] < (Sync & Async & Abort[ReflectError] & Scope) =
        for
            cp <- Reflect.Classpath.openCached(Seq("."), cacheDir = ".kyo-reflect-cache")
        yield cp.findClass(fqn) match
            case Absent => Absent
            case Present(cls) =>
                val parents = cls.parents
                val decls   = cls.declarations
                Present(ClassSummary(
                    name = cls.fullName.asString,
                    isJava = cls.isJava,
                    parents = parents.map(_.show),
                    members = decls.map(_.name.asString)
                ))

    /** Compare a Java class and its Scala counterpart side-by-side. */
    def compare(javaFqn: String, scalaFqn: String)(using Frame): String < (Sync & Async & Abort[ReflectError] & Scope) =
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
