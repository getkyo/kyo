package kyo.internal.tasty.symbol

import kyo.Tasty
import kyo.internal.tasty.reader.TastyFormat

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
    def fromTypedefTemplateFlags(flags: Long): Tasty.SymbolKind =
        if (flags & Tasty.Flag.Trait.bit) != 0L then Tasty.SymbolKind.Trait
        else if (flags & Tasty.Flag.Module.bit) != 0L then Tasty.SymbolKind.Object
        else if (flags & Tasty.Flag.Enum.bit) != 0L && (flags & Tasty.Flag.Case.bit) != 0L then
            // F-E-007: enum-case classes carry both the Enum and Case flags.
            // Enum-case OBJECTS additionally carry Module; the Module branch above handles those,
            // ensuring only class-form enum cases (no Module flag) reach this branch.
            Tasty.SymbolKind.EnumCase
        else Tasty.SymbolKind.Class

    /** Resolve SymbolKind from a TYPEDEF modifier flags bitmask and the sub-tree tag, where the caller has confirmed the TYPEDEF payload
      * does NOT contain a TEMPLATE (type-level definition). Discriminants (checked in order):
      *   - OPAQUE flag: OpaqueType
      *   - ABSTRACT flag OR bodyTag is TYPEBOUNDS/TYPEBOUNDStpt: AbstractType (abstract type member, e.g. `type Item` in a trait). dotty
      *     emits TYPEBOUNDS or TYPEBOUNDStpt for abstract type members; the ABSTRACT (Deferred) modifier may or may not be present.
      *   - else: TypeAlias
      */
    def fromTypedefTypeFlagsAndBody(flags: Long, bodyTag: Int): Tasty.SymbolKind =
        if (flags & Tasty.Flag.Opaque.bit) != 0L then Tasty.SymbolKind.OpaqueType
        else if (flags & Tasty.Flag.Abstract.bit) != 0L
            || bodyTag == TastyFormat.TYPEBOUNDS
            || bodyTag == TastyFormat.TYPEBOUNDStpt
        then Tasty.SymbolKind.AbstractType
        else Tasty.SymbolKind.TypeAlias

    /** Resolve SymbolKind from a TYPEDEF modifier flags bitmask, where the caller has confirmed the TYPEDEF payload does NOT contain a
      * TEMPLATE (type-level definition). Discriminants:
      *   - OPAQUE flag: OpaqueType
      *   - ABSTRACT flag: AbstractType (abstract type member, e.g. `type Item` in a trait)
      *   - else: TypeAlias
      *
      * Prefer fromTypedefTypeFlagsAndBody when the sub-tree tag is available, for more reliable AbstractType detection.
      */
    def fromTypedefTypeFlags(flags: Long): Tasty.SymbolKind =
        if (flags & Tasty.Flag.Opaque.bit) != 0L then Tasty.SymbolKind.OpaqueType
        else if (flags & Tasty.Flag.Abstract.bit) != 0L then Tasty.SymbolKind.AbstractType
        else Tasty.SymbolKind.TypeAlias

    /** Map a VALDEF modifier flags bitmask to Val or Var. */
    def fromValdefFlags(flags: Long): Tasty.SymbolKind =
        if (flags & Tasty.Flag.Mutable.bit) != 0L then Tasty.SymbolKind.Var
        else Tasty.SymbolKind.Val

    /** Dispatch entry point mandated by plan line 196. Maps an AST tag and collected modifier flags to a SymbolKind. TYPEDEF with a
      * TEMPLATE body must have been detected and split by the caller (AstUnpickler) before calling this; pass the flags from the
      * appropriate helper accordingly.
      */
    def fromTagAndFlags(tag: Int, flags: Long): Tasty.SymbolKind =
        import TastyFormat.*
        tag match
            case PACKAGE   => Tasty.SymbolKind.Package
            case TYPEDEF   => fromTypedefTypeFlags(flags)
            case VALDEF    => fromValdefFlags(flags)
            case DEFDEF    => Tasty.SymbolKind.Method
            case TYPEPARAM => Tasty.SymbolKind.TypeParam
            case PARAM     => Tasty.SymbolKind.Parameter
            case _         => Tasty.SymbolKind.Unresolved
        end match
    end fromTagAndFlags

end SymbolKind
