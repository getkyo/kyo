package kyo.internal.tasty.symbol

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TypeUnpickler

/** Decodes TASTy constant leaf nodes into `Tasty.Constant` values.
  *
  * TASTy constant tags:
  *   - UNITconst=2, FALSEconst=3, TRUEconst=4, NULLconst=5: category 1 (tag only, already consumed by caller)
  *   - BYTEconst=67, SHORTconst=68, CHARconst=69, INTconst=70, LONGconst=71, FLOATconst=72, DOUBLEconst=73, STRINGconst=74: category 2 (tag
  *     + Nat)
  *   - CLASSconst=92: category 3 (tag + sub-AST type reference)
  *
  * For `CLASSconst`: the embedded type sub-AST is decoded via TypeUnpickler.readTypeIntoSession to produce
  * `ClassConst(typeRef: Tasty.Type)`. No class symbol resolution at decode time; the type carries the class identity. Cross-platform: no
  * `java.lang.Class` reference.
  */
object Constant:

    /** Decode the constant whose tag byte has already been read. The `view` cursor is positioned immediately after the tag.
      *
      * For category-1 tags (UNITconst, FALSEconst, TRUEconst, NULLconst): no further bytes are consumed. For category-2 tags: one Nat is
      * consumed. For category-3 CLASSconst: the type sub-AST is decoded via `session` and the resulting `Tasty.Type` is wrapped in
      * `ClassConst`.
      *
      * `session` carries the name table, addrMap, arena, and home classpath reference used for type decoding.
      */
    def fromTastyTag(tag: Int, view: ByteView, session: TypeUnpickler.DecodeSession)(using
        frame: Frame
    ): Tasty.Constant < (Sync & Abort[TastyError]) =
        val result =
            try Right(decodeConstant(tag, view, session, frame))
            catch
                case _: ArrayIndexOutOfBoundsException =>
                    Left(TastyError.MalformedSection("ASTs", s"unexpected end while reading constant tag $tag", view.position))
        result match
            case Right(c)  => Sync.defer(c)
            case Left(err) => Abort.fail(err)
    end fromTastyTag

    private def decodeConstant(tag: Int, view: ByteView, session: TypeUnpickler.DecodeSession, frame: Frame): Tasty.Constant =
        // Unsafe: Name.asString requires AllowUnsafe; embraced here in the decode-pass context (§839 case 3).
        import AllowUnsafe.embrace.danger
        tag match
            case TastyFormat.UNITconst  => Tasty.Constant.UnitConst
            case TastyFormat.FALSEconst => Tasty.Constant.BooleanConst(false)
            case TastyFormat.TRUEconst  => Tasty.Constant.BooleanConst(true)
            case TastyFormat.NULLconst  => Tasty.Constant.NullConst
            case TastyFormat.BYTEconst =>
                val v = view.readNat()
                Tasty.Constant.ByteConst(v.toByte)
            case TastyFormat.SHORTconst =>
                val v = view.readNat()
                Tasty.Constant.ShortConst(v.toShort)
            case TastyFormat.CHARconst =>
                val v = view.readNat()
                Tasty.Constant.CharConst(v.toChar)
            case TastyFormat.INTconst =>
                val v = view.readInt()
                Tasty.Constant.IntConst(v)
            case TastyFormat.LONGconst =>
                val lo = view.readLongNat()
                Tasty.Constant.LongConst(lo)
            case TastyFormat.FLOATconst =>
                val bits = view.readNat()
                Tasty.Constant.FloatConst(java.lang.Float.intBitsToFloat(bits))
            case TastyFormat.DOUBLEconst =>
                val bits = view.readLongNat()
                Tasty.Constant.DoubleConst(java.lang.Double.longBitsToDouble(bits))
            case TastyFormat.STRINGconst =>
                val nameRef = view.readNat()
                Tasty.Constant.StringConst(session.names(nameRef).asString)
            case TastyFormat.CLASSconst =>
                // Category 3: tag + sub-AST (type reference).
                // Decode the embedded type sub-AST via the shared session; no placeholder needed.
                val tpe = TypeUnpickler.readTypeIntoSession(view, session)(using frame)
                Tasty.Constant.ClassConst(tpe)
            case other =>
                throw new ArrayIndexOutOfBoundsException(s"Unrecognized constant tag $other")
        end match
    end decodeConstant

end Constant
