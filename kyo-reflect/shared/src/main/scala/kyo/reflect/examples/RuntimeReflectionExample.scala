package kyo.reflect.examples

import kyo.*
import kyo.Reflect.*

/** Runtime reflection replacement: extract field information about a known Scala class without loading it.
  *
  * Replaces use cases that would otherwise reach for `scala.reflect.runtime` or `java.lang.reflect`. Compile-time type info from TASTy
  * means no runtime classloading; works on Scala Native and Scala.js where standard reflection isn't fully available.
  *
  * Updated for v3 Phase 3: findClass, declarations, parents, and declaredType are now pure values.
  */
object RuntimeReflectionExample:

    /** Returns the public val fields of `A` as `(name, type)` pairs. */
    def fieldsOf[A](using t: Tag[A], frame: Frame): Chunk[(String, Reflect.Type)] < (Sync & Async & Abort[ReflectError] & Scope) =
        val fqn = Reflect.classFqn[A]
        for
            cp  <- Reflect.Classpath.openCached(Seq("."), cacheDir = ".kyo-reflect-cache")
            cls <- requireFound(cp.findClass(fqn), fqn)
        yield
            val decls = cls.declarations
            val vals  = decls.filter(_.kind == Reflect.SymbolKind.Val)
            vals.map(f => (f.name.asString, f.declaredType))
        end for
    end fieldsOf

    /** Returns a structural type summary of a known type for debugging or printing. */
    def describe[A](using t: Tag[A], frame: Frame): String < (Sync & Async & Abort[ReflectError] & Scope) =
        val fqn = Reflect.classFqn[A]
        for
            cp  <- Reflect.Classpath.openCached(Seq("."), cacheDir = ".kyo-reflect-cache")
            cls <- requireFound(cp.findClass(fqn), fqn)
        yield
            val parents = cls.parents
            s"$fqn extends ${parents.map(_.show).mkString(" with ")}"
        end for
    end describe

    private def requireFound(m: Maybe[Reflect.Symbol], fqn: String)(using Frame): Reflect.Symbol < (Sync & Abort[ReflectError]) =
        m match
            case Present(s) => Sync.defer(s)
            case Absent     => Abort.fail(ReflectError.SymbolNotFound(fqn))

end RuntimeReflectionExample
