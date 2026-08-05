package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.db.Backend
import scala.quoted.*
import scala.util.control.NonFatal

/** Builds a [[kyo.db.Backend.Registry]] from the backend factories on the compile classpath.
  *
  * Reads every `META-INF/services/kyo.db.Backend` resource the macro classloader can see, resolves each named class through the compiler's
  * own symbol table so a services entry naming an absent class fails the compile rather than the run, and emits one plain constructor call
  * per factory found, which is why a backend's factory class name is part of its binary contract. The expansion carries no reflection and
  * reads no resource at run time, so the registry costs one construction per summon site and works identically on every target platform.
  *
  * An empty result is not an error here: a project may summon a registry before adding a backend, and the diagnostic belongs at the point a
  * URL is actually opened, where the scheme being asked for is known.
  */
private[kyo] object SqlBackendRegistryMacro:

    private val servicesPath = "META-INF/services/kyo.db.Backend"

    def deriveImpl(using Quotes): Expr[Backend.Registry] =
        import quotes.reflect.*

        val constructions = factoryNames().map { fqn =>
            // A name the symbol table cannot resolve comes back two ways and `NoSymbol` is neither of them:
            // `Symbol.requiredClass` raises, or it hands back a stub standing in for the missing class. Both are normalized
            // to `noSymbol` here, the stub through its declarations, which are empty, so it has no constructor to call.
            val cls =
                try
                    val sym = Symbol.requiredClass(fqn)
                    if sym.primaryConstructor.exists then sym else Symbol.noSymbol
                catch case ex: Throwable if NonFatal(ex) => Symbol.noSymbol
            if !cls.exists then
                report.errorAndAbort(
                    s"$servicesPath names '$fqn', which this compilation cannot resolve to a class it can construct. " +
                        "The backend artifact declaring it is either missing from the compile classpath or was published with a " +
                        "different class name."
                )
            end if
            Apply(Select(New(TypeIdent(cls)), cls.primaryConstructor), Nil).asExprOf[Backend]
        }

        '{ new Backend.Registry(Chunk.from(${ Expr.ofList(constructions) })) }
    end deriveImpl

    /** The factory class names declared across every services resource, in classpath order and without repeats.
      *
      * Follows the `ServiceLoader` file convention: one class name per line, `#` starts a comment, blank lines are ignored.
      */
    private def factoryNames(): List[String] =
        val resources = this.getClass.getClassLoader.getResources(servicesPath)
        val names     = List.newBuilder[String]
        while resources.hasMoreElements do
            val stream = resources.nextElement().openStream()
            try
                val text = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                text.linesIterator.foreach { line =>
                    val name = line.takeWhile(_ != '#').trim
                    if name.nonEmpty then names += name
                }
            finally stream.close()
            end try
        end while
        names.result().distinct
    end factoryNames

end SqlBackendRegistryMacro
