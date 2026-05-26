package kyo.reflect.examples

import kyo.*
import kyo.Reflect.*

/** IDE-style symbol query: look up a class by FQN, find a member, render hover text.
  *
  * Updated for v3 Phase 3: findClass, declarations, and declaredType are now pure values. The for-comprehension no longer threads effects
  * through these calls.
  */
object IdeHoverExample:

    /** Returns the hover string for `fqn.member`, or Absent if either lookup fails. */
    def hover(fqn: String, member: String)(using Frame): Maybe[String] < (Sync & Async & Abort[ReflectError] & Scope) =
        for
            cp <- Reflect.Classpath.openCached(Seq("."), cacheDir = ".kyo-reflect-cache")
        yield cp.findClass(fqn) match
            case Absent => Absent
            case Present(cls) =>
                Maybe.fromOption(cls.declarations.find(_.name.asString == member)) match
                    case Absent     => Absent
                    case Present(s) => Present(s"${s.name.asString}: ${s.declaredType.show}")

    /** "Find all sealed classes in this classpath" composed query. */
    def findSealed(using Frame): Chunk[Reflect.Symbol] < (Sync & Async & Abort[ReflectError] & Scope) =
        for
            cp <- Reflect.Classpath.openCached(Seq("."), cacheDir = ".kyo-reflect-cache")
        yield cp.topLevelClasses.filter(_.flags.contains(Reflect.Flag.Sealed))

end IdeHoverExample
