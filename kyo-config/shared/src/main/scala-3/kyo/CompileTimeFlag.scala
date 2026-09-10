package kyo

import scala.annotation.publicInBinary
import scala.quoted.*

/** Resolves a flag while the code is being compiled and inlines the result as a constant.
  *
  * The order is [[Flag]]'s, system property then environment variable then the default, but read from the
  * machine doing the compiling rather than the one running the result. The value reaches the program as a
  * literal, so a branch on it is gone before the class file exists, which is what lets an instrumentation
  * hook cost nothing when it is off.
  *
  * Reads the JDK directly rather than going through [[FlagPlatform]], and that is not an oversight: a macro
  * expands inside the compiler, on a JVM, whichever platform is being compiled for, while `FlagPlatform`'s
  * JS implementation reads `process` from the JavaScript global scope and has no meaning there. Only the
  * environment variable naming rule is shared, through [[Flag.envName]], because it is a pure function of
  * the flag name with no config source behind it.
  *
  * Stricter than `Flag.Reader.boolean`, deliberately. That reader follows `java.lang.Boolean.parseBoolean`
  * and reads anything other than "true" as false, which is a fair choice for a flag resolved at runtime,
  * where a wrong value still shows up in `Flag.dump()`. A flag resolved at compile time has no such second
  * chance, so `enabled=yes` has to fail the build rather than become a feature that is quietly off.
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
