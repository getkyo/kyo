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

    /** Resolve SymbolKind from a TYPEDEF modifier flags bitmask, where the caller has confirmed the TYPEDEF payload does NOT contain a
      * TEMPLATE (type-level definition). Discriminants:
      *   - OPAQUE flag: OpaqueType
      *   - else: TypeAlias (TYPEBOUNDS-vs-alias distinction is resolved lazily in Phase 4)
      */
    def fromTypedefTypeFlags(flags: Long): Reflect.SymbolKind =
        if (flags & Reflect.Flag.Opaque.bit) != 0L then Reflect.SymbolKind.OpaqueType
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
