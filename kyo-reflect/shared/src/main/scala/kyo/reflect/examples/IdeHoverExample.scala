package kyo.reflect.examples

import kyo.*
import kyo.Reflect.*

/** IDE-style symbol query: look up a class by FQN, find a member, render hover text. */
object IdeHoverExample:

    /** Returns the hover string for `fqn.member`, or Absent if either lookup fails. */
    def hover(fqn: String, member: String)(using Frame): Maybe[String] < (Sync & Abort[ReflectError] & Scope) =
        for
            cp     <- Reflect.Classpath.openCached(Seq("."), cacheDir = ".kyo-reflect-cache")
            clsOpt <- cp.findClass(fqn)
            out <- clsOpt match
                case Absent => Sync.defer(Absent: Maybe[String])
                case Present(cls) =>
                    cls.declarations.flatMap { decls =>
                        Maybe.fromOption(decls.find(_.name.asString == member)) match
                            case Absent     => Sync.defer(Absent: Maybe[String])
                            case Present(s) => s.declaredType.map(t => Present(s"${s.name.asString}: ${t.show}"))
                    }
        yield out

    /** "Find all sealed classes in this classpath" composed query. */
    def findSealed(using Frame): Chunk[Reflect.Symbol] < (Sync & Abort[ReflectError] & Scope) =
        for
            cp      <- Reflect.Classpath.openCached(Seq("."), cacheDir = ".kyo-reflect-cache")
            classes <- cp.topLevelClasses
        yield classes.filter(_.flags.contains(Reflect.Flag.Sealed))

end IdeHoverExample
