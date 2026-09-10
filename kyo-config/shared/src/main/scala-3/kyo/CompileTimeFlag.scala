package kyo

import scala.annotation.publicInBinary
import scala.quoted.*

/** Resolves a flag at compile time and inlines the result as a constant.
  *
  * Resolution order is [[Flag]]'s, system property then environment variable then the default, read from the machine doing the compiling.
  * The value reaches the program as a literal, so a branch on it is eliminated before the class file exists, which is what lets an
  * instrumentation hook cost nothing when it is off.
  *
  * Reads the JDK directly rather than through [[FlagPlatform]]: a macro expands inside the compiler, on a JVM, whichever platform is
  * being compiled for, and `FlagPlatform`'s JS implementation reads `process` from the JavaScript global scope. Only the environment
  * variable naming rule is shared, through [[Flag.envName]], which is a pure function of the name.
  *
  * Stricter than `Flag.Reader.boolean`, which follows `java.lang.Boolean.parseBoolean` and reads anything other than "true" as false. A
  * flag resolved at runtime still shows up in `Flag.dump()`; one resolved at compile time does not, so `enabled=yes` fails the build
  * rather than becoming a feature that is quietly off.
  */
@publicInBinary private[kyo] object CompileTimeFlag:

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
