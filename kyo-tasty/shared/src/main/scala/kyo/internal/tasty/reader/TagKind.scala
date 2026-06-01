package kyo.internal.tasty.reader

import kyo.TastyError

/** Exhaustive per-position tag enumerations for TASTy decode positions.
  *
  * Each enum enumerates exactly the tags that are valid in its decode position. The companion `from(raw)` factory throws
  * `TastyError.UnknownTagInPosition` on an unrecognised byte, replacing the silent `case other => warn(...); Named(-1)` fallback
  * pattern that previously allowed corrupt or future-format bytes to produce silent sentinel symbols.
  *
  * Five enums cover the five decode positions where TypeUnpickler and AstUnpickler dispatch on tag bytes:
  *   - `TypePositionTag`: type nodes (handled by `TypeUnpickler.decodeTag`)
  *   - `TreePositionTag`: statement-position tree nodes (handled by `AstUnpickler`)
  *   - `TptPositionTag`: type-tree (TPT) position nodes
  *   - `ConstantTag`: constant-type category-1 and category-2 nodes
  *   - `ModifierTag`: modifier-category-1 nodes (flags on definition nodes)
  *
  * Note: the enums serve as exhaustive documentation and as `from()` factories. They do not replace the existing `tag match` blocks
  * mechanically; instead the `case other =>` silent-fallback branches in each unpickler are replaced with explicit throws using the
  * position label from the relevant enum's `position` field.
  *
  * Raw values are the actual TASTy wire byte values from `TastyFormat`.
  */
private[kyo] object TagKind:

    // ─────────────────────────────────────────────────────────────────────────
    // TypePositionTag: all tags that can appear as the leading byte of a type
    // node when TypeUnpickler.decodeTag dispatches.
    // ─────────────────────────────────────────────────────────────────────────

    /** All valid type-position tags. Raw values match TastyFormat constants. */
    enum TypePositionTag(val raw: Int) derives CanEqual:
        // Category 1 (no payload): constant types
        case UNITconst  extends TypePositionTag(TastyFormat.UNITconst)
        case FALSEconst extends TypePositionTag(TastyFormat.FALSEconst)
        case TRUEconst  extends TypePositionTag(TastyFormat.TRUEconst)
        case NULLconst  extends TypePositionTag(TastyFormat.NULLconst)

        // Category 2 (tag + Nat): shared-type back-ref, term/type refs, constants
        case SHAREDtype    extends TypePositionTag(TastyFormat.SHAREDtype)
        case TERMREFdirect extends TypePositionTag(TastyFormat.TERMREFdirect)
        case TYPEREFdirect extends TypePositionTag(TastyFormat.TYPEREFdirect)
        case TERMREFpkg    extends TypePositionTag(TastyFormat.TERMREFpkg)
        case TYPEREFpkg    extends TypePositionTag(TastyFormat.TYPEREFpkg)
        case RECthis       extends TypePositionTag(TastyFormat.RECthis)
        case BYTEconst     extends TypePositionTag(TastyFormat.BYTEconst)
        case SHORTconst    extends TypePositionTag(TastyFormat.SHORTconst)
        case CHARconst     extends TypePositionTag(TastyFormat.CHARconst)
        case INTconst      extends TypePositionTag(TastyFormat.INTconst)
        case LONGconst     extends TypePositionTag(TastyFormat.LONGconst)
        case FLOATconst    extends TypePositionTag(TastyFormat.FLOATconst)
        case DOUBLEconst   extends TypePositionTag(TastyFormat.DOUBLEconst)
        case STRINGconst   extends TypePositionTag(TastyFormat.STRINGconst)
        // term-tag-in-type-position (SHAREDterm == 60)
        case SHAREDterm extends TypePositionTag(TastyFormat.SHAREDterm)

        // Category 3 (tag + AST): single child
        case THIS         extends TypePositionTag(TastyFormat.THIS)
        case QUALTHIS     extends TypePositionTag(TastyFormat.QUALTHIS)
        case CLASSconst   extends TypePositionTag(TastyFormat.CLASSconst)
        case BYNAMEtype   extends TypePositionTag(TastyFormat.BYNAMEtype)
        case BYNAMEtpt    extends TypePositionTag(TastyFormat.BYNAMEtpt)
        case NEW          extends TypePositionTag(TastyFormat.NEW)
        case RECtype      extends TypePositionTag(TastyFormat.RECtype)
        case SINGLETONtpt extends TypePositionTag(TastyFormat.SINGLETONtpt)

        // Category 4 (tag + Nat + AST): qualified refs
        case IDENT         extends TypePositionTag(TastyFormat.IDENT)
        case IDENTtpt      extends TypePositionTag(TastyFormat.IDENTtpt)
        case SELECT        extends TypePositionTag(TastyFormat.SELECT)
        case SELECTtpt     extends TypePositionTag(TastyFormat.SELECTtpt)
        case TERMREFsymbol extends TypePositionTag(TastyFormat.TERMREFsymbol)
        case TERMREF       extends TypePositionTag(TastyFormat.TERMREF)
        case TYPEREFsymbol extends TypePositionTag(TastyFormat.TYPEREFsymbol)
        case TYPEREF       extends TypePositionTag(TastyFormat.TYPEREF)

        // Category 5 (tag + Length + payload): structural types
        case APPLIEDtype    extends TypePositionTag(TastyFormat.APPLIEDtype)
        case ANNOTATEDtype  extends TypePositionTag(TastyFormat.ANNOTATEDtype)
        case ANDtype        extends TypePositionTag(TastyFormat.ANDtype)
        case ORtype         extends TypePositionTag(TastyFormat.ORtype)
        case SUPERtype      extends TypePositionTag(TastyFormat.SUPERtype)
        case REFINEDtype    extends TypePositionTag(TastyFormat.REFINEDtype)
        case MATCHtype      extends TypePositionTag(TastyFormat.MATCHtype)
        case FLEXIBLEtype   extends TypePositionTag(TastyFormat.FLEXIBLEtype)
        case TYPELAMBDAtype extends TypePositionTag(TastyFormat.TYPELAMBDAtype)
        case POLYtype       extends TypePositionTag(TastyFormat.POLYtype)
        case METHODtype     extends TypePositionTag(TastyFormat.METHODtype)
        case PARAMtype      extends TypePositionTag(TastyFormat.PARAMtype)
        case TYPEREFin      extends TypePositionTag(TastyFormat.TYPEREFin)
        case TERMREFin      extends TypePositionTag(TastyFormat.TERMREFin)
        case SELECTin       extends TypePositionTag(TastyFormat.SELECTin)
        case REFINEDtpt     extends TypePositionTag(TastyFormat.REFINEDtpt)
        case APPLIEDtpt     extends TypePositionTag(TastyFormat.APPLIEDtpt)
        case TYPEBOUNDStpt  extends TypePositionTag(TastyFormat.TYPEBOUNDStpt)
        case ANNOTATEDtpt   extends TypePositionTag(TastyFormat.ANNOTATEDtpt)
        case LAMBDAtpt      extends TypePositionTag(TastyFormat.LAMBDAtpt)
        case MATCHtpt       extends TypePositionTag(TastyFormat.MATCHtpt)
        case MATCHCASEtype  extends TypePositionTag(TastyFormat.MATCHCASEtype)
        case TYPEBOUNDS     extends TypePositionTag(TastyFormat.TYPEBOUNDS)
        case REPEATED       extends TypePositionTag(TastyFormat.REPEATED)
        // term-tag-in-type-position (length-prefixed)
        case APPLY     extends TypePositionTag(TastyFormat.APPLY)
        case TYPEAPPLY extends TypePositionTag(TastyFormat.TYPEAPPLY)
        case CASEDEF   extends TypePositionTag(TastyFormat.CASEDEF)
        case INLINED   extends TypePositionTag(TastyFormat.INLINED)
    end TypePositionTag

    object TypePositionTag:
        val position: String = "type"

        private val byRawMap: Map[Int, TypePositionTag] =
            values.map(t => t.raw -> t).toMap

        /** Convert a raw byte to the typed tag; throws `TastyErrorException(UnknownTagInPosition)` on unrecognised input. */
        def from(raw: Int): TypePositionTag =
            byRawMap.getOrElse(
                raw,
                throw new TastyErrorException(TastyError.UnknownTagInPosition(raw, position))
            )
    end TypePositionTag

    // ─────────────────────────────────────────────────────────────────────────
    // TreePositionTag: tags that appear as tree-statement nodes in AstUnpickler
    // ─────────────────────────────────────────────────────────────────────────

    /** All valid tree-position tags encountered by AstUnpickler in statement position. */
    enum TreePositionTag(val raw: Int) derives CanEqual:
        // Definition nodes (category 5)
        case PACKAGE   extends TreePositionTag(TastyFormat.PACKAGE)
        case VALDEF    extends TreePositionTag(TastyFormat.VALDEF)
        case DEFDEF    extends TreePositionTag(TastyFormat.DEFDEF)
        case TYPEDEF   extends TreePositionTag(TastyFormat.TYPEDEF)
        case IMPORT    extends TreePositionTag(TastyFormat.IMPORT)
        case TYPEPARAM extends TreePositionTag(TastyFormat.TYPEPARAM)
        case PARAM     extends TreePositionTag(TastyFormat.PARAM)
        case TEMPLATE  extends TreePositionTag(TastyFormat.TEMPLATE)
        case EXPORT    extends TreePositionTag(TastyFormat.EXPORT)
        // Term nodes
        case APPLY         extends TreePositionTag(TastyFormat.APPLY)
        case TYPEAPPLY     extends TreePositionTag(TastyFormat.TYPEAPPLY)
        case TYPED         extends TreePositionTag(TastyFormat.TYPED)
        case ASSIGN        extends TreePositionTag(TastyFormat.ASSIGN)
        case BLOCK         extends TreePositionTag(TastyFormat.BLOCK)
        case IF            extends TreePositionTag(TastyFormat.IF)
        case LAMBDA        extends TreePositionTag(TastyFormat.LAMBDA)
        case MATCH         extends TreePositionTag(TastyFormat.MATCH)
        case RETURN        extends TreePositionTag(TastyFormat.RETURN)
        case WHILE         extends TreePositionTag(TastyFormat.WHILE)
        case TRY           extends TreePositionTag(TastyFormat.TRY)
        case INLINED       extends TreePositionTag(TastyFormat.INLINED)
        case SELECTouter   extends TreePositionTag(TastyFormat.SELECTouter)
        case REPEATED      extends TreePositionTag(TastyFormat.REPEATED)
        case BIND          extends TreePositionTag(TastyFormat.BIND)
        case ALTERNATIVE   extends TreePositionTag(TastyFormat.ALTERNATIVE)
        case UNAPPLY       extends TreePositionTag(TastyFormat.UNAPPLY)
        case CASEDEF       extends TreePositionTag(TastyFormat.CASEDEF)
        case GUARD         extends TreePositionTag(TastyFormat.GUARD)
        case SUPER         extends TreePositionTag(TastyFormat.SUPER)
        case QUOTE         extends TreePositionTag(TastyFormat.QUOTE)
        case SPLICE        extends TreePositionTag(TastyFormat.SPLICE)
        case APPLYsigpoly  extends TreePositionTag(TastyFormat.APPLYsigpoly)
        case QUOTEPATTERN  extends TreePositionTag(TastyFormat.QUOTEPATTERN)
        case SPLICEPATTERN extends TreePositionTag(TastyFormat.SPLICEPATTERN)
        case HOLE          extends TreePositionTag(TastyFormat.HOLE)
        // Category 2/3/4 term nodes
        case SHAREDterm    extends TreePositionTag(TastyFormat.SHAREDterm)
        case THIS          extends TreePositionTag(TastyFormat.THIS)
        case QUALTHIS      extends TreePositionTag(TastyFormat.QUALTHIS)
        case THROW         extends TreePositionTag(TastyFormat.THROW)
        case IMPLICITarg   extends TreePositionTag(TastyFormat.IMPLICITarg)
        case BOUNDED       extends TreePositionTag(TastyFormat.BOUNDED)
        case EXPLICITtpt   extends TreePositionTag(TastyFormat.EXPLICITtpt)
        case ELIDED        extends TreePositionTag(TastyFormat.ELIDED)
        case IDENT         extends TreePositionTag(TastyFormat.IDENT)
        case SELECT        extends TreePositionTag(TastyFormat.SELECT)
        case TERMREFsymbol extends TreePositionTag(TastyFormat.TERMREFsymbol)
        case TERMREF       extends TreePositionTag(TastyFormat.TERMREF)
        case TERMREFdirect extends TreePositionTag(TastyFormat.TERMREFdirect)
        case TERMREFpkg    extends TreePositionTag(TastyFormat.TERMREFpkg)
        case SELFDEF       extends TreePositionTag(TastyFormat.SELFDEF)
        case NAMEDARG      extends TreePositionTag(TastyFormat.NAMEDARG)
        case NEW           extends TreePositionTag(TastyFormat.NEW)
        case IMPORTED      extends TreePositionTag(TastyFormat.IMPORTED)
        case RENAMED       extends TreePositionTag(TastyFormat.RENAMED)
    end TreePositionTag

    object TreePositionTag:
        val position: String = "tree"

        private val byRawMap: Map[Int, TreePositionTag] =
            values.map(t => t.raw -> t).toMap

        /** Convert a raw byte to the typed tag; throws `TastyErrorException(UnknownTagInPosition)` on unrecognised input. */
        def from(raw: Int): TreePositionTag =
            byRawMap.getOrElse(
                raw,
                throw new TastyErrorException(TastyError.UnknownTagInPosition(raw, position))
            )
    end TreePositionTag

    // ─────────────────────────────────────────────────────────────────────────
    // TptPositionTag: type-position tree (TPT) tags
    // ─────────────────────────────────────────────────────────────────────────

    /** All valid TPT-position tags (type-syntactic tree nodes). */
    enum TptPositionTag(val raw: Int) derives CanEqual:
        case IDENTtpt      extends TptPositionTag(TastyFormat.IDENTtpt)
        case SELECTtpt     extends TptPositionTag(TastyFormat.SELECTtpt)
        case TYPEREFsymbol extends TptPositionTag(TastyFormat.TYPEREFsymbol)
        case TYPEREF       extends TptPositionTag(TastyFormat.TYPEREF)
        case TYPEREFdirect extends TptPositionTag(TastyFormat.TYPEREFdirect)
        case TYPEREFpkg    extends TptPositionTag(TastyFormat.TYPEREFpkg)
        case SINGLETONtpt  extends TptPositionTag(TastyFormat.SINGLETONtpt)
        case REFINEDtpt    extends TptPositionTag(TastyFormat.REFINEDtpt)
        case APPLIEDtpt    extends TptPositionTag(TastyFormat.APPLIEDtpt)
        case LAMBDAtpt     extends TptPositionTag(TastyFormat.LAMBDAtpt)
        case TYPEBOUNDStpt extends TptPositionTag(TastyFormat.TYPEBOUNDStpt)
        case ANNOTATEDtpt  extends TptPositionTag(TastyFormat.ANNOTATEDtpt)
        case MATCHtpt      extends TptPositionTag(TastyFormat.MATCHtpt)
        case BYNAMEtpt     extends TptPositionTag(TastyFormat.BYNAMEtpt)
        case SHAREDtype    extends TptPositionTag(TastyFormat.SHAREDtype)
        case TERMREFin     extends TptPositionTag(TastyFormat.TERMREFin)
        case TYPEREFin     extends TptPositionTag(TastyFormat.TYPEREFin)
        case SELECTin      extends TptPositionTag(TastyFormat.SELECTin)
    end TptPositionTag

    object TptPositionTag:
        val position: String = "tpt"

        private val byRawMap: Map[Int, TptPositionTag] =
            values.map(t => t.raw -> t).toMap

        /** Convert a raw byte to the typed tag; throws `TastyErrorException(UnknownTagInPosition)` on unrecognised input. */
        def from(raw: Int): TptPositionTag =
            byRawMap.getOrElse(
                raw,
                throw new TastyErrorException(TastyError.UnknownTagInPosition(raw, position))
            )
    end TptPositionTag

    // ─────────────────────────────────────────────────────────────────────────
    // ConstantTag: tags that appear in constant-literal positions
    // ─────────────────────────────────────────────────────────────────────────

    /** All valid constant-position tags. */
    enum ConstantTag(val raw: Int) derives CanEqual:
        case UNITconst   extends ConstantTag(TastyFormat.UNITconst)
        case FALSEconst  extends ConstantTag(TastyFormat.FALSEconst)
        case TRUEconst   extends ConstantTag(TastyFormat.TRUEconst)
        case NULLconst   extends ConstantTag(TastyFormat.NULLconst)
        case BYTEconst   extends ConstantTag(TastyFormat.BYTEconst)
        case SHORTconst  extends ConstantTag(TastyFormat.SHORTconst)
        case CHARconst   extends ConstantTag(TastyFormat.CHARconst)
        case INTconst    extends ConstantTag(TastyFormat.INTconst)
        case LONGconst   extends ConstantTag(TastyFormat.LONGconst)
        case FLOATconst  extends ConstantTag(TastyFormat.FLOATconst)
        case DOUBLEconst extends ConstantTag(TastyFormat.DOUBLEconst)
        case STRINGconst extends ConstantTag(TastyFormat.STRINGconst)
        case CLASSconst  extends ConstantTag(TastyFormat.CLASSconst)
    end ConstantTag

    object ConstantTag:
        val position: String = "constant"

        private val byRawMap: Map[Int, ConstantTag] =
            values.map(t => t.raw -> t).toMap

        /** Convert a raw byte to the typed tag; throws `TastyErrorException(UnknownTagInPosition)` on unrecognised input. */
        def from(raw: Int): ConstantTag =
            byRawMap.getOrElse(
                raw,
                throw new TastyErrorException(TastyError.UnknownTagInPosition(raw, position))
            )
    end ConstantTag

    // ─────────────────────────────────────────────────────────────────────────
    // ModifierTag: tags that appear in modifier-list positions
    // ─────────────────────────────────────────────────────────────────────────

    /** All valid modifier-position tags. */
    enum ModifierTag(val raw: Int) derives CanEqual:
        // Category 1: single-byte modifier flags
        case PRIVATE       extends ModifierTag(TastyFormat.PRIVATE)
        case PROTECTED     extends ModifierTag(TastyFormat.PROTECTED)
        case ABSTRACT      extends ModifierTag(TastyFormat.ABSTRACT)
        case FINAL         extends ModifierTag(TastyFormat.FINAL)
        case SEALED        extends ModifierTag(TastyFormat.SEALED)
        case CASE          extends ModifierTag(TastyFormat.CASE)
        case IMPLICIT      extends ModifierTag(TastyFormat.IMPLICIT)
        case LAZY          extends ModifierTag(TastyFormat.LAZY)
        case OVERRIDE      extends ModifierTag(TastyFormat.OVERRIDE)
        case INLINEPROXY   extends ModifierTag(TastyFormat.INLINEPROXY)
        case INLINE        extends ModifierTag(TastyFormat.INLINE)
        case STATIC        extends ModifierTag(TastyFormat.STATIC)
        case OBJECT        extends ModifierTag(TastyFormat.OBJECT)
        case TRAIT         extends ModifierTag(TastyFormat.TRAIT)
        case ENUM          extends ModifierTag(TastyFormat.ENUM)
        case LOCAL         extends ModifierTag(TastyFormat.LOCAL)
        case SYNTHETIC     extends ModifierTag(TastyFormat.SYNTHETIC)
        case ARTIFACT      extends ModifierTag(TastyFormat.ARTIFACT)
        case MUTABLE       extends ModifierTag(TastyFormat.MUTABLE)
        case FIELDaccessor extends ModifierTag(TastyFormat.FIELDaccessor)
        case CASEaccessor  extends ModifierTag(TastyFormat.CASEaccessor)
        case COVARIANT     extends ModifierTag(TastyFormat.COVARIANT)
        case CONTRAVARIANT extends ModifierTag(TastyFormat.CONTRAVARIANT)
        case HASDEFAULT    extends ModifierTag(TastyFormat.HASDEFAULT)
        case STABLE        extends ModifierTag(TastyFormat.STABLE)
        case MACRO         extends ModifierTag(TastyFormat.MACRO)
        case ERASED        extends ModifierTag(TastyFormat.ERASED)
        case OPAQUE        extends ModifierTag(TastyFormat.OPAQUE)
        case EXTENSION     extends ModifierTag(TastyFormat.EXTENSION)
        case GIVEN         extends ModifierTag(TastyFormat.GIVEN)
        case PARAMsetter   extends ModifierTag(TastyFormat.PARAMsetter)
        case EXPORTED      extends ModifierTag(TastyFormat.EXPORTED)
        case OPEN          extends ModifierTag(TastyFormat.OPEN)
        case PARAMalias    extends ModifierTag(TastyFormat.PARAMalias)
        case TRANSPARENT   extends ModifierTag(TastyFormat.TRANSPARENT)
        case INFIX         extends ModifierTag(TastyFormat.INFIX)
        case INVISIBLE     extends ModifierTag(TastyFormat.INVISIBLE)
        case EMPTYCLAUSE   extends ModifierTag(TastyFormat.EMPTYCLAUSE)
        case SPLITCLAUSE   extends ModifierTag(TastyFormat.SPLITCLAUSE)
        case TRACKED       extends ModifierTag(TastyFormat.TRACKED)
        case SUBMATCH      extends ModifierTag(TastyFormat.SUBMATCH)
        case INTO          extends ModifierTag(TastyFormat.INTO)
        // Category 3: qualified-modifier nodes (tag + sub-AST)
        case PRIVATEqualified   extends ModifierTag(TastyFormat.PRIVATEqualified)
        case PROTECTEDqualified extends ModifierTag(TastyFormat.PROTECTEDqualified)
        // Category 5: annotation modifier
        case ANNOTATION extends ModifierTag(TastyFormat.ANNOTATION)
    end ModifierTag

    object ModifierTag:
        val position: String = "modifier"

        private val byRawMap: Map[Int, ModifierTag] =
            values.map(t => t.raw -> t).toMap

        /** Convert a raw byte to the typed tag; throws `TastyErrorException(UnknownTagInPosition)` on unrecognised input. */
        def from(raw: Int): ModifierTag =
            byRawMap.getOrElse(
                raw,
                throw new TastyErrorException(TastyError.UnknownTagInPosition(raw, position))
            )
    end ModifierTag

end TagKind
