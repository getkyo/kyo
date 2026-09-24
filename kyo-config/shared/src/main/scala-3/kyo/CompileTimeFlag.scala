package kyo

import scala.quoted.*

/** Resolves a flag at compile time and inlines the result as a constant.
  *
  * The value reaches the program as a literal, so a branch on it is eliminated before the class file exists.
  *
  * Reads the JDK directly rather than through [[FlagPlatform]]: a macro expands inside the compiler, on a JVM, whichever platform is being
  * compiled for, and `FlagPlatform`'s JS implementation reads `process` from the JavaScript global scope. Only the environment variable
  * naming rule is shared, through [[Flag.envName]].
  *
  * Stricter than `Flag.Reader.boolean`, which reads anything other than "true" as false: a flag resolved at compile time does not show up
  * in `Flag.dump()`, so `enabled=yes` fails the build rather than becoming a feature that is quietly off.
  *
  * Public rather than `private[kyo]`: an inline method that references a qualified-private object compiles to an inline accessor in its
  * own class, which `DebuggerBytecodeTest` forbids, and `@publicInBinary`, the other way to avoid it, does not exist on the Scala 3.3
  * cross-build this module publishes.
  */
object CompileTimeFlag:

    inline def boolean(inline name: String, inline default: Boolean): Boolean = ${ booleanImpl('name, 'default) }

    private def booleanImpl(name: Expr[String], default: Expr[Boolean])(using Quotes): Expr[Boolean] =
        import quotes.reflect.*
        val key = name.valueOrAbort
        resolve(key) match
            case Some("true")  => Expr(true)
            case Some("false") => Expr(false)
            case Some(other)   => report.errorAndAbort(s"$key must be true or false, got $other")
            case None          => Expr(default.valueOrAbort)
        end match
    end booleanImpl

    private def resolve(key: String): Option[String] =
        Option(java.lang.System.getProperty(key))
            .orElse(Option(java.lang.System.getenv(Flag.envName(key))))
end CompileTimeFlag
