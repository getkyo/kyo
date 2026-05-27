package kyo.tasty.examples

import kyo.*
import kyo.Tasty.*

/** Runtime reflection replacement: extract field information about a known Scala class without loading it.
  *
  * Replaces use cases that would otherwise reach for `scala.reflect.runtime` or `java.lang.reflect`. Compile-time type info from TASTy
  * means no runtime classloading; works on Scala Native and Scala.js where standard reflection isn't fully available.
  *
  * Updated for v3 Phase 7: findClass, declarations, parents, and declaredType are pure values; no Sync.defer ceremony.
  */
object RuntimeReflectionExample:

    /** Returns the public val fields of `A` as `(name, type)` pairs. */
    def fieldsOf[A](using t: Tag[A], frame: Frame): Chunk[(String, Tasty.Type)] < (Sync & Async & Abort[TastyError] & Scope) =
        val fqn = Tasty.classFqn[A]
        for
            cp  <- Tasty.Classpath.openCached(Seq("."), cacheDir = ".kyo-tasty-cache")
            cls <- requireFound(cp.findClass(fqn), fqn)
        yield
            val decls = cls.declarations
            val vals  = decls.filter(_.kind == Tasty.SymbolKind.Val)
            vals.map(f => (f.name.asString, f.declaredType))
        end for
    end fieldsOf

    /** Returns a structural type summary of a known type for debugging or printing. */
    def describe[A](using t: Tag[A], frame: Frame): String < (Sync & Async & Abort[TastyError] & Scope) =
        val fqn = Tasty.classFqn[A]
        for
            cp  <- Tasty.Classpath.openCached(Seq("."), cacheDir = ".kyo-tasty-cache")
            cls <- requireFound(cp.findClass(fqn), fqn)
        yield
            val parents = cls.parents
            s"$fqn extends ${parents.map(_.show).mkString(" with ")}"
        end for
    end describe

    private def requireFound(m: Maybe[Tasty.Symbol], fqn: String)(using Frame): Tasty.Symbol < Abort[TastyError] =
        m match
            case Present(s) => s
            case Absent     => Abort.fail(TastyError.SymbolNotFound(fqn))

end RuntimeReflectionExample
