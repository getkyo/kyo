package kyo.internal

import kyo.*
import kyo.Record.*
import scala.quoted.*

/** Shared macro utilities for navigable types (Schema, Path, Mutator, Diff, etc.).
  *
  * All navigable types follow the same pattern:
  *   1. Extract the field name from a singleton string type
  *   2. Expand the focus type to get its structural representation
  *   3. Find the value type for the named field in the expanded type
  *   4. Return a new instance of the navigator focused on the field's type
  *
  * This object factors out steps 1-3 so each navigator macro only implements step 4.
  *
  * =Relationship to Record.stage=
  *
  * Conceptually, every navigable type is a "staged Record" where the interpretation function G determines the navigator type:
  *   - Schema: G[V] = Schema[expanded_V]
  *   - Path: G[V] = Path[Root, V]
  *   - Mutator: G[V] = Mutator[Root, V] with composed getter/setter
  *   - etc.
  *
  * Record.stage[A]([v] => field => G[v](field)) materializes this pattern for first-level navigation. However, the per-type selectDynamic
  * macros remain necessary because:
  *
  *   1. '''Return type computation''': Each selectDynamic returns a type-specific wrapper (Schema[expanded_V], Path[Root, V], etc.) that
  *      requires compile-time type expansion via ExpandMacro. Record's selectDynamic returns Fields.Have#Value (the raw type), not the
  *      wrapped form.
  *   2. '''Stateful composition''': Most navigators compose per-instance state (getters, setters, paths, rules) across levels. The child
  *      value depends on the parent's state, which doesn't exist at staging time.
  *   3. '''Type-level effects''': Some navigators (Builder, Select) grow or shrink type parameters across navigation steps, which
  *      Record.selectDynamic cannot express.
  *
  * The shared NavigationMacro captures the REAL duplication (field resolution), while Record.stage captures the CONCEPTUAL model.
  */
object NavigationMacro:

    /** Result of resolving a field in a structural type. */
    case class ResolvedField(
        nameStr: String,
        valueType: Any, // quotes.reflect.TypeRepr (can't parameterize by Quotes)
        isSum: Boolean  // true if field was found via OrType (sum variant), false if via AndType (product field)
    )

    /** Extract the field name string from a singleton string type parameter. */
    def extractName[Name <: String: Type](using q: Quotes): String =
        import q.reflect.*
        TypeRepr.of[Name] match
            case ConstantType(StringConstant(s)) => s
            case _ => report.errorAndAbort(s"Field name must be a literal string type, got: ${TypeRepr.of[Name].show}")
    end extractName

    /** Resolve a field by name in an already-expanded structural type.
      *
      * Returns the value type and whether it was found via a sum (OrType) or product (AndType).
      */
    def classifyField(using Quotes)(tpe: quotes.reflect.TypeRepr, nameStr: String): Option[(quotes.reflect.TypeRepr, Boolean)] =
        import quotes.reflect.*
        tpe.dealias match
            case AndType(l, r) =>
                findDirect(l, nameStr).map((_, false))
                    .orElse(findDirect(r, nameStr).map((_, false)))
                    .orElse(classifyField(l, nameStr))
                    .orElse(classifyField(r, nameStr))
            case OrType(l, r) =>
                findDirect(l, nameStr).map((_, true))
                    .orElse(findDirect(r, nameStr).map((_, true)))
                    .orElse(classifyField(l, nameStr))
                    .orElse(classifyField(r, nameStr))
            case AppliedType(_, List(ConstantType(StringConstant(n)), valueType)) if n == nameStr =>
                Some((valueType, false))
            case _ =>
                None
        end match
    end classifyField

    /** Find the value type for a field name directly in a ~ application, recursing into And/Or types. */
    def findDirect(using Quotes)(tpe: quotes.reflect.TypeRepr, nameStr: String): Option[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        tpe.dealias match
            case AppliedType(_, List(ConstantType(StringConstant(n)), valueType)) if n == nameStr =>
                Some(valueType)
            case AndType(l, r) =>
                findDirect(l, nameStr).orElse(findDirect(r, nameStr))
            case OrType(l, r) =>
                findDirect(l, nameStr).orElse(findDirect(r, nameStr))
            case _ =>
                None
        end match
    end findDirect

    /** Simple findValueType (no product/sum classification needed). */
    def findValueType(using Quotes)(tpe: quotes.reflect.TypeRepr, nameStr: String): Option[quotes.reflect.TypeRepr] =
        classifyField(tpe, nameStr).map(_._1)

    /** Full resolution: extract name, expand focus, find field, classify product vs sum.
      *
      * This is the main entry point that combines all steps.
      */
    def resolve[Focus: Type, Name <: String: Type](using q: Quotes): ResolvedField =
        import q.reflect.*

        val nameStr  = extractName[Name]
        val expanded = ExpandMacro.expandType(TypeRepr.of[Focus])
        val (valueType, isSum) = classifyField(expanded, nameStr).getOrElse {
            val available = MacroUtils.collectFields(expanded).map(_._1)
            report.errorAndAbort(
                s"Field '$nameStr' not found. Available fields: ${available.mkString(", ")}."
            )
        }

        ResolvedField(nameStr, valueType, isSum)
    end resolve

    /** Resolve returning only the value type (for navigators that don't need product/sum classification). */
    def resolveSimple[Focus: Type, Name <: String: Type](using q: Quotes): (String, quotes.reflect.TypeRepr) =
        import q.reflect.*

        val nameStr  = extractName[Name]
        val expanded = ExpandMacro.expandType(TypeRepr.of[Focus])
        val valueType = findValueType(expanded, nameStr).getOrElse {
            val available = MacroUtils.collectFields(expanded).map(_._1)
            report.errorAndAbort(
                s"Field '$nameStr' not found. Available fields: ${available.mkString(", ")}."
            )
        }

        (nameStr, valueType)
    end resolveSimple

end NavigationMacro
