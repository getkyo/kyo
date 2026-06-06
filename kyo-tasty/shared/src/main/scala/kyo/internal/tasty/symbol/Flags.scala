package kyo.internal.tasty.symbol

import kyo.Tasty
import kyo.internal.tasty.reader.TastyFormat

/** TASTy modifier tag to Flags conversions, and JVM access flag conversions.
  *
  * The full ~42-flag set is defined here.
  *
  * Flag bit assignments: Bits 0-15: core flags (Inline, Private, Protected, Public, Final, Sealed, Abstract, Given, Implicit, Opaque,
  * Case, Module, Synthetic, JavaDefined, Enum, JavaRecord). Bits 16+: extended flags (Open, ParamAccessor, Lazy, Override, Mutable,
  * Erased, Tracked, Tailrec, Infix, Transparent, Trait, CaseAccessor, FieldAccessor, Macro, InlineProxy, Extension, Exported, CoVariant,
  * ContraVariant, HasDefault, Stable, Local, Artifact, Invisible, Into, PARAMsetter, PARAMalias).
  */
object Flags:

    import Tasty.Flag

    /** Convert a TASTy category-1 modifier tag to a Flags bitmask.
      *
      * Returns Flags.empty for unrecognized or non-modifier tags. The caller is responsible for distinguishing category-1 tags from
      * category-3 qualified-modifier tags (PRIVATEqualified, PROTECTEDqualified) which require separate AST sub-tree skipping.
      */
    def fromTastyModifierTag(tag: Int): Tasty.Flags =
        tag match
            case TastyFormat.PRIVATE       => Tasty.Flags(Flag.Private)
            case TastyFormat.PROTECTED     => Tasty.Flags(Flag.Protected)
            case TastyFormat.ABSTRACT      => Tasty.Flags(Flag.Abstract)
            case TastyFormat.FINAL         => Tasty.Flags(Flag.Final)
            case TastyFormat.SEALED        => Tasty.Flags(Flag.Sealed)
            case TastyFormat.CASE          => Tasty.Flags(Flag.Case)
            case TastyFormat.IMPLICIT      => Tasty.Flags(Flag.Implicit)
            case TastyFormat.LAZY          => Tasty.Flags(Flag.Lazy)
            case TastyFormat.OVERRIDE      => Tasty.Flags(Flag.Override)
            case TastyFormat.INLINEPROXY   => Tasty.Flags(Flag.InlineProxy)
            case TastyFormat.INLINE        => Tasty.Flags(Flag.Inline)
            case TastyFormat.STATIC        => Tasty.Flags(Flag.JavaDefined)
            case TastyFormat.OBJECT        => Tasty.Flags(Flag.Module)
            case TastyFormat.TRAIT         => Tasty.Flags(Flag.Trait)
            case TastyFormat.ENUM          => Tasty.Flags(Flag.Enum)
            case TastyFormat.LOCAL         => Tasty.Flags(Flag.Local)
            case TastyFormat.SYNTHETIC     => Tasty.Flags(Flag.Synthetic)
            case TastyFormat.ARTIFACT      => Tasty.Flags(Flag.Artifact)
            case TastyFormat.MUTABLE       => Tasty.Flags(Flag.Mutable)
            case TastyFormat.FIELDaccessor => Tasty.Flags(Flag.FieldAccessor)
            case TastyFormat.CASEaccessor  => Tasty.Flags(Flag.CaseAccessor)
            case TastyFormat.COVARIANT     => Tasty.Flags(Flag.CoVariant)
            case TastyFormat.CONTRAVARIANT => Tasty.Flags(Flag.ContraVariant)
            case TastyFormat.HASDEFAULT    => Tasty.Flags(Flag.HasDefault)
            case TastyFormat.STABLE        => Tasty.Flags(Flag.Stable)
            case TastyFormat.MACRO         => Tasty.Flags(Flag.Macro)
            case TastyFormat.ERASED        => Tasty.Flags(Flag.Erased)
            case TastyFormat.OPAQUE        => Tasty.Flags(Flag.Opaque)
            case TastyFormat.EXTENSION     => Tasty.Flags(Flag.Extension)
            case TastyFormat.GIVEN         => Tasty.Flags(Flag.Given)
            case TastyFormat.PARAMsetter   => Tasty.Flags(Flag.PARAMsetter)
            case TastyFormat.EXPORTED      => Tasty.Flags(Flag.Exported)
            case TastyFormat.OPEN          => Tasty.Flags(Flag.Open)
            case TastyFormat.PARAMalias    => Tasty.Flags(Flag.PARAMalias)
            case TastyFormat.TRANSPARENT   => Tasty.Flags(Flag.Transparent)
            case TastyFormat.INFIX         => Tasty.Flags(Flag.Infix)
            case TastyFormat.INVISIBLE     => Tasty.Flags(Flag.Invisible)
            case TastyFormat.INTO          => Tasty.Flags(Flag.Into)
            case TastyFormat.TRACKED       => Tasty.Flags(Flag.Tracked)
            case _                         => Tasty.Flags.empty

    /** Convert JVM access flags (from a classfile) to Flags bitmask.
      *
      * JVM access flag constants per JVMS Table 4.1-B.
      */
    def fromJvmAccessFlags(acc: Int): Tasty.Flags =
        var bits = 0L
        if (acc & 0x0001) != 0 then bits |= Flag.bits(Flag.Public)    // ACC_PUBLIC
        if (acc & 0x0002) != 0 then bits |= Flag.bits(Flag.Private)   // ACC_PRIVATE
        if (acc & 0x0004) != 0 then bits |= Flag.bits(Flag.Protected) // ACC_PROTECTED
        if (acc & 0x0010) != 0 then bits |= Flag.bits(Flag.Final)     // ACC_FINAL
        if (acc & 0x0400) != 0 then bits |= Flag.bits(Flag.Abstract)  // ACC_ABSTRACT (0x0400), not ACC_INTERFACE (0x0200)
        if (acc & 0x0200) != 0 then bits |= Flag.bits(Flag.Trait)     // ACC_INTERFACE -> Trait
        if (acc & 0x0008) != 0 then bits |= Flag.bits(Flag.Static)    // ACC_STATIC
        if (acc & 0x1000) != 0 then bits |= Flag.bits(Flag.Synthetic) // ACC_SYNTHETIC
        if (acc & 0x4000) != 0 then bits |= Flag.bits(Flag.Enum)      // ACC_ENUM
        // Flag.JavaRecord is NOT set here; set by ClassfileUnpickler when Record attribute found.
        // Flag.JavaDefined is set unconditionally by ClassfileUnpickler for every Java symbol.
        Tasty.Flags.fromBits(bits)
    end fromJvmAccessFlags

end Flags
