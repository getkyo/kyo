package kyo.internal.tasty.symbol

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.TastyFormat

/** Decodes TASTy constant leaf nodes into `Tasty.Constant` values.
  *
  * TASTy constant tags:
  *   - UNITconst=2, FALSEconst=3, TRUEconst=4, NULLconst=5: category 1 (tag only, already consumed by caller)
  *   - BYTEconst=67, SHORTconst=68, CHARconst=69, INTconst=70, LONGconst=71, FLOATconst=72, DOUBLEconst=73, STRINGconst=74: category 2 (tag
  *     + Nat)
  *   - CLASSconst=92: category 3 (tag + sub-AST type reference)
  *
  * For `CLASSconst`: the type reference embedded in TASTy is decoded to produce `ClassConst(typeRef: Tasty.Type)` without resolving the
  * class symbol at decode time. This is the cross-platform path: no `java.lang.Class` reference at decode time.
  */
object Constant:

    /** Sentinel symbol used as the type carrier for CLASSconst placeholders. Phase 4 replaces it with the resolved class symbol. */
    private val classConstSentinel: Tasty.Symbol =
        Tasty.Symbol.make(
            Tasty.SymbolKind.Unresolved,
            Tasty.Flags.empty,
            Tasty.Name(""),
            null,
            new kyo.internal.tasty.query.ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            kyo.Maybe.Absent
        )

    /** Decode the constant whose tag byte has already been read. The `view` cursor is positioned immediately after the tag.
      *
      * For category-1 tags (UNITconst, FALSEconst, TRUEconst, NULLconst): no further bytes are consumed. For category-2 tags: one Nat is
      * consumed. For category-3 CLASSconst: the type sub-AST is skipped (type decoding is Phase 4).
      *
      * `names` is the file's name table, used for STRINGconst.
      */
    def fromTastyTag(tag: Int, view: ByteView, names: Array[Tasty.Name])(using Frame): Tasty.Constant < (Sync & Abort[TastyError]) =
        val result =
            try Right(decodeConstant(tag, view, names))
            catch
                case _: ArrayIndexOutOfBoundsException =>
                    Left(TastyError.MalformedSection("ASTs", s"unexpected end while reading constant tag $tag"))
        result match
            case Right(c)  => Sync.defer(c)
            case Left(err) => Abort.fail(err)
    end fromTastyTag

    private def decodeConstant(tag: Int, view: ByteView, names: Array[Tasty.Name]): Tasty.Constant =
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
                Tasty.Constant.StringConst(names(nameRef).asString)
            case TastyFormat.CLASSconst =>
                // Category 3: tag + sub-AST (type reference). Skip the sub-AST for now.
                // The type reference is decoded in Phase 4. Return a placeholder ClassConst.
                skipTree(view)
                // Return a placeholder; Phase 4 resolves the actual type.
                Tasty.Constant.ClassConst(Tasty.Type.Named(classConstSentinel))
            case other =>
                throw new ArrayIndexOutOfBoundsException(s"Unrecognized constant tag $other")

    /** Skip one tree from the view. Used for CLASSconst sub-AST skipping. */
    private def skipTree(view: ByteView): Unit =
        val tag = view.readByte() & 0xff
        skipTreeBody(tag, view)

    private def skipTreeBody(tag: Int, view: ByteView): Unit =
        if tag < TastyFormat.firstLengthTreeTag then
            if tag >= 60 && tag <= 89 then
                // Category 2: tag + Nat
                discard(view.readNat())
            else if tag >= 90 && tag <= 109 then
                // Category 3: tag + sub-AST
                skipTree(view)
            else if tag >= 110 && tag <= 127 then
                // Category 4: tag + Nat + sub-AST
                discard(view.readNat())
                skipTree(view)
            // else category 1: nothing more to skip
        else
            // Category 5: tag + Length + payload
            val end = view.readEnd()
            view.goto(end)

end Constant
