package sandbox.internal

import scala.quoted.*

/** Reports what the compiler hands a macro at a given site: the type as seen, its dealiasing
  * routes, and the owner chain. For recording compiler facts, never for the design itself.
  */
object ProbeMacro:
    inline def seen[A]: String = ${ seenImpl[A] }

    private def seenImpl[A: Type](using Quotes): Expr[String] =
        import quotes.reflect.*
        val t = TypeRepr.of[A]
        def flags(s: Symbol) = if s.flags.is(Flags.Opaque) then "opaque" else "plain"
        def safe(f: => String) =
            try f
            catch case e: Throwable => "ERR " + e.getClass.getSimpleName
        val routes = List(
            "seen=" + t.show + "(" + flags(t.typeSymbol) + ")",
            "dealias=" + t.dealias.show,
            "dealiasKeepOpaques=" + t.dealiasKeepOpaques.show,
            "simplified=" + t.simplified.show
        )
        Expr(routes.mkString("; "))
    end seenImpl

    inline def same[A, B]: String = ${ sameImpl[A, B] }

    private def sameImpl[A: Type, B: Type](using Quotes): Expr[String] =
        import quotes.reflect.*
        val a = TypeRepr.of[A]
        val b = TypeRepr.of[B]
        Expr(s"${a.show} =:= ${b.show}: ${a =:= b}; <:<: ${a <:< b}; >:>: ${b <:< a}")
    end sameImpl

    inline def underlyingOf[A]: String = ${ underlyingOfImpl[A] }

    private def underlyingOfImpl[A: Type](using Quotes): Expr[String] =
        import quotes.reflect.*
        val sym = TypeRepr.of[A].typeSymbol
        val u   = sym.typeRef.dealias
        val deeper =
            u match
                case lambda: TypeLambda =>
                    lambda.resType match
                        case AppliedType(tycon, _) =>
                            "tycon.dealias=" + tycon.dealias.show(using Printer.TypeReprStructure) +
                                "; resType.dealias=" + lambda.resType.dealias.show(using Printer.TypeReprStructure) +
                                "; resType.simplified=" + lambda.resType.simplified.show(using Printer.TypeReprStructure)
                        case other                 => "resType=" + other.show(using Printer.TypeReprStructure)
                case other => other.show(using Printer.TypeReprStructure)
        Expr(s"${sym.fullName}: dealias=${u.show}; $deeper")
    end underlyingOfImpl

    inline def packageOpaques: String = ${ packageOpaquesImpl }

    private def packageOpaquesImpl(using Quotes): Expr[String] =
        import quotes.reflect.*
        val chain = Iterator.iterate(Symbol.spliceOwner)(_.owner).takeWhile(s => !s.isNoSymbol).toList
        val owners = chain.filter(s => s.isPackageDef || s.isClassDef)
        Expr(owners.map(p =>
            p.name + ": " + p.declaredTypes.filter(_.flags.is(Flags.Opaque)).map(t => t.name + "=" + t.typeRef.dealias.show).mkString(",")
        ).mkString(" | "))
    end packageOpaquesImpl

    inline def owners: String = ${ ownersImpl }

    private def ownersImpl(using Quotes): Expr[String] =
        import quotes.reflect.*
        val chain = Iterator.iterate(Symbol.spliceOwner)(_.owner).takeWhile(s => !s.isNoSymbol).toList
        Expr(chain.map(s => s.name + (if s.isClassDef then "[C]" else if s.isPackageDef then "[P]" else "")).mkString(" <- "))
    end ownersImpl
end ProbeMacro
