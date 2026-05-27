package kyo.internal.tasty.symbol

import kyo.Tasty
import kyo.internal.tasty.reader.TastyFormat

/** TASTy modifier tag to Flags conversions, and JVM access flag conversions.
  *
  * The full ~42-flag set is defined here. Phase 0 seeded bits 0-15; Phase 3 adds bits 16+.
  *
  * Flag bit assignments: Bits 0-15: Phase 0 flags (Inline, Private, Protected, Public, Final, Sealed, Abstract, Given, Implicit, Opaque,
  * Case, Module, Synthetic, JavaDefined, Enum, JavaRecord) Bits 16+: Phase 3 additions (Open, ParamAccessor, Lazy, Override, Mutable,
  * Erased, Tracked, Tailrec, Infix, Transparent, Trait, CaseAccessor, FieldAccessor, Macro, InlineProxy, Extension, Exported, CoVariant,
  * ContraVariant, HasDefault, Stable, Local, Artifact, Invisible, Into, PARAMsetter, PARAMalias)
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
            case TastyFormat.PRIVATE       => new Tasty.Flags(Flag.Private.bit)
            case TastyFormat.PROTECTED     => new Tasty.Flags(Flag.Protected.bit)
            case TastyFormat.ABSTRACT      => new Tasty.Flags(Flag.Abstract.bit)
            case TastyFormat.FINAL         => new Tasty.Flags(Flag.Final.bit)
            case TastyFormat.SEALED        => new Tasty.Flags(Flag.Sealed.bit)
            case TastyFormat.CASE          => new Tasty.Flags(Flag.Case.bit)
            case TastyFormat.IMPLICIT      => new Tasty.Flags(Flag.Implicit.bit)
            case TastyFormat.LAZY          => new Tasty.Flags(Flag.Lazy.bit)
            case TastyFormat.OVERRIDE      => new Tasty.Flags(Flag.Override.bit)
            case TastyFormat.INLINEPROXY   => new Tasty.Flags(Flag.InlineProxy.bit)
            case TastyFormat.INLINE        => new Tasty.Flags(Flag.Inline.bit)
            case TastyFormat.STATIC        => new Tasty.Flags(Flag.JavaDefined.bit)
            case TastyFormat.OBJECT        => new Tasty.Flags(Flag.Module.bit)
            case TastyFormat.TRAIT         => new Tasty.Flags(Flag.Trait.bit)
            case TastyFormat.ENUM          => new Tasty.Flags(Flag.Enum.bit)
            case TastyFormat.LOCAL         => new Tasty.Flags(Flag.Local.bit)
            case TastyFormat.SYNTHETIC     => new Tasty.Flags(Flag.Synthetic.bit)
            case TastyFormat.ARTIFACT      => new Tasty.Flags(Flag.Artifact.bit)
            case TastyFormat.MUTABLE       => new Tasty.Flags(Flag.Mutable.bit)
            case TastyFormat.FIELDaccessor => new Tasty.Flags(Flag.FieldAccessor.bit)
            case TastyFormat.CASEaccessor  => new Tasty.Flags(Flag.CaseAccessor.bit)
            case TastyFormat.COVARIANT     => new Tasty.Flags(Flag.CoVariant.bit)
            case TastyFormat.CONTRAVARIANT => new Tasty.Flags(Flag.ContraVariant.bit)
            case TastyFormat.HASDEFAULT    => new Tasty.Flags(Flag.HasDefault.bit)
            case TastyFormat.STABLE        => new Tasty.Flags(Flag.Stable.bit)
            case TastyFormat.MACRO         => new Tasty.Flags(Flag.Macro.bit)
            case TastyFormat.ERASED        => new Tasty.Flags(Flag.Erased.bit)
            case TastyFormat.OPAQUE        => new Tasty.Flags(Flag.Opaque.bit)
            case TastyFormat.EXTENSION     => new Tasty.Flags(Flag.Extension.bit)
            case TastyFormat.GIVEN         => new Tasty.Flags(Flag.Given.bit)
            case TastyFormat.PARAMsetter   => new Tasty.Flags(Flag.PARAMsetter.bit)
            case TastyFormat.EXPORTED      => new Tasty.Flags(Flag.Exported.bit)
            case TastyFormat.OPEN          => new Tasty.Flags(Flag.Open.bit)
            case TastyFormat.PARAMalias    => new Tasty.Flags(Flag.PARAMalias.bit)
            case TastyFormat.TRANSPARENT   => new Tasty.Flags(Flag.Transparent.bit)
            case TastyFormat.INFIX         => new Tasty.Flags(Flag.Infix.bit)
            case TastyFormat.INVISIBLE     => new Tasty.Flags(Flag.Invisible.bit)
            case TastyFormat.INTO          => new Tasty.Flags(Flag.Into.bit)
            case TastyFormat.TRACKED       => new Tasty.Flags(Flag.Tracked.bit)
            case _                         => Tasty.Flags.empty

    /** Convert JVM access flags (from a classfile) to Flags bitmask.
      *
      * JVM access flag constants per JVMS Table 4.1-B.
      */
    def fromJvmAccessFlags(acc: Int): Tasty.Flags =
        var bits = 0L
        if (acc & 0x0001) != 0 then bits |= Flag.Public.bit    // ACC_PUBLIC
        if (acc & 0x0002) != 0 then bits |= Flag.Private.bit   // ACC_PRIVATE
        if (acc & 0x0004) != 0 then bits |= Flag.Protected.bit // ACC_PROTECTED
        if (acc & 0x0010) != 0 then bits |= Flag.Final.bit     // ACC_FINAL
        if (acc & 0x0400) != 0 then bits |= Flag.Abstract.bit  // ACC_ABSTRACT (0x0400), not ACC_INTERFACE (0x0200)
        if (acc & 0x0200) != 0 then bits |= Flag.Trait.bit     // ACC_INTERFACE -> Trait
        if (acc & 0x0008) != 0 then bits |= Flag.Static.bit    // ACC_STATIC
        if (acc & 0x1000) != 0 then bits |= Flag.Synthetic.bit // ACC_SYNTHETIC
        if (acc & 0x4000) != 0 then bits |= Flag.Enum.bit      // ACC_ENUM
        // Flag.JavaRecord is NOT set here; set by ClassfileUnpickler when Record attribute found.
        // Flag.JavaDefined is set unconditionally by ClassfileUnpickler for every Java symbol.
        new Tasty.Flags(bits)
    end fromJvmAccessFlags

end Flags
