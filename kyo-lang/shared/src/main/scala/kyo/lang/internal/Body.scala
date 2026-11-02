package kyo.lang.internal

import kyo.internal.DirectSyntax
import kyo.internal.Lower
import scala.quoted.*

/** The typed macro that validates and lowers a `.kyo` definition body.
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
  */
object Body:

    transparent inline def apply[A](inline value: A) = ${ transform('value, false) }

    transparent inline def field[A](inline value: A) = ${ transform('value, true) }

    private def transform[A: Type](value: Expr[A], field: Boolean)(using Quotes): Expr[Any] =
        import quotes.reflect.*
        Validate(value)
        val markers = new TreeAccumulator[Boolean]:
            def foldTree(found: Boolean, tree: Tree)(owner: Symbol): Boolean =
                found || DirectSyntax.now(tree).isDefined || foldOverTree(false, tree)(owner)
        if !markers.foldTree(false, value.asTerm)(Symbol.spliceOwner) then value
        else if field then report.errorAndAbort("Effectful fields are not supported in .kyo; define a method", value.asTerm.pos)
        else
            kyo.internal.Validate(value)
            Lower(value)
        end if
    end transform
end Body
