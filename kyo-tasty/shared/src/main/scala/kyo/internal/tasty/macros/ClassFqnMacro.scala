package kyo.internal.tasty.macros

import scala.quoted.*

/** Compile-time extraction of the dotted FQN of `A`'s class symbol.
  *
  * Used by `Tasty.classFqn[A]`. Reads `TypeRepr.of[A].typeSymbol.fullName` so the result is the bare
  * class FQN, with type parameters stripped: `classFqn[List[Int]]` evaluates to
  * `"scala.collection.immutable.List"`, not `"scala.collection.immutable.List[scala.Int]"`. The
  * stripped form is what `Classpath.findClass`, `findClassLike`, and `findSymbol` accept.
  */
private[kyo] object ClassFqnMacro:

    def impl[A](using Quotes, Type[A]): Expr[String] =
        import quotes.reflect.*
        // dealias so `Predef.String` -> `java.lang.String`, `Predef.Map[K, V]` -> `scala.collection.immutable.Map`, etc.
        val sym = TypeRepr.of[A].dealias.typeSymbol
        if !sym.exists then
            report.errorAndAbort(s"Tasty.classFqn: type ${Type.show[A]} has no class symbol")
        Expr(sym.fullName)
    end impl

end ClassFqnMacro
