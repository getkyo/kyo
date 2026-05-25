package kyo.internal

/** Macro helper for [[SerializationMacroDriftTest]]. Lives in a separate file because Scala 3 inline-macro callers
  * must reside in a different compilation unit from the macro implementation.
  *
  * [[unrecognisedGivens]] enumerates every `given` member declared on the `kyo.Schema` companion at compile time. For
  * each given whose return type peels to `Schema[T]`:
  *   - if `T` has no free type parameters (monomorphic givens like `Schema[String]`, `Schema[java.util.UUID]`,
  *     `Schema[kyo.Text]`), it runs [[SerializationMacro.isSerializableType]] directly;
  *   - if `T` is an applied type with free type parameters (parameterised givens like `Schema[List[A]]`,
  *     `Schema[Map[String, V]]`, `Schema[Tuple3[A, B, C]]`, `Schema[Either[A, B]]`), it substitutes every free
  *     parameter with `String` (a known-serializable type) and runs the gate on the substituted type.
  *
  * This catches gate drift in both directions: missing primitive entries surface as monomorphic-given rejections, and
  * missing container/tuple/either tycons surface because the substituted applied type falls through every match
  * branch in `check`.
  *
  * Givens like `Schema.derived[A]` whose target is a bare type parameter (no constructor) are filtered out: those are
  * the typeclass-derivation entry point itself, not a container declaration. Coverage for that path comes from the
  * round-trip tests in `CodecTest`.
  */
object SerializationMacroDriftMacro:
    import scala.quoted.*

    inline def unrecognisedGivens: Seq[String] = ${ unrecognisedGivensImpl }
    inline def isRecognised[A]: Boolean        = ${ isRecognisedImpl[A] }

    private def unrecognisedGivensImpl(using Quotes): Expr[Seq[String]] =
        import quotes.reflect.*

        val schemaSym    = TypeRepr.of[kyo.Schema.type].typeSymbol
        val givenMembers = schemaSym.declaredMethods.filter(_.flags.is(Flags.Given))
        val stringTpe    = TypeRepr.of[String]

        // Peel Schema[T] from a return type, accepting refinements like `Schema[T] { type Focused = ... }`.
        def peel(ret: TypeRepr): Option[TypeRepr] =
            ret.dealias match
                case AppliedType(_, List(target)) => Some(target)
                case Refinement(parent, _, _)     => peel(parent)
                case _                            => None

        // Replace every bare TypeRef whose symbol is a type parameter with String. Recurses into AppliedType args.
        def substituteParams(tpe: TypeRepr): TypeRepr =
            tpe match
                case TypeRef(_, _) if tpe.typeSymbol.isTypeParam => stringTpe
                case AppliedType(tycon, args)                    => AppliedType(tycon, args.map(substituteParams))
                case other                                       => other

        val rejected: List[String] = givenMembers.flatMap { m =>
            m.tree match
                case d: DefDef =>
                    peel(d.returnTpt.tpe).flatMap { target =>
                        val substituted = substituteParams(target)
                        // Skip bare type-parameter targets (Schema.derived[A]); they are not concrete categories.
                        if substituted.typeSymbol.isTypeParam then None
                        else if SerializationMacro.isSerializableType(substituted) then None
                        else Some(s"${m.name}: ${target.show}")
                    }
                case _ => None
        }
        Expr.ofSeq(rejected.map(Expr(_)))
    end unrecognisedGivensImpl

    private def isRecognisedImpl[A: Type](using Quotes): Expr[Boolean] =
        import quotes.reflect.*
        Expr(SerializationMacro.isSerializableType(TypeRepr.of[A]))
    end isRecognisedImpl
end SerializationMacroDriftMacro
