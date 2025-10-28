package kyo.experimental

import kyo.*
import scala.language.dynamics
import scala.quoted.*

/** Clean service access pattern via companion objects.
  *
  * Usage:
  * ```scala
  * trait Service[-S]:
  *     def hello: String < S
  *     def greet(name: String): String < S
  *
  * object Service extends UseOps[Service]
  *
  * // Clean API:
  * Service.use.hello        // Instead of Use[Service].hello
  * Service.use.greet("Jon") // Instead of Use[Service].greet("Jon")
  * ```
  */
trait UseOps[R[-_]]:
    inline def use: R[Use[R]] = ${ UseOpsMacros.generate[R] }

    // 🎯 WORKAROUND: Provide an explicit cast method to bypass structural typing at call site
    inline def useAs: R[Use[R]] = use.asInstanceOf[R[Use[R]]]
end UseOps

class GeneratedUse

object UseOpsMacros:

    def generate[R[-_]: Type](using Quotes): Expr[R[Use[R]]] =
        import quotes.reflect.*

        val tpe    = TypeRepr.of[R]
        val tpeSym = tpe.typeSymbol

        // Validate it's a trait
        if !tpeSym.flags.is(Flags.Trait) then
            report.errorAndAbort(s"${tpeSym.name} is not a trait.")

        // Find abstract methods
        val methods = tpeSym.declarations.filter { sym =>
            sym.isDefDef && sym.flags.is(Flags.Deferred)
        }

        if methods.isEmpty then
            report.errorAndAbort(s"Trait ${tpeSym.name} has no abstract methods.")

        val traitName = tpeSym.name

        // Generate real trait implementation using AST generation
        generateTraitImplementation[R](tpe, methods)
    end generate

    private def generateTraitImplementation[R[-_]: Type](using
        Quotes
    )(
        tpe: quotes.reflect.TypeRepr,
        methods: List[quotes.reflect.Symbol]
    ): Expr[R[Use[R]]] =
        import quotes.reflect.*

        // Create Use[R] type
        val useType          = TypeRepr.of[Use].appliedTo(List(tpe))
        val appliedTraitType = tpe.appliedTo(List(useType))

        // Generate anonymous class that implements the trait
        val clsSymbol = Symbol.newClass(
            Symbol.spliceOwner,
            "$anon",
            parents = List(TypeRepr.of[GeneratedUse], appliedTraitType),
            decls = _ => Nil,
            selfType = None
        )

        // Create constructor
        val constructorSymbol = clsSymbol.primaryConstructor
        val superCall = Apply(
            Select(Super(This(clsSymbol), None), TypeRepr.of[Object].typeSymbol.primaryConstructor),
            Nil
        )
        val constructorDef = DefDef(constructorSymbol, _ => Some(superCall))

        // Generate method implementations
        val methodImpls = methods.map { method =>
            val methodName = method.name
            val methodType = appliedTraitType.memberType(method)

            val newMethodSym = Symbol.newMethod(
                clsSymbol,
                methodName,
                methodType,
                Flags.Override,
                Symbol.noSymbol
            )

            DefDef(
                newMethodSym,
                params =>
                    // Generate method body: Use.use[R](_.methodName)
                    generateMethodBody(tpe, method, params)
            )
        }

        // Create class definition
        val clsDef = ClassDef(
            clsSymbol,
            List(TypeTree.of[Object], Inferred(appliedTraitType)),
            constructorDef +: methodImpls
        )

        // Create instance
        val newExpr = New(TypeIdent(clsSymbol))
            .select(constructorSymbol)
            .appliedToArgs(Nil)

        // 🎯 ULTIMATE CASTING: Try casting in the generated runtime code itself
        val block = Block(List(clsDef), newExpr)

        // Generate a runtime cast expression
        '{
            val instance = ${ block.asExprOf[Any] }
            instance.asInstanceOf[R[Use[R]]]
        }

    end generateTraitImplementation

    private def generateMethodBody[R[-_]: Type](using
        Quotes
    )(
        rType: quotes.reflect.TypeRepr,
        method: quotes.reflect.Symbol,
        params: List[List[quotes.reflect.Tree]]
    ): Option[quotes.reflect.Term] =
        import quotes.reflect.*

        val methodName = method.name

        // For now, generate a simple implementation that calls Use.use[R]
        // This is a simplified version - we'll improve it step by step
        Some('{
            import kyo.*
            import kyo.experimental.Use

            // Generate: Use.use[R](_.methodName)
            Use.use[R](r =>
                // For now, return a simple result to prove the concept works
                "Generated result".asInstanceOf[String]
            ).asInstanceOf[Any] // Type erasure for now
        }.asTerm)
    end generateMethodBody

end UseOpsMacros
