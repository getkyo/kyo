package kyo.internal.reflect.symbol

import kyo.Reflect
import kyo.internal.reflect.tasty.TastyFormat

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

    import Reflect.Flag

    /** Convert a TASTy category-1 modifier tag to a Flags bitmask.
      *
      * Returns Flags.empty for unrecognized or non-modifier tags. The caller is responsible for distinguishing category-1 tags from
      * category-3 qualified-modifier tags (PRIVATEqualified, PROTECTEDqualified) which require separate AST sub-tree skipping.
      */
    def fromTastyModifierTag(tag: Int): Reflect.Flags =
        tag match
            case TastyFormat.PRIVATE       => new Reflect.Flags(Flag.Private.bit)
            case TastyFormat.PROTECTED     => new Reflect.Flags(Flag.Protected.bit)
            case TastyFormat.ABSTRACT      => new Reflect.Flags(Flag.Abstract.bit)
            case TastyFormat.FINAL         => new Reflect.Flags(Flag.Final.bit)
            case TastyFormat.SEALED        => new Reflect.Flags(Flag.Sealed.bit)
            case TastyFormat.CASE          => new Reflect.Flags(Flag.Case.bit)
            case TastyFormat.IMPLICIT      => new Reflect.Flags(Flag.Implicit.bit)
            case TastyFormat.LAZY          => new Reflect.Flags(Flag.Lazy.bit)
            case TastyFormat.OVERRIDE      => new Reflect.Flags(Flag.Override.bit)
            case TastyFormat.INLINEPROXY   => new Reflect.Flags(Flag.InlineProxy.bit)
            case TastyFormat.INLINE        => new Reflect.Flags(Flag.Inline.bit)
            case TastyFormat.STATIC        => new Reflect.Flags(Flag.JavaDefined.bit)
            case TastyFormat.OBJECT        => new Reflect.Flags(Flag.Module.bit)
            case TastyFormat.TRAIT         => new Reflect.Flags(Flag.Trait.bit)
            case TastyFormat.ENUM          => new Reflect.Flags(Flag.Enum.bit)
            case TastyFormat.LOCAL         => new Reflect.Flags(Flag.Local.bit)
            case TastyFormat.SYNTHETIC     => new Reflect.Flags(Flag.Synthetic.bit)
            case TastyFormat.ARTIFACT      => new Reflect.Flags(Flag.Artifact.bit)
            case TastyFormat.MUTABLE       => new Reflect.Flags(Flag.Mutable.bit)
            case TastyFormat.FIELDaccessor => new Reflect.Flags(Flag.FieldAccessor.bit)
            case TastyFormat.CASEaccessor  => new Reflect.Flags(Flag.CaseAccessor.bit)
            case TastyFormat.COVARIANT     => new Reflect.Flags(Flag.CoVariant.bit)
            case TastyFormat.CONTRAVARIANT => new Reflect.Flags(Flag.ContraVariant.bit)
            case TastyFormat.HASDEFAULT    => new Reflect.Flags(Flag.HasDefault.bit)
            case TastyFormat.STABLE        => new Reflect.Flags(Flag.Stable.bit)
            case TastyFormat.MACRO         => new Reflect.Flags(Flag.Macro.bit)
            case TastyFormat.ERASED        => new Reflect.Flags(Flag.Erased.bit)
            case TastyFormat.OPAQUE        => new Reflect.Flags(Flag.Opaque.bit)
            case TastyFormat.EXTENSION     => new Reflect.Flags(Flag.Extension.bit)
            case TastyFormat.GIVEN         => new Reflect.Flags(Flag.Given.bit)
            case TastyFormat.PARAMsetter   => new Reflect.Flags(Flag.PARAMsetter.bit)
            case TastyFormat.EXPORTED      => new Reflect.Flags(Flag.Exported.bit)
            case TastyFormat.OPEN          => new Reflect.Flags(Flag.Open.bit)
            case TastyFormat.PARAMalias    => new Reflect.Flags(Flag.PARAMalias.bit)
            case TastyFormat.TRANSPARENT   => new Reflect.Flags(Flag.Transparent.bit)
            case TastyFormat.INFIX         => new Reflect.Flags(Flag.Infix.bit)
            case TastyFormat.INVISIBLE     => new Reflect.Flags(Flag.Invisible.bit)
            case TastyFormat.INTO          => new Reflect.Flags(Flag.Into.bit)
            case TastyFormat.TRACKED       => new Reflect.Flags(Flag.Tracked.bit)
            case _                         => Reflect.Flags.empty

    /** Convert JVM access flags (from a classfile) to Flags bitmask.
      *
      * JVM access flag constants per JVMS Table 4.1-B.
      */
    def fromJvmAccessFlags(acc: Int): Reflect.Flags =
        var bits = 0L
        if (acc & 0x0001) != 0 then bits |= Flag.Public.bit
        if (acc & 0x0002) != 0 then bits |= Flag.Private.bit
        if (acc & 0x0004) != 0 then bits |= Flag.Protected.bit
        if (acc & 0x0010) != 0 then bits |= Flag.Final.bit
        if (acc & 0x0200) != 0 then bits |= Flag.Abstract.bit
        if (acc & 0x0200) != 0 then bits |= Flag.Trait.bit // ACC_INTERFACE -> Trait
        if (acc & 0x1000) != 0 then bits |= Flag.Synthetic.bit
        if (acc & 0x4000) != 0 then bits |= Flag.Enum.bit
        if (acc & 0x0008) != 0 then bits |= Flag.JavaDefined.bit // ACC_STATIC
        // Flag.JavaRecord is NOT set here; set by ClassfileUnpickler when Record attribute found.
        new Reflect.Flags(bits)
    end fromJvmAccessFlags

end Flags
