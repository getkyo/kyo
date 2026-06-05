package examples

import kyo.*
import kyo.Tasty.*

/** Runtime reflection replacement: extract field information about a known Scala class without loading it.
  *
  * Replaces use cases that would otherwise reach for `scala.reflect.runtime` or `java.lang.reflect`. Compile-time type info from TASTy
  * means no runtime classloading; works on Scala Native and Scala.js where standard reflection isn't fully available.
  *
  * Updated for carry A8: Tasty.withClasspath replaces Classpath.initCached. declarations uses declarationIds on ClassLike;
  * parent types use parentTypes directly on ClassLike.
  */
object RuntimeReflectionExample:

    /** Returns the public val fields of `A` as `(name, type)` pairs. */
    def fieldsOf[A](using t: Tag[A], frame: Frame): Chunk[(String, Tasty.Type)] < (Sync & Async & Abort[TastyError]) =
        // Unsafe: Symbol accessors require AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        val fqn = Tasty.classFqn[A]
        Tasty.withClasspath(Seq("."), Maybe.Present(".kyo-tasty-cache")):
            for
                cp  <- Tasty.classpath
                cls <- requireFoundClass(cp.findClass(fqn), fqn)
            yield
                val decls = cls.declarationIds.flatMap(id => cp.symbol(id).toChunk)
                val vals  = decls.collect { case v: Tasty.Symbol.Val => v }
                vals.map(f => (f.name.asString, f.declaredType.getOrElse(Tasty.Type.Nothing)))
    end fieldsOf

    /** Returns a structural type summary of a known type for debugging or printing. */
    def describe[A](using t: Tag[A], frame: Frame): String < (Sync & Async & Abort[TastyError]) =
        // Unsafe: Symbol.parents requires AllowUnsafe; embraced here at the example app boundary (§839 case 3).
        import AllowUnsafe.embrace.danger
        val fqn = Tasty.classFqn[A]
        Tasty.withClasspath(Seq("."), Maybe.Present(".kyo-tasty-cache")):
            for
                cp  <- Tasty.classpath
                cls <- requireFoundClass(cp.findClass(fqn), fqn)
            yield
                val parents = cls.parentTypes
                s"$fqn extends ${parents.map(_.toString).mkString(" with ")}"
    end describe

    private def requireFoundClass(m: Maybe[Tasty.Symbol.Class], fqn: String)(using Frame): Tasty.Symbol.Class < Abort[TastyError] =
        m match
            case Present(s) => s
            case Absent     => Abort.fail(TastyError.SymbolNotFound(fqn))

end RuntimeReflectionExample
