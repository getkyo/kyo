package kyo.internal

import scala.NamedTuple.NamedTuple
import scala.annotation.implicitNotFound
import scala.quoted.*

/** Type class for composing named tuple input types used by routes and handlers.
  *
  * Handles both tuple concatenation (path `/` composition, handler input combining) and single-field addition (route builder methods like
  * `.query`, `.header`). For single-field addition, express the field as a single-element named tuple:
  * `Inputs[In, NamedTuple[N *: EmptyTuple, A *: EmptyTuple]]` or use the `Inputs.Field` alias.
  *
  * `EmptyTuple` is the sentinel for "no inputs".
  */
@implicitNotFound(
    "Cannot compose input types ${A} and ${B}. " +
        "Both must be EmptyTuple or named tuple types."
)
sealed trait Inputs[A, B]:
    type Out
end Inputs

object Inputs:

    /** Convenience alias for adding a single named field: `Inputs[In, (N: A)]`. */
    type Field[In, N <: String & Singleton, A] = Inputs[In, NamedTuple[N *: EmptyTuple, A *: EmptyTuple]]

    // --- Type computation (pure given instances, no macros) ---

    given emptyEmpty: Inputs[EmptyTuple, EmptyTuple] with
        type Out = EmptyTuple

    given emptyLeft[N <: Tuple, V <: Tuple]: Inputs[EmptyTuple, NamedTuple[N, V]] with
        type Out = NamedTuple[N, V]

    given emptyRight[N <: Tuple, V <: Tuple]: Inputs[NamedTuple[N, V], EmptyTuple] with
        type Out = NamedTuple[N, V]

    given concat[N1 <: Tuple, V1 <: Tuple, N2 <: Tuple, V2 <: Tuple]
        : Inputs[NamedTuple[N1, V1], NamedTuple[N2, V2]] with
        type Out = NamedTuple[Tuple.Concat[N1, N2], Tuple.Concat[V1, V2]]

    // --- Inline helpers (centralize validation for callers) ---

    /** Validates adding a named field to an input type. Checks: string literal, non-empty, no duplicate. Route builder methods call this —
      * validation is centralized here instead of in each caller.
      */
    inline def addField[In, N <: String & Singleton, A](
        using Field[In, N, A]
    ): Unit =
        ${ InputsMacro.validateFieldImpl[In, N] }

    /** Validates combining two input types. Checks for overlapping field names. Path `/` operator calls this.
      */
    inline def combine[A, B](using Inputs[A, B]): Unit =
        ${ InputsMacro.validateImpl[A, B] }

end Inputs

private[kyo] object InputsMacro:

    /** Extracts field names from a NamedTuple type. Returns Nil for EmptyTuple or non-NamedTuple types. */
    private[internal] def getFieldNames[T: Type](using Quotes): List[String] =
        import quotes.reflect.*

        def extractNames(tupleType: TypeRepr): List[String] =
            tupleType.dealias match
                case tp if tp =:= TypeRepr.of[EmptyTuple] => Nil
                case AppliedType(_, args) if tupleType.dealias <:< TypeRepr.of[NonEmptyTuple] =>
                    val headName = args.head.dealias match
                        case ConstantType(StringConstant(str)) => List(str)
                        case _                                 => Nil
                    headName ++ extractNames(args(1))
                case AppliedType(tycon, args) if args.size == 2 && tycon.typeSymbol.fullName.contains("Concat") =>
                    // Handles Tuple.Concat[A, B] which may not be reduced
                    extractNames(args(0)) ++ extractNames(args(1))
                case _ => Nil
        end extractNames

        val repr = TypeRepr.of[T]
        if repr =:= TypeRepr.of[EmptyTuple] then Nil
        else
            val namedTupleSym = TypeRepr.of[NamedTuple[EmptyTuple, EmptyTuple]].typeSymbol
            repr.dealias match
                case AppliedType(tycon, List(names, _)) if tycon.typeSymbol.fullName == namedTupleSym.fullName =>
                    extractNames(names)
                case _ => Nil
            end match
        end if
    end getFieldNames

    /** Validates adding a single named field: literal check, empty check, duplicate check. */
    def validateFieldImpl[In: Type, N: Type](using Quotes): Expr[Unit] =
        import quotes.reflect.*

        val name = TypeRepr.of[N].dealias match
            case ConstantType(StringConstant(s)) => s
            case other =>
                report.errorAndAbort(
                    s"""Parameter name must be a string literal, got: ${other.show}
                       |
                       |Route builder methods and path captures require string literals so the
                       |compiler can track input names for the handler's named tuple.
                       |
                       |  route.query[Int]("limit")   // OK — string literal
                       |  HttpPath.int("id")          // OK — string literal
                       |
                       |  val name = "id"
                       |  HttpPath.int(name)          // Error — not a literal""".stripMargin
                )

        if name.isEmpty then
            report.errorAndAbort(
                s"""Parameter name cannot be empty.
                   |
                   |  HttpPath.int("id")        // OK
                   |  HttpPath.int("")          // Error — empty name""".stripMargin
            )
        end if

        val existingNames = getFieldNames[In]
        if existingNames.contains(name) then
            val existing = existingNames.mkString(", ")
            report.errorAndAbort(
                s"""Duplicate input name '$name'. Existing inputs: ($existing)
                   |
                   |Each input name must be unique. Rename the conflicting parameter to resolve.""".stripMargin
            )
        end if

        '{ () }
    end validateFieldImpl

    /** Validates combining two named tuples: checks for overlapping field names. */
    def validateImpl[A: Type, B: Type](using Quotes): Expr[Unit] =
        import quotes.reflect.*
        val namesA  = getFieldNames[A]
        val namesB  = getFieldNames[B]
        val overlap = namesA.toSet.intersect(namesB.toSet)
        if overlap.nonEmpty then
            val overlapStr = overlap.mkString("'", "', '", "'")
            report.errorAndAbort(
                s"""Duplicate path capture name(s): $overlapStr.
                   |
                   |When composing paths with `/`, each capture must have a unique name.
                   |The left path has captures: (${namesA.mkString(", ")})
                   |The right path has captures: (${namesB.mkString(", ")})
                   |
                   |Rename one of the conflicting captures to resolve.""".stripMargin
            )
        end if
        '{ () }
    end validateImpl

end InputsMacro
