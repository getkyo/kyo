package kyo.lang.internal

import kyo.<
import kyo.internal.DirectSyntax
import kyo.internal.Lower
import scala.quoted.*

/** The typed macros that validate and lower a `.kyo` body, and the boundaries inside it.
  *
  * The `kyo-lang` plugin wraps every method body in `Body.apply` and every field initializer in `Body.field`, after wrapping each
  * expression in a [[Markers]] marker. Normal typing resolves the markers, leaving kyo-direct's `.now` on every sequenced computation,
  * and this macro receives the typed body. It validates every resolved call and reference through [[Validate]], and then:
  *
  *   - returns the body unchanged when it has no `.now`, so a pure definition keeps its ordinary type;
  *   - rejects an effectful field initializer, since a field cannot carry an effect row;
  *   - otherwise hands the body to kyo-direct: its restrictions apply and its lowering produces the Kyo composition, exactly as for
  *     the body of a `direct` block. Every construct and every counterpart kyo-direct supports is available to a `.kyo` body, under
  *     the syntax the plugin admits.
  *
  * The macro is `transparent inline`, so the definition's inferred result type is the precise `A < S` of the lowered body rather than a
  * widened bound. An explicit result annotation on the definition participates in typing as usual: a pure annotation cannot erase a
  * pending effect, and an effectful annotation constrains the inferred row.
  *
  * The other entries are the computation boundaries inside a body, each lowering the markers of its own expression independently of the
  * enclosing body, which then sees a computation and no marker:
  *
  *   - [[boundary]] is the conversion [[Markers.boundary]] expands to, where the typer meets an expected pending type with a value;
  *   - [[sequenced]] is the pending marker overload, which plants [[Markers.sequence]];
  *   - [[closure]] wraps the body of a lambda outside argument position (a stored or returned closure): an effectful body becomes a
  *     computation, so the closure's type carries its latent effects, and a pure body keeps its type.
  */
object Body:

    transparent inline def apply[A](inline value: A) = ${ transform('value, false) }

    transparent inline def field[A](inline value: A) = ${ transform('value, true) }

    transparent inline def closure[A](inline value: A): A = ${ lowered('value) }

    def transform[A: Type](value: Expr[A], field: Boolean)(using Quotes): Expr[Any] =
        import quotes.reflect.*
        Validate(value)
        if !hasMarker(value) then value
        else if field then report.errorAndAbort("Effectful fields are not supported in .kyo; define a method", value.asTerm.pos)
        else
            kyo.internal.Validate(value)
            Lower(value)
        end if
    end transform

    def boundary[A: Type, S: Type](value: Expr[A])(using Quotes): Expr[A < S] =
        if !hasMarker(value) then wrapped('{ kyo.Kyo.lift[A, Any]($value) }).asInstanceOf[Expr[A < S]]
        else
            kyo.internal.Validate(value)
            wrapped(Lower(value)).asInstanceOf[Expr[A < S]]
    end boundary

    def sequenced[P: Type](value: Expr[P])(using Quotes): Expr[Markers.Exposed[P]] =
        import quotes.reflect.*
        TypeRepr.of[P].dealias match
            case AppliedType(_, List(exposed, row)) =>
                exposed.asType match
                    case '[a] =>
                        row.asType match
                            case '[s] =>
                                '{ Markers.sequence[a, s](${ value.asExprOf[a < s] }) }.asInstanceOf[Expr[Markers.Exposed[P]]]
            case other => report.errorAndAbort(s"Not a pending type: ${other.show}", value.asTerm.pos)
        end match
    end sequenced

    def lowered[A: Type](value: Expr[A])(using Quotes): Expr[A] =
        if !hasMarker(value) then value
        else
            kyo.internal.Validate(value)
            wrapped(Lower(value)).asInstanceOf[Expr[A]]
    end lowered

    /** The computation wrapped in [[Markers.lowered]], the boundary kyo-direct's validation of the enclosing body reads as a value. */
    private def wrapped(using Quotes)(computation: Expr[Any]): Expr[Any] =
        import quotes.reflect.*
        computation.asTerm.tpe.widen.asType match
            case '[t] => '{ Markers.lowered[t](${ computation.asExprOf[t] }) }
    end wrapped

    private def hasMarker(using Quotes)(value: Expr[Any]): Boolean =
        import quotes.reflect.*
        val markers = new TreeAccumulator[Boolean]:
            def foldTree(found: Boolean, tree: Tree)(owner: Symbol): Boolean =
                found || DirectSyntax.now(tree).isDefined || foldOverTree(false, tree)(owner)
        markers.foldTree(false, value.asTerm)(Symbol.spliceOwner)
    end hasMarker
end Body
