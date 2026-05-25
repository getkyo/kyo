package kyo.internal.reflect.symbol

import kyo.Reflect
import kyo.internal.reflect.tasty.TastyFormat

/** TASTy-tag-to-SymbolKind mapping.
  *
  * TYPEDEF discrimination: TYPEDEF is shared between class-like definitions (where the sub-tree after NameRef is TEMPLATE=156) and
  * type-level definitions (where the sub-tree is a type term). This helper handles the modifier-flag-based discrimination for class-like
  * TYPEDEFs after the TEMPLATE peek is done in AstUnpickler. The peek itself belongs in AstUnpickler where the payload cursor is available.
  *
  * Reference: dotty TreeUnpickler.readNewDef, line ~935 in scala3-compiler 3.7.0.
  */
object SymbolKind:

    /** Resolve SymbolKind from a TYPEDEF modifier flags bitmask, where the caller has already confirmed that the TYPEDEF payload contains a
      * TEMPLATE (class-like definition). Modifier flags bit meanings:
      *   - TRAIT (bit for Flag.Trait): Trait
      *   - OBJECT (bit for Flag.Module): Object
      *   - else: Class
      */
    def fromTypedefTemplateFlags(flags: Long): Reflect.SymbolKind =
        if (flags & Reflect.Flag.Trait.bit) != 0L then Reflect.SymbolKind.Trait
        else if (flags & Reflect.Flag.Module.bit) != 0L then Reflect.SymbolKind.Object
        else Reflect.SymbolKind.Class

    /** Resolve SymbolKind from a TYPEDEF modifier flags bitmask and the sub-tree tag, where the caller has confirmed the TYPEDEF payload
      * does NOT contain a TEMPLATE (type-level definition). Discriminants (checked in order):
      *   - OPAQUE flag: OpaqueType
      *   - ABSTRACT flag OR bodyTag is TYPEBOUNDS/TYPEBOUNDStpt: AbstractType (abstract type member, e.g. `type Item` in a trait). dotty
      *     emits TYPEBOUNDS or TYPEBOUNDStpt for abstract type members; the ABSTRACT (Deferred) modifier may or may not be present.
      *   - else: TypeAlias
      */
    def fromTypedefTypeFlagsAndBody(flags: Long, bodyTag: Int): Reflect.SymbolKind =
        if (flags & Reflect.Flag.Opaque.bit) != 0L then Reflect.SymbolKind.OpaqueType
        else if (flags & Reflect.Flag.Abstract.bit) != 0L
            || bodyTag == TastyFormat.TYPEBOUNDS
            || bodyTag == TastyFormat.TYPEBOUNDStpt
        then Reflect.SymbolKind.AbstractType
        else Reflect.SymbolKind.TypeAlias

    /** Resolve SymbolKind from a TYPEDEF modifier flags bitmask, where the caller has confirmed the TYPEDEF payload does NOT contain a
      * TEMPLATE (type-level definition). Discriminants:
      *   - OPAQUE flag: OpaqueType
      *   - ABSTRACT flag: AbstractType (abstract type member, e.g. `type Item` in a trait)
      *   - else: TypeAlias
      *
      * Prefer fromTypedefTypeFlagsAndBody when the sub-tree tag is available, for more reliable AbstractType detection.
      */
    def fromTypedefTypeFlags(flags: Long): Reflect.SymbolKind =
        if (flags & Reflect.Flag.Opaque.bit) != 0L then Reflect.SymbolKind.OpaqueType
        else if (flags & Reflect.Flag.Abstract.bit) != 0L then Reflect.SymbolKind.AbstractType
        else Reflect.SymbolKind.TypeAlias

    /** Map a VALDEF modifier flags bitmask to Val or Var. */
    def fromValdefFlags(flags: Long): Reflect.SymbolKind =
        if (flags & Reflect.Flag.Mutable.bit) != 0L then Reflect.SymbolKind.Var
        else Reflect.SymbolKind.Val

    /** Dispatch entry point mandated by plan line 196. Maps an AST tag and collected modifier flags to a SymbolKind. TYPEDEF with a
      * TEMPLATE body must have been detected and split by the caller (AstUnpickler) before calling this; pass the flags from the
      * appropriate helper accordingly.
      */
    def fromTagAndFlags(tag: Int, flags: Long): Reflect.SymbolKind =
        import TastyFormat.*
        tag match
            case PACKAGE   => Reflect.SymbolKind.Package
            case TYPEDEF   => fromTypedefTypeFlags(flags)
            case VALDEF    => fromValdefFlags(flags)
            case DEFDEF    => Reflect.SymbolKind.Method
            case TYPEPARAM => Reflect.SymbolKind.TypeParam
            case PARAM     => Reflect.SymbolKind.Parameter
            case _         => Reflect.SymbolKind.Unresolved
        end match
    end fromTagAndFlags

end SymbolKind
