package kyo.internal.tasty.reader

import kyo.*
import kyo.Tasty.Name.asString
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.type_.TypeArena
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** Decodes TASTy body byte slices into Tasty.Tree values.
  *
  * Called synchronously from Symbol._bodyOnce init lambdas. Does not use Kyo effects; throws DecodeException or
  * ArrayIndexOutOfBoundsException on error.
  *
  * Design: each decode session uses an independent treeAddrCache keyed by absolute byte address in the section. SHAREDterm references an
  * address in the cache; decoding any node first records startAddr -> result in the cache.
  *
  * Cross-platform: no JVM-only APIs.
  */
object TreeUnpickler:

    /** Thrown on malformed or unsupported TASTy body content.
      *
      * @param byteOffset
      *   byte position in the ASTs section where the error was detected; 0L when the position is unavailable (e.g. when thrown from a
      *   context that does not hold a ByteView cursor).
      */
    final class DecodeException(msg: String, val byteOffset: Long) extends RuntimeException(msg)

    /** Decode a single Term tree from raw annotation pickle bytes.
      *
      * Called eagerly by TypeUnpickler.ANNOTATEDtype during Pass B. The pickle is a self-contained slice of a TASTy AST section
      * corresponding to one annotation term (typically a `New` or `Apply` expression). Throws DecodeException or
      * ArrayIndexOutOfBoundsException on malformed input; the caller catches and records the error in DecodeSession.annotationDecodeErrors.
      *
      * @param pickle
      *   raw annotation term bytes extracted from the ASTs section.
      * @param names
      *   the file-level name array from NameUnpickler.
      * @param addrMap
      *   map from TASTy byte address to Symbol, used for TERMREF/SELECT resolution.
      * @param sectionBytes
      *   full ASTs section bytes (for SHAREDtype re-decode on cache miss).
      * @param sectionOffset
      *   absolute byte offset of the ASTs section within the TASTy file.
      */
    private[kyo] def decodeAnnotationTerm(
        pickle: Array[Byte],
        names: Array[Tasty.Name],
        addrMap: scala.collection.Map[Int, Tasty.Symbol],
        sectionBytes: Array[Byte],
        sectionOffset: Int
    )(using AllowUnsafe): Tasty.Tree =
        val view       = ByteView(pickle, 0, pickle.length)
        val dummyArena = TypeArena.canonical()
        val typeSession =
            new TypeUnpickler.TreeTypeSession(names, addrMap, dummyArena, sectionBytes, sectionOffset)
        val treeAddrCache = new mutable.HashMap[Int, Tasty.Tree]()
        val decodeCtx     = DecodeCtx(names, addrMap, typeSession, treeAddrCache)
        readTree(view, decodeCtx)
    end decodeAnnotationTerm

    /** Synchronously decode the body byte slice for `sym` into a Tree.
      *
      * plan: phase-02 new signature; accepts SymbolBody and a symbol lookup function to resolve SymbolId -> Symbol for IDENT/SELECT
      * references. Phase 04 wires this via cp.decodeBody.
      *
      * @param body
      *   the SymbolBody carrying sectionBytes, bodyStart, bodyEnd, names, sectionOffset, and addrMap.
      * @param sym
      *   the symbol whose body is being decoded; used to determine body-slice layout.
      * @param symbolLookup
      *   function from SymbolId.value (Int) to Tasty.Symbol; used to build the addrMap for type decode.
      */
    def decodeSync(
        body: Tasty.SymbolBody,
        sym: Tasty.Symbol,
        symbolLookup: Int => Tasty.Symbol
    )(using AllowUnsafe): Tasty.Tree =
        // Unsafe: toArrayUnsafe returns the backing array of the Span without copying.
        // This is safe here because the array is read-only within this decode operation.
        val names = body.names.toArrayUnsafe
        val bytes = body.sectionBytes.toArrayUnsafe
        if bytes == null || bytes.isEmpty then
            throw new DecodeException("body bytes not available (snapshot-loaded symbol)", 0L)
        end if
        val view = ByteView(bytes, body.bodyStart, body.bodyEnd)
        // Build addrMap: convert IntMap[SymbolId] -> Map[Int, Symbol] via symbolLookup.
        val addrMap: scala.collection.Map[Int, Tasty.Symbol] =
            body.addrMap.map { case (addr, sid) => (addr, symbolLookup(sid.value)) }
        val treeAddrCache = new mutable.HashMap[Int, Tasty.Tree]()
        val dummyArena    = TypeArena.canonical()
        val typeSession   = new TypeUnpickler.TreeTypeSession(names, addrMap, dummyArena, bytes, body.sectionOffset)
        val ctx = DecodeCtx(
            names,
            addrMap,
            typeSession,
            treeAddrCache
        )
        decodeSymBody(sym, view, body.bodyEnd, ctx)
    end decodeSync

    /** Decode a body slice whose layout depends on the symbol kind.
      *
      *   - Val/Lazy/Module/Param: slice = `declared_type rhs_tree? modifier*`; return ValDef.
      *   - Method: slice = `TYPEPARAM* PARAM* result_type rhs_tree? modifier*`; return DefDef.
      *   - Class/Trait/Object/Enum/EnumCase/Interface/Abstract/Opaque: slice = TEMPLATE body content; return ClassDef.
      *   - Package: slice = `stat*`; return PackageDef.
      *   - Other (TypeAlias etc.): try readTree fallback; return whatever the slice contains.
      */
    private def decodeSymBody(
        sym: Tasty.Symbol,
        view: ByteView,
        end: Int,
        ctx: DecodeCtx
    )(using AllowUnsafe): Tasty.Tree =
        import Tasty.SymbolKind.*
        sym.kind match
            case Val | Var | Field | Parameter =>
                // Slice: declared_type rhs_tree? modifier*
                val tpt = readTypeOrSkip(view, end, ctx)
                val rhs = readOptionalRhs(view, end, ctx)
                view.goto(end)
                Tasty.Tree.ValDef(sym, tpt, rhs)
            case Method =>
                // Slice: TYPEPARAM* PARAM* result_type rhs_tree? modifier*
                val (paramss, tpt) = readDefDefParamsAndTpt(view, end, ctx)
                val rhs            = readOptionalRhs(view, end, ctx)
                view.goto(end)
                Tasty.Tree.DefDef(sym, paramss, tpt, rhs)
            case Class | Trait | Object | OpaqueType =>
                // Slice starts at TEMPLATE payload body (parents already stripped by AstUnpickler).
                val tmpl = readTemplateBody(view, end, ctx)
                Tasty.Tree.ClassDef(sym, tmpl)
            case Package =>
                val stats = readTreesUntil(view, end, ctx)
                view.goto(end)
                Tasty.Tree.PackageDef(sym, stats)
            case _ =>
                // TypeAlias, AbstractType, TypeParam, Unresolved, etc.: try generic readTree.
                if view.position < end then readTree(view, ctx)
                else Tasty.Tree.Unknown(0, 0)
        end match
    end decodeSymBody

    // ── Internal decode context ───────────────────────────────────────────────

    final private class DecodeCtx(
        val names: Array[Tasty.Name],
        val addrMap: scala.collection.Map[Int, Tasty.Symbol],
        val typeSession: TypeUnpickler.TreeTypeSession,
        val treeAddrCache: mutable.HashMap[Int, Tasty.Tree]
    )

    // ── Tree dispatch ─────────────────────────────────────────────────────────

    /** Read one tree node; tag byte not yet consumed. Records startAddr in treeAddrCache. */
    private def readTree(view: ByteView, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Tree =
        val startAddr = view.positionInt
        val tag       = view.readByte() & 0xff
        val result    = decodeTreeTag(tag, startAddr, view, ctx)
        if tag != TastyFormat.SHAREDterm && tag != TastyFormat.SHAREDtype then
            ctx.treeAddrCache(startAddr) = result
        result
    end readTree

    /** Read all trees until view.position >= end. */
    private def readTreesUntil(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): Chunk[Tasty.Tree] =
        val buf = new mutable.ArrayBuffer[Tasty.Tree]()
        while view.position < end do
            // Skip standalone modifier tags that can appear between tree nodes.
            val peek = view.peekByte(view.position) & 0xff
            if isModifierTag(peek) then
                discard(view.readByte())
                if peek == TastyFormat.PRIVATEqualified || peek == TastyFormat.PROTECTEDqualified then
                    skipOneTree(view)
            else
                buf += readTree(view, ctx)
            end if
        end while
        Chunk.from(buf.toSeq)
    end readTreesUntil

    /** Dispatch on the tag byte (already consumed). */
    private def decodeTreeTag(tag: Int, startAddr: Int, view: ByteView, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Tree =
        tag match

            // ── Category 1: tag only (1-59) -- literal constants & modifiers ─────
            case TastyFormat.UNITconst  => Tasty.Tree.Literal(Tasty.Constant.UnitConst)
            case TastyFormat.FALSEconst => Tasty.Tree.Literal(Tasty.Constant.BooleanConst(false))
            case TastyFormat.TRUEconst  => Tasty.Tree.Literal(Tasty.Constant.BooleanConst(true))
            case TastyFormat.NULLconst  => Tasty.Tree.Literal(Tasty.Constant.NullConst)

            // ── Category 2: tag + Nat (60-89) ────────────────────────────────────

            case TastyFormat.SHAREDterm =>
                val addr = view.readNat()
                Tasty.Tree.Shared(addr)

            case TastyFormat.TERMREFdirect =>
                val addr = view.readNat()
                Tasty.Tree.TermRefDirect(addr)

            case TastyFormat.TERMREFpkg =>
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                Tasty.Tree.TermRefPkg(name)

            case TastyFormat.BYTEconst =>
                Tasty.Tree.Literal(Tasty.Constant.ByteConst(view.readNat().toByte))
            case TastyFormat.SHORTconst =>
                Tasty.Tree.Literal(Tasty.Constant.ShortConst(view.readNat().toShort))
            case TastyFormat.CHARconst =>
                Tasty.Tree.Literal(Tasty.Constant.CharConst(view.readNat().toChar))
            case TastyFormat.INTconst =>
                Tasty.Tree.Literal(Tasty.Constant.IntConst(view.readInt()))
            case TastyFormat.LONGconst =>
                Tasty.Tree.Literal(Tasty.Constant.LongConst(view.readLongNat()))
            case TastyFormat.FLOATconst =>
                Tasty.Tree.Literal(Tasty.Constant.FloatConst(java.lang.Float.intBitsToFloat(view.readNat())))
            case TastyFormat.DOUBLEconst =>
                Tasty.Tree.Literal(Tasty.Constant.DoubleConst(java.lang.Double.longBitsToDouble(view.readLongNat())))
            case TastyFormat.STRINGconst =>
                val nameRef = view.readNat()
                Tasty.Tree.Literal(Tasty.Constant.StringConst(nameFromRef(nameRef, ctx).asString))

            case TastyFormat.SHAREDtype =>
                val addr = view.readNat()
                Tasty.Tree.Shared(addr)

            case TastyFormat.TYPEREFdirect =>
                val addr = view.readNat()
                Tasty.Tree.TypeRefDirect(addr)

            case TastyFormat.TYPEREFpkg =>
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                Tasty.Tree.TypeRefPkg(name)

            // Cat-2: tag + Nat.
            case TastyFormat.RECthis =>
                // RECthis: address of the enclosing Rec type frame.
                val addr = view.readNat()
                Tasty.Tree.RecThisAddr(addr)

            case TastyFormat.IMPORTED =>
                // IMPORTED: cat-2 (tag + NameRef) -- imported name in an import selector.
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                Tasty.Tree.Imported(Tasty.Tree.Ident(name, Tasty.Type.Named(makeUnresolvedSym(name.asString).id)))

            case TastyFormat.RENAMED =>
                // RENAMED: cat-2 (tag + NameRef) -- rename target in import selector.
                val nameRef = view.readNat()
                Tasty.Tree.Renamed(nameFromRef(nameRef, ctx))

            // ── Category 3: tag + sub-AST (90-109) ───────────────────────────────

            case TastyFormat.THIS =>
                val tpe = readType(view, ctx)
                val sym = tpe match
                    case Tasty.Type.Named(id)    => resolveSymbolById(id, ctx, "this-class")
                    case Tasty.Type.ThisType(id) => resolveSymbolById(id, ctx, "this-class")
                    case _                       => makeUnresolvedSym("this-class")
                Tasty.Tree.This(sym)

            case TastyFormat.QUALTHIS =>
                val tpe = readType(view, ctx)
                val sym = tpe match
                    case Tasty.Type.Named(id) => resolveSymbolById(id, ctx, "qualthis")
                    case _                    => makeUnresolvedSym("qualthis")
                Tasty.Tree.This(sym)

            case TastyFormat.CLASSconst =>
                val tpe = readType(view, ctx)
                Tasty.Tree.Literal(Tasty.Constant.ClassConst(tpe))

            case TastyFormat.NEW =>
                val tpe = readType(view, ctx)
                Tasty.Tree.New(tpe)

            case TastyFormat.THROW =>
                val expr = readTree(view, ctx)
                Tasty.Tree.Throw(expr)

            case TastyFormat.IMPLICITarg =>
                // Implicit argument: transparent wrapper; return inner tree.
                readTree(view, ctx)

            // Modifier-with-sub-AST: not a term tree; skip the sub-tree.
            case TastyFormat.PRIVATEqualified | TastyFormat.PROTECTEDqualified =>
                skipOneTree(view)
                Tasty.Tree.Unknown(tag, 0)

            // RECtype and BYNAMEtype: category-3 (tag+AST); decode into typed Tree cases.
            case TastyFormat.RECtype =>
                val parent = readTree(view, ctx)
                Tasty.Tree.RecType(parent)

            case TastyFormat.BYNAMEtype =>
                val arg = readTree(view, ctx)
                Tasty.Tree.ByNameType(arg)

            case TastyFormat.SINGLETONtpt =>
                val inner = readTree(view, ctx)
                Tasty.Tree.SingletonTpt(inner)

            case TastyFormat.BYNAMEtpt =>
                // BYNAMEtpt: cat-3 (tag + body_Type). Type-position by-name annotation.
                val inner = readType(view, ctx)
                Tasty.Tree.ByNameTpt(inner)

            case TastyFormat.BOUNDED =>
                // BOUNDED: cat-3 (tag + bound_Tree). Bounded wildcard type.
                val bound = readTree(view, ctx)
                Tasty.Tree.Bounded(bound)

            case TastyFormat.EXPLICITtpt =>
                // EXPLICITtpt: cat-3 (tag + tpe_Type). Explicit type position.
                val inner = readType(view, ctx)
                Tasty.Tree.ExplicitTpt(inner)

            case TastyFormat.ELIDED =>
                // ELIDED: cat-3 (tag + tpe_Type). Elided (inferred) type position.
                val inner = readType(view, ctx)
                Tasty.Tree.Elided(inner)

            // ── Category 4: tag + Nat + sub-AST (110-127) ────────────────────────

            case TastyFormat.IDENT =>
                val nameRef = view.readNat()
                val tpe     = readType(view, ctx)
                val name    = nameFromRef(nameRef, ctx)
                Tasty.Tree.Ident(name, tpe)

            case TastyFormat.SELECT =>
                // SELECT nameRef qualifier_Tree
                // tpe of the Select is not encoded here; use Wildcard as placeholder.
                val nameRef   = view.readNat()
                val qualifier = readTree(view, ctx)
                val name      = nameFromRef(nameRef, ctx)
                Tasty.Tree.Select(
                    qualifier,
                    name,
                    Tasty.Type.Wildcard(
                        Tasty.Type.Named(makeUnresolvedSym("lo").id),
                        Tasty.Type.Named(makeUnresolvedSym("hi").id)
                    )
                )

            case TastyFormat.TERMREFsymbol =>
                // TERMREFsymbol addr qualifier_Tree
                val addr = view.readNat()
                val qual = readTree(view, ctx)
                Tasty.Tree.TermRefSymbol(addr, qual)

            case TastyFormat.TERMREF =>
                // TERMREF nameRef prefix_Type
                val nameRef = view.readNat()
                val prefix  = readType(view, ctx)
                val name    = nameFromRef(nameRef, ctx)
                Tasty.Tree.Select(Tasty.Tree.Ident(name, prefix), name, prefix)

            case TastyFormat.NAMEDARG =>
                val nameRef = view.readNat()
                val value   = readTree(view, ctx)
                val name    = nameFromRef(nameRef, ctx)
                Tasty.Tree.NamedArg(name, value)

            case TastyFormat.IDENTtpt =>
                // IDENTtpt nameRef tpe_Tree
                val nameRef = view.readNat()
                val tpe     = readType(view, ctx)
                val name    = nameFromRef(nameRef, ctx)
                Tasty.Tree.IdentTpt(name, tpe)

            case TastyFormat.SELECTtpt =>
                // SELECTtpt nameRef qualifier_Tree
                val nameRef = view.readNat()
                val qual    = readTree(view, ctx)
                val name    = nameFromRef(nameRef, ctx)
                Tasty.Tree.SelectTpt(qual, name)

            case TastyFormat.TYPEREFsymbol =>
                // TYPEREFsymbol addr qualifier_Tree
                val addr = view.readNat()
                val qual = readTree(view, ctx)
                Tasty.Tree.TypeRefSymbol(addr, qual)

            case TastyFormat.TYPEREF =>
                // TYPEREF: cat-4 (tag + NameRef + qual_Tree). Type-position type reference.
                val nameRef = view.readNat()
                val qual    = readTree(view, ctx)
                val name    = nameFromRef(nameRef, ctx)
                Tasty.Tree.TypeRefTree(qual, name)

            case TastyFormat.SELFDEF =>
                // SELFDEF: cat-4 (tag + NameRef + type_Tree). Self type definition in TEMPLATE.
                val nameRef = view.readNat()
                val tpe     = readTree(view, ctx)
                val name    = nameFromRef(nameRef, ctx)
                Tasty.Tree.SelfDef(name, tpe)

            // ── Category 5: tag + Length + payload (128-255) ─────────────────────

            case TastyFormat.PACKAGE =>
                val end   = view.readEnd()
                val name  = extractPackageName(view, ctx)
                val sym   = makeUnresolvedSym(name.asString)
                val stats = readTreesUntil(view, end, ctx)
                view.goto(end)
                Tasty.Tree.PackageDef(sym, stats)

            case TastyFormat.VALDEF =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                // First type node after NameRef is the declared type; remaining content is rhs + modifiers.
                val tpt = readTypeOrSkip(view, end, ctx)
                // Collect rhs tree if present (skip modifier tags first).
                val rhs = readOptionalRhs(view, end, ctx)
                view.goto(end)
                val sym = ctx.addrMap.getOrElse(startAddr, makeUnresolvedSym(name.asString))
                Tasty.Tree.ValDef(sym, tpt, rhs)

            case TastyFormat.DEFDEF =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                val sym     = ctx.addrMap.getOrElse(startAddr, makeUnresolvedSym(name.asString))
                // Collect parameter clauses (TYPEPARAM and PARAM nodes), then result type, then optional rhs.
                val (paramss, tpt) = readDefDefParamsAndTpt(view, end, ctx)
                val rhs            = readOptionalRhs(view, end, ctx)
                view.goto(end)
                Tasty.Tree.DefDef(sym, paramss, tpt, rhs)

            case TastyFormat.TYPEDEF =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                val sym     = ctx.addrMap.getOrElse(startAddr, makeUnresolvedSym(name.asString))
                // Peek: if next tag is TEMPLATE (156) -> ClassDef; else TypeDef.
                // Consume TEMPLATE tag before calling readTemplate (which expects tag already consumed).
                skipModifierTags(view, end)
                if view.position < end && (view.peekByte(view.position) & 0xff) == TastyFormat.TEMPLATE then
                    discard(view.readByte()) // consume TEMPLATE tag
                    val tmpl = readTemplate(view, ctx)
                    view.goto(end)
                    Tasty.Tree.ClassDef(sym, tmpl)
                else
                    val rhs = readTypeOrSkip(view, end, ctx)
                    view.goto(end)
                    Tasty.Tree.TypeDef(sym, rhs)
                end if

            case TastyFormat.IMPORT =>
                val end  = view.readEnd()
                val qual = readTree(view, ctx)
                val sels = readImportSelectors(view, end, ctx)
                view.goto(end)
                Tasty.Tree.Import(qual, sels)

            case TastyFormat.EXPORT =>
                val end  = view.readEnd()
                val qual = readTree(view, ctx)
                val sels = readImportSelectors(view, end, ctx)
                view.goto(end)
                Tasty.Tree.Export(qual, sels)

            case TastyFormat.ANNOTATION =>
                val end       = view.readEnd()
                val annotType = readTree(view, ctx)
                val arg =
                    if view.position < end then readTree(view, ctx)
                    else Tasty.Tree.Unknown(0, 0)
                view.goto(end)
                Tasty.Tree.AnnotationNode(annotType, arg)

            case TastyFormat.TYPEPARAM =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                val sym     = ctx.addrMap.getOrElse(startAddr, makeUnresolvedSym(name.asString))
                val tpt     = readTypeOrSkip(view, end, ctx)
                view.goto(end)
                Tasty.Tree.TypeDef(sym, tpt)

            case TastyFormat.PARAM =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                val sym     = ctx.addrMap.getOrElse(startAddr, makeUnresolvedSym(name.asString))
                val tpt     = readTypeOrSkip(view, end, ctx)
                val rhs     = readOptionalRhs(view, end, ctx)
                view.goto(end)
                Tasty.Tree.ValDef(sym, tpt, rhs)

            case TastyFormat.APPLY =>
                val end  = view.readEnd()
                val fun  = readTree(view, ctx)
                val args = readTreesUntil(view, end, ctx)
                view.goto(end)
                Tasty.Tree.Apply(fun, args)

            case TastyFormat.TYPEAPPLY =>
                val end      = view.readEnd()
                val fun      = readTree(view, ctx)
                val typeArgs = readTypesUntil(view, end, ctx)
                view.goto(end)
                Tasty.Tree.TypeApply(fun, typeArgs)

            case TastyFormat.TYPED =>
                val end  = view.readEnd()
                val expr = readTree(view, ctx)
                val tpe  = readType(view, ctx)
                view.goto(end)
                Tasty.Tree.Typed(expr, tpe)

            case TastyFormat.ASSIGN =>
                val end = view.readEnd()
                val lhs = readTree(view, ctx)
                val rhs = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.Assign(lhs, rhs)

            case TastyFormat.BLOCK =>
                val end   = view.readEnd()
                val trees = readTreesUntil(view, end, ctx)
                view.goto(end)
                if trees.isEmpty then
                    throw new DecodeException(s"BLOCK at $startAddr has empty payload", startAddr.toLong)
                val stats = trees.dropRight(1)
                val expr  = trees.last
                Tasty.Tree.Block(stats, expr)

            case TastyFormat.IF =>
                val end   = view.readEnd()
                val cond  = readTree(view, ctx)
                val thenp = readTree(view, ctx)
                val elsep = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.If(cond, thenp, elsep)

            case TastyFormat.LAMBDA =>
                val end    = view.readEnd()
                val method = readTree(view, ctx)
                // tpe is optional; peek to see if the next thing is a type node.
                val tpe =
                    if view.position < end then
                        val peek = view.peekByte(view.position) & 0xff
                        if isTypeTag(peek) then Maybe(readType(view, ctx))
                        else Maybe.Absent
                    else Maybe.Absent
                view.goto(end)
                Tasty.Tree.Lambda(method, tpe)

            case TastyFormat.MATCH =>
                val end      = view.readEnd()
                val selector = readTree(view, ctx)
                val cases    = readCaseDefs(view, end, ctx)
                view.goto(end)
                Tasty.Tree.Match(selector, cases)

            case TastyFormat.RETURN =>
                val end  = view.readEnd()
                val addr = view.readNat()
                val from = ctx.addrMap.getOrElse(addr, makeUnresolvedSym(s"return-target@$addr"))
                val expr =
                    if view.position < end then
                        val peek = view.peekByte(view.position) & 0xff
                        if !isModifierTag(peek) then Maybe(readTree(view, ctx))
                        else Maybe.Absent
                    else Maybe.Absent
                view.goto(end)
                Tasty.Tree.Return(expr, from)

            case TastyFormat.WHILE =>
                val end  = view.readEnd()
                val cond = readTree(view, ctx)
                val body = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.While(cond, body)

            case TastyFormat.TRY =>
                val end   = view.readEnd()
                val expr  = readTree(view, ctx)
                val cases = readCaseDefs(view, end, ctx)
                // Optional finalizer: any remaining tree before end.
                val fin =
                    if view.position < end then Maybe(readTree(view, ctx))
                    else Maybe.Absent
                view.goto(end)
                Tasty.Tree.Try(expr, cases, fin)

            case TastyFormat.INLINED =>
                val end = view.readEnd()
                // call may be EMPTYTREE (a zero-byte marker) or a real tree.
                val call =
                    if view.position < end then
                        val peek = view.peekByte(view.position) & 0xff
                        // EMPTYTREE is not a formal tag in TastyFormat; dotty uses a very short payload.
                        // In practice the call is present as a full tree or absent.
                        Maybe(readTree(view, ctx))
                    else Maybe.Absent
                // Bindings are VALDEFs / DEFDEFs before the body (last tree).
                val remaining = readTreesUntil(view, end, ctx)
                view.goto(end)
                val (bindings, body) =
                    if remaining.isEmpty then (Chunk.empty[Tasty.Tree], Tasty.Tree.Unknown(TastyFormat.INLINED, 0))
                    else (remaining.dropRight(1), remaining.last)
                Tasty.Tree.Inlined(call, bindings, body)

            case TastyFormat.SELECTouter =>
                // SELECTouter: cat-5 (tag + Length + levels_Nat + name_NameRef + qual_Tree + tpe_Type).
                // Encodes an outer-class reference from an inner class.
                val end    = view.readEnd()
                val levels = view.readNat()
                val nm     = nameFromRef(view.readNat(), ctx)
                val qual   = readTree(view, ctx)
                val tpe    = readType(view, ctx)
                view.goto(end)
                Tasty.Tree.SelectOuter(qual, nm, levels, tpe)

            case TastyFormat.REPEATED =>
                // F-B-005: REPEATED encodes a varargs sequence; emit Tree.SeqLiteral.
                val end   = view.readEnd()
                val trees = readTreesUntil(view, end, ctx)
                view.goto(end)
                Tasty.Tree.SeqLiteral(
                    trees,
                    Tasty.Type.Wildcard(Tasty.Type.Named(makeUnresolvedSym("Nothing").id), Tasty.Type.Named(makeUnresolvedSym("Any").id))
                )

            case TastyFormat.BIND =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                discard(readType(view, ctx)) // type of bound variable
                val pattern = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.Bind(name, pattern)

            case TastyFormat.ALTERNATIVE =>
                val end      = view.readEnd()
                val patterns = readTreesUntil(view, end, ctx)
                view.goto(end)
                Tasty.Tree.Alternative(patterns)

            case TastyFormat.UNAPPLY =>
                val end = view.readEnd()
                val fun = readTree(view, ctx)
                // Implicits: trees before the final pattern args.
                // Read until we hit APPLY or TYPEAPPLY patterns boundary.
                // dotty: implicits are IMPLICITarg-tagged trees.
                val (implicits, patterns) = readUnapplyParts(view, end, ctx)
                view.goto(end)
                Tasty.Tree.Unapply(fun, implicits, patterns)

            case TastyFormat.ANNOTATEDtype =>
                val end    = view.readEnd()
                val parent = readTree(view, ctx)
                val annot  = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.AnnotatedType(parent, annot)

            case TastyFormat.ANNOTATEDtpt =>
                val end  = view.readEnd()
                val expr = readTree(view, ctx)
                val ann  = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.Annotated(expr, ann)

            case TastyFormat.CASEDEF =>
                val end     = view.readEnd()
                val pattern = readTree(view, ctx)
                // Guard is optional: if next node is not the body, it is the guard.
                val (guard, body) = readCaseDefGuardAndBody(view, end, ctx)
                view.goto(end)
                Tasty.Tree.CaseDef(pattern, guard, body)

            case TastyFormat.TEMPLATE =>
                // Should not normally appear as a standalone readTree target (it is consumed by TYPEDEF decode),
                // but handle defensively.
                val tmpl = readTemplate(view, ctx)
                Tasty.Tree.ClassDef(makeUnresolvedSym("template"), tmpl)

            case TastyFormat.SUPER =>
                val end  = view.readEnd()
                val qual = readTree(view, ctx)
                val mix =
                    if view.position < end then
                        Maybe(nameFromRef(view.readNat(), ctx))
                    else Maybe.Absent
                view.goto(end)
                Tasty.Tree.Super(qual, mix)

            // ── Category-5 type-form tags (SUPERtype, REFINEDtype, APPLIEDtype, etc.) ─
            // These tags are length-prefixed (tag + Length + payload); boundary is readEnd().
            // Mirror of TypeUnpickler boundary derivation: readEnd() then decode nodes until end.

            case TastyFormat.SUPERtype =>
                val end      = view.readEnd()
                val thistpe  = readTree(view, ctx)
                val supertpe = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.SuperType(thistpe, supertpe)

            case TastyFormat.REFINEDtype =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val parent  = readTree(view, ctx)
                val info    = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.RefinedType(parent, nameFromRef(nameRef, ctx), info)

            case TastyFormat.APPLIEDtype =>
                // Length-prefixed: readEnd() gives the payload boundary.
                // tycon is one tree node; remaining nodes until end are type args.
                val end   = view.readEnd()
                val tycon = readTree(view, ctx)
                val args  = readTreesUntil(view, end, ctx)
                view.goto(end)
                Tasty.Tree.AppliedType(tycon, args)

            case TastyFormat.TYPEBOUNDS =>
                val end = view.readEnd()
                val lo  = readTree(view, ctx)
                val hi  = if view.position < end then readTree(view, ctx) else Tasty.Tree.Unknown(0, 0)
                view.goto(end)
                Tasty.Tree.TypeBounds(lo, hi)

            case TastyFormat.ANDtype =>
                val end   = view.readEnd()
                val left  = readTree(view, ctx)
                val right = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.AndType(left, right)

            case TastyFormat.ORtype =>
                val end   = view.readEnd()
                val left  = readTree(view, ctx)
                val right = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.OrType(left, right)

            case TastyFormat.MATCHtype =>
                // Length-prefixed: readEnd() gives the payload boundary.
                // bound + scrutinee are the first two nodes; remaining nodes until end are cases.
                val end       = view.readEnd()
                val bound     = readTree(view, ctx)
                val scrutinee = readTree(view, ctx)
                val cases     = readTreesUntil(view, end, ctx)
                view.goto(end)
                Tasty.Tree.MatchType(bound, scrutinee, cases)

            case TastyFormat.FLEXIBLEtype =>
                val end = view.readEnd()
                val arg = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.FlexibleType(arg)

            case TastyFormat.SELECTin =>
                // SELECTin nameRef qualifier_Tree owner_Tree
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val qual    = readTree(view, ctx)
                val owner   = readTree(view, ctx)
                view.goto(end)
                val name = nameFromRef(nameRef, ctx)
                Tasty.Tree.SelectIn(qual, name, owner)

            // Cat-5 handlers for type-form tags that appear in term position.

            case TastyFormat.TERMREFin =>
                // F-B-004: TERMREFin (174) is a term-position path-dependent reference.
                // Wire format: tag + Length + NameRef + qual_Type + owner_Type.
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val nm      = nameFromRef(nameRef, ctx)
                val qual    = readType(view, ctx)
                view.goto(end)
                Tasty.Tree.TermRef(Tasty.Tree.Ident(nm, qual), nm)

            case TastyFormat.TYPEREFin =>
                // TYPEREFin: cat-5 (tag + Length + NameRef + qual_Type + owner_Type).
                // Type-position path-dependent reference; decode as SelectIn.
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val nm      = nameFromRef(nameRef, ctx)
                val qual    = readTree(view, ctx)
                val owner   = readTree(view, ctx)
                view.goto(end)
                Tasty.Tree.SelectIn(qual, nm, owner)

            case TastyFormat.POLYtype =>
                // POLYtype: cat-5 type-form lambda; decode type and discard.
                val end = view.readEnd()
                val tpe = readType(view, ctx)
                view.goto(end)
                Tasty.Tree.TypeDef(makeUnresolvedSym("poly"), tpe)

            case TastyFormat.TYPELAMBDAtype =>
                // TYPELAMBDAtype: cat-5 type-lambda form; decode type and discard.
                val end = view.readEnd()
                val tpe = readType(view, ctx)
                view.goto(end)
                Tasty.Tree.TypeDef(makeUnresolvedSym("typelambda"), tpe)

            case TastyFormat.METHODtype =>
                // METHODtype: cat-5 method-type form in term position.
                val end = view.readEnd()
                val tpe = readType(view, ctx)
                view.goto(end)
                Tasty.Tree.DefDef(makeUnresolvedSym("mtype"), Chunk.empty, tpe, Maybe.Absent)

            case TastyFormat.PARAMtype =>
                // PARAMtype: cat-5 (tag + Length + binder_addr + paramNum). Param reference type.
                val end = view.readEnd()
                view.goto(end)
                Tasty.Tree.Ident(Tasty.Name("param"), Tasty.Type.Named(makeUnresolvedSym("param").id))

            // Explicit Unknown arms for macro/quote tags (legitimate but rare).
            case TastyFormat.QUOTE =>
                val end = view.readEnd()
                view.goto(end)
                Tasty.Tree.Unknown(TastyFormat.QUOTE, Math.toIntExact(end - startAddr))

            case TastyFormat.SPLICE =>
                val end = view.readEnd()
                view.goto(end)
                Tasty.Tree.Unknown(TastyFormat.SPLICE, Math.toIntExact(end - startAddr))

            case TastyFormat.APPLYsigpoly =>
                val end = view.readEnd()
                view.goto(end)
                Tasty.Tree.Unknown(TastyFormat.APPLYsigpoly, Math.toIntExact(end - startAddr))

            case TastyFormat.QUOTEPATTERN =>
                val end = view.readEnd()
                view.goto(end)
                Tasty.Tree.Unknown(TastyFormat.QUOTEPATTERN, Math.toIntExact(end - startAddr))

            case TastyFormat.SPLICEPATTERN =>
                val end = view.readEnd()
                view.goto(end)
                Tasty.Tree.Unknown(TastyFormat.SPLICEPATTERN, Math.toIntExact(end - startAddr))

            case TastyFormat.HOLE =>
                val end = view.readEnd()
                view.goto(end)
                Tasty.Tree.Unknown(TastyFormat.HOLE, Math.toIntExact(end - startAddr))

            // HARD RULE 2: no silent fallback for remaining cat-5 tags.
            case other if other >= TastyFormat.firstLengthTreeTag =>
                val end = view.readEnd()
                view.goto(end)
                throw new IllegalStateException(
                    s"decodeTreeTag: unhandled cat-5 tag $other at addr $startAddr; add a handler or an explicit Unknown arm"
                )

            // Category 1 modifier tags (tag in [1, firstASTtag)): decode as Modifier.
            case other if other < TastyFormat.firstASTtag =>
                decodeCategoryOneModifier(other, view)

            // Unknown category 2-4 tags: skip body.
            case other =>
                skipTreeBody(other, view)
                Tasty.Tree.Unknown(other, 0)

        end match
    end decodeTreeTag

    /** Decode a TASTy category-1 modifier tag (single-byte, no payload) into a Tree.Modifier.
      *
      * Called when the tag byte is in [1, firstASTtag) and was not matched by an earlier specific arm (the category-1 constant tags
      * UNITconst/FALSEconst/TRUEconst/NULLconst are handled by dedicated arms before this dispatch reaches).
      *
      * Throws DecodeException for any category-1 byte that is not a recognised modifier flag.
      */
    private def decodeCategoryOneModifier(tag: Int, view: ByteView): Tasty.Tree =
        val flag: Tasty.Flag = tag match
            case TastyFormat.PRIVATE       => Tasty.Flag.Private
            case TastyFormat.PROTECTED     => Tasty.Flag.Protected
            case TastyFormat.ABSTRACT      => Tasty.Flag.Abstract
            case TastyFormat.FINAL         => Tasty.Flag.Final
            case TastyFormat.SEALED        => Tasty.Flag.Sealed
            case TastyFormat.CASE          => Tasty.Flag.Case
            case TastyFormat.IMPLICIT      => Tasty.Flag.Implicit
            case TastyFormat.GIVEN         => Tasty.Flag.Given
            case TastyFormat.LAZY          => Tasty.Flag.Lazy
            case TastyFormat.OVERRIDE      => Tasty.Flag.Override
            case TastyFormat.INLINE        => Tasty.Flag.Inline
            case TastyFormat.MACRO         => Tasty.Flag.Macro
            case TastyFormat.OPAQUE        => Tasty.Flag.Opaque
            case TastyFormat.OPEN          => Tasty.Flag.Open
            case TastyFormat.TRANSPARENT   => Tasty.Flag.Transparent
            case TastyFormat.INFIX         => Tasty.Flag.Infix
            case TastyFormat.ERASED        => Tasty.Flag.Erased
            case TastyFormat.TRACKED       => Tasty.Flag.Tracked
            case TastyFormat.SYNTHETIC     => Tasty.Flag.Synthetic
            case TastyFormat.ARTIFACT      => Tasty.Flag.Artifact
            case TastyFormat.STABLE        => Tasty.Flag.Stable
            case TastyFormat.STATIC        => Tasty.Flag.Static
            case TastyFormat.MUTABLE       => Tasty.Flag.Mutable
            case TastyFormat.FIELDaccessor => Tasty.Flag.FieldAccessor
            case TastyFormat.CASEaccessor  => Tasty.Flag.CaseAccessor
            case TastyFormat.PARAMsetter   => Tasty.Flag.PARAMsetter
            case TastyFormat.PARAMalias    => Tasty.Flag.PARAMalias
            case TastyFormat.EXPORTED      => Tasty.Flag.Exported
            case TastyFormat.LOCAL         => Tasty.Flag.Local
            case TastyFormat.HASDEFAULT    => Tasty.Flag.HasDefault
            case TastyFormat.EXTENSION     => Tasty.Flag.Extension
            case TastyFormat.INLINEPROXY   => Tasty.Flag.InlineProxy
            case TastyFormat.COVARIANT     => Tasty.Flag.CoVariant
            case TastyFormat.CONTRAVARIANT => Tasty.Flag.ContraVariant
            case TastyFormat.INVISIBLE     => Tasty.Flag.Invisible
            case TastyFormat.INTO          => Tasty.Flag.Into
            case TastyFormat.OBJECT        => Tasty.Flag.Module
            case TastyFormat.TRAIT         => Tasty.Flag.Trait
            case TastyFormat.ENUM          => Tasty.Flag.Enum
            case other =>
                throw new DecodeException(s"unknown category-1 modifier tag $other", view.position.toLong)
        Tasty.Tree.Modifier(flag)
    end decodeCategoryOneModifier

    // ── Template decode ───────────────────────────────────────────────────────

    /** Read a TEMPLATE node. Tag byte has already been consumed (or not; we consume it here if present). */
    private def readTemplate(view: ByteView, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Tree.Template =
        // Consume tag + length prefix.
        val end                          = view.readEnd()
        val parents                      = new mutable.ArrayBuffer[Tasty.Tree]()
        var selfSym: Maybe[Tasty.Symbol] = Maybe.Absent
        val bodyBuf                      = new mutable.ArrayBuffer[Tasty.Tree]()
        // Parents come first: types (not normal trees). They may be encoded as APPLY/SELECT/TYPEAPPLY trees.
        // Read trees until SELFDEF or a definition tag.
        var inParents = true
        while view.position < end do
            val peek = view.peekByte(view.position) & 0xff
            if inParents && isParentTag(peek) then
                parents += readTree(view, ctx)
            else if peek == TastyFormat.SELFDEF then
                inParents = false
                discard(view.readByte()) // consume SELFDEF tag
                val nameRef = view.readNat()
                skipOneTree(view) // skip type of self
                val name = nameFromRef(nameRef, ctx)
                selfSym = ctx.addrMap.values.find(_.name == name) match
                    case Some(s) => Maybe(s)
                    case None    => Maybe.Absent
            else if isModifierTag(peek) then
                inParents = false
                discard(view.readByte())
                if peek == TastyFormat.PRIVATEqualified || peek == TastyFormat.PROTECTEDqualified then
                    skipOneTree(view)
            else
                inParents = false
                bodyBuf += readTree(view, ctx)
            end if
        end while
        view.goto(end)
        Tasty.Tree.Template(Chunk.from(parents.toSeq), selfSym, Chunk.from(bodyBuf.toSeq))
    end readTemplate

    /** Read a TEMPLATE body from a slice whose `end` is already known (no length prefix to consume).
      *
      * Used by `decodeSymBody` for class-like symbols whose body slice starts at the TEMPLATE payload content (the TEMPLATE tag and length
      * prefix were already consumed by AstUnpickler during Pass 1).
      */
    private def readTemplateBody(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Tree.Template =
        val parents                      = new mutable.ArrayBuffer[Tasty.Tree]()
        var selfSym: Maybe[Tasty.Symbol] = Maybe.Absent
        val bodyBuf                      = new mutable.ArrayBuffer[Tasty.Tree]()
        var inParents                    = true
        while view.position < end do
            val peek = view.peekByte(view.position) & 0xff
            if inParents && isParentTag(peek) then
                parents += readTree(view, ctx)
            else if peek == TastyFormat.SELFDEF then
                inParents = false
                discard(view.readByte()) // consume SELFDEF tag
                val nameRef = view.readNat()
                skipOneTree(view) // skip type of self
                val name = nameFromRef(nameRef, ctx)
                selfSym = ctx.addrMap.values.find(_.name == name) match
                    case Some(s) => Maybe(s)
                    case None    => Maybe.Absent
            else if isModifierTag(peek) then
                inParents = false
                discard(view.readByte())
                if peek == TastyFormat.PRIVATEqualified || peek == TastyFormat.PROTECTEDqualified then
                    skipOneTree(view)
            else
                inParents = false
                bodyBuf += readTree(view, ctx)
            end if
        end while
        view.goto(end)
        Tasty.Tree.Template(Chunk.from(parents.toSeq), selfSym, Chunk.from(bodyBuf.toSeq))
    end readTemplateBody

    // ── CaseDef helpers ───────────────────────────────────────────────────────

    /** Read consecutive CASEDEF nodes until end. */
    private def readCaseDefs(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): Chunk[Tasty.Tree.CaseDef] =
        val buf = new mutable.ArrayBuffer[Tasty.Tree.CaseDef]()
        while view.position < end do
            val peek = view.peekByte(view.position) & 0xff
            if peek == TastyFormat.CASEDEF then
                val startAddr = view.positionInt
                discard(view.readByte()) // consume CASEDEF tag
                val payloadEnd    = view.readEnd()
                val pattern       = readTree(view, ctx)
                val (guard, body) = readCaseDefGuardAndBody(view, payloadEnd, ctx)
                view.goto(payloadEnd)
                val cd = Tasty.Tree.CaseDef(pattern, guard, body)
                ctx.treeAddrCache(startAddr) = cd
                buf += cd
            else
                // Not a CASEDEF; stop collecting cases.
                return Chunk.from(buf.toSeq)
            end if
        end while
        Chunk.from(buf.toSeq)
    end readCaseDefs

    /** Read optional guard + mandatory body from a CASEDEF payload (after pattern has been read).
      *
      * F-B-008: uses explicit GUARD-tag (135) peek instead of a heuristic. The GUARD tag is a
      * category-5 (length-prefixed) node. When present, consume its tag byte, read the length prefix,
      * decode the guard tree, then read the body. When absent, the next node is the body directly.
      */
    private def readCaseDefGuardAndBody(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): (Maybe[Tasty.Tree], Tasty.Tree) =
        if view.position >= end then
            (Maybe.Absent, Tasty.Tree.Unknown(0, 0))
        else
            val maybeGuard =
                if view.position < end && (view.peekByte(view.position) & 0xff) == TastyFormat.GUARD then
                    discard(view.readByte()) // consume GUARD tag
                    val gEnd = view.readEnd()
                    val g    = readTree(view, ctx)
                    view.goto(gEnd)
                    Maybe(g)
                else
                    Maybe.Absent
            val body = readTree(view, ctx)
            (maybeGuard, body)
        end if
    end readCaseDefGuardAndBody

    // ── Unapply helpers ───────────────────────────────────────────────────────

    /** Read implicits (IMPLICITarg-tagged) and patterns from UNAPPLY payload (after fun). */
    private def readUnapplyParts(
        view: ByteView,
        end: Long,
        ctx: DecodeCtx
    )(using AllowUnsafe): (Chunk[Tasty.Tree], Chunk[Tasty.Tree]) =
        val implicits = new mutable.ArrayBuffer[Tasty.Tree]()
        val patterns  = new mutable.ArrayBuffer[Tasty.Tree]()
        while view.position < end do
            val peek = view.peekByte(view.position) & 0xff
            if peek == TastyFormat.IMPLICITarg then
                discard(view.readByte())
                implicits += readTree(view, ctx)
            else
                patterns += readTree(view, ctx)
            end if
        end while
        (Chunk.from(implicits.toSeq), Chunk.from(patterns.toSeq))
    end readUnapplyParts

    // ── Import/Export selector helpers ───────────────────────────────────────

    /** Read import selectors from an IMPORT or EXPORT payload (after qual has been read).
      *
      * Selectors are encoded as IMPORTED nameRef (RENAMED nameRef)? sequences, where IMPORTED and RENAMED are category-2 tags. Each
      * selector is returned as a Tree.Ident built from the imported name. Wildcards and omit-selectors that dotty may emit as bare IMPORTED
      * with a wildcard name are handled the same way.
      */
    private def readImportSelectors(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): Chunk[Tasty.Tree] =
        val buf = new scala.collection.mutable.ArrayBuffer[Tasty.Tree]()
        while view.position < end do
            val peek = view.peekByte(view.position) & 0xff
            if peek == TastyFormat.IMPORTED then
                discard(view.readByte())
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                buf += Tasty.Tree.Ident(name, Tasty.Type.Named(makeUnresolvedSym(name.asString).id))
                // Check for optional RENAMED
                if view.position < end && (view.peekByte(view.position) & 0xff) == TastyFormat.RENAMED then
                    discard(view.readByte())
                    discard(view.readNat()) // skip renamed-to name
            else if isModifierTag(peek) then
                discard(view.readByte())
                if peek == TastyFormat.PRIVATEqualified || peek == TastyFormat.PROTECTEDqualified then
                    skipOneTree(view)
            else
                // Unexpected content; skip by reading a full tree to maintain cursor integrity.
                discard(readTree(view, ctx))
            end if
        end while
        Chunk.from(buf.toSeq)
    end readImportSelectors

    // ── DefDef helpers ────────────────────────────────────────────────────────

    /** Read TYPEPARAM and PARAM groups until the result type, then read result type. */
    private def readDefDefParamsAndTpt(
        view: ByteView,
        end: Long,
        ctx: DecodeCtx
    )(using AllowUnsafe): (Chunk[Chunk[Tasty.Tree]], Tasty.Type) =
        val paramss = new mutable.ArrayBuffer[Chunk[Tasty.Tree]]()
        var tpt     = Tasty.Type.Named(makeUnresolvedSym("unknown-tpt").id): Tasty.Type
        while view.position < end do
            val peek = view.peekByte(view.position) & 0xff
            peek match
                case TastyFormat.TYPEPARAM | TastyFormat.PARAM =>
                    // Read one clause of params.
                    val clause = readOneParamClause(view, end, ctx)
                    paramss += clause
                case TastyFormat.EMPTYCLAUSE =>
                    discard(view.readByte())
                    paramss += Chunk.empty
                case TastyFormat.SPLITCLAUSE =>
                    discard(view.readByte())
                // Result type: first type node that is not a param/modifier.
                case _ if isTypeTag(peek) =>
                    tpt = readType(view, ctx)
                    // After result type, only rhs remains; stop.
                    return (Chunk.from(paramss.toSeq), tpt)
                case _ if isModifierTag(peek) =>
                    discard(view.readByte())
                    if peek == TastyFormat.PRIVATEqualified || peek == TastyFormat.PROTECTEDqualified then
                        skipOneTree(view)
                case _ =>
                    // Could be rhs or something else; stop reading params.
                    return (Chunk.from(paramss.toSeq), tpt)
            end match
        end while
        (Chunk.from(paramss.toSeq), tpt)
    end readDefDefParamsAndTpt

    /** Read one clause of TYPEPARAM or PARAM nodes. */
    private def readOneParamClause(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): Chunk[Tasty.Tree] =
        val buf = new mutable.ArrayBuffer[Tasty.Tree]()
        while view.position < end do
            val peek = view.peekByte(view.position) & 0xff
            if peek == TastyFormat.TYPEPARAM || peek == TastyFormat.PARAM then
                buf += readTree(view, ctx)
            else
                return Chunk.from(buf.toSeq)
            end if
        end while
        Chunk.from(buf.toSeq)
    end readOneParamClause

    // ── Type decode (via TypeUnpickler.readTypeForTree) ───────────────────────

    /** Read one type node using the shared TreeTypeSession. */
    private def readType(view: ByteView, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Type =
        TypeUnpickler.readTypeForTree(view, ctx.typeSession)
    end readType

    /** Read type nodes until position reaches end. */
    private def readTypesUntil(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): Chunk[Tasty.Type] =
        val buf = new mutable.ArrayBuffer[Tasty.Type]()
        while view.position < end do
            val peek = view.peekByte(view.position) & 0xff
            if !isTypeTag(peek) then return Chunk.from(buf.toSeq)
            buf += readType(view, ctx)
        end while
        Chunk.from(buf.toSeq)
    end readTypesUntil

    /** Read the first type node in the payload if the current tag looks like a type tag; skip modifiers first. */
    private def readTypeOrSkip(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Type =
        skipModifierTags(view, end)
        if view.position < end then
            val peek = view.peekByte(view.position) & 0xff
            if isTypeTag(peek) then
                readType(view, ctx)
            else
                Tasty.Type.Named(makeUnresolvedSym("unknown-tpt").id)
            end if
        else
            Tasty.Type.Named(makeUnresolvedSym("unknown-tpt").id)
        end if
    end readTypeOrSkip

    /** Read an optional rhs tree: the first remaining content after modifiers, or Absent.
      *
      * Note: does not filter by isTreeTag because literal constants (INTconst etc.) are valid rhs trees but are also returned by isTypeTag
      * (they can appear as ConstantType in type position). Any non-modifier byte after the declared type is interpreted as the rhs tree.
      */
    private def readOptionalRhs(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): Maybe[Tasty.Tree] =
        skipModifierTags(view, end)
        if view.position < end then Maybe(readTree(view, ctx))
        else Maybe.Absent
    end readOptionalRhs

    // ── Package name extraction ───────────────────────────────────────────────

    private def extractPackageName(view: ByteView, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Name =
        val tag = view.readByte() & 0xff
        tag match
            case TastyFormat.TERMREFpkg =>
                nameFromRef(view.readNat(), ctx)
            case TastyFormat.SELECT | TastyFormat.SELECTtpt =>
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                skipOneTree(view) // skip qualifier
                name
            case other =>
                skipTreeBody(other, view)
                Tasty.Name("")
        end match
    end extractPackageName

    // ── Skip helpers ──────────────────────────────────────────────────────────

    /** Skip exactly one tree from the current position (tag not yet consumed). */
    private def skipOneTree(view: ByteView)(using AllowUnsafe): Unit =
        val tag = view.readByte() & 0xff
        skipTreeBody(tag, view)
    end skipOneTree

    /** Skip the body of a tree node whose tag byte has already been consumed. */
    private def skipTreeBody(tag: Int, view: ByteView)(using AllowUnsafe): Unit =
        if tag >= TastyFormat.firstLengthTreeTag then
            val end = view.readEnd()
            view.goto(end)
        else if tag >= 110 && tag <= 127 then
            discard(view.readNat())
            skipOneTree(view)
        else if tag >= 90 && tag <= 109 then
            skipOneTree(view)
        else if tag >= 60 && tag <= 89 then
            discard(view.readNat())
        // else category 1: nothing to skip

    /** Skip any modifier tags (category 1, 1-59) and qualified-modifier sub-trees at current position. */
    private def skipModifierTags(view: ByteView, end: Long)(using AllowUnsafe): Unit =
        while view.position < end do
            val peek = view.peekByte(view.position) & 0xff
            if isModifierTag(peek) then
                discard(view.readByte())
                if peek == TastyFormat.PRIVATEqualified || peek == TastyFormat.PROTECTEDqualified then
                    skipOneTree(view)
            else
                return
            end if
        end while
    end skipModifierTags

    // ── Tag classification helpers ────────────────────────────────────────────

    /** Returns true for tags 1-59 (modifier/flag tags, category 1). */
    private def isModifierTag(tag: Int): Boolean = tag >= 1 && tag <= 59

    /** Returns true for tags that introduce type nodes (both cat-2 type-only and cat-5 type-prefixed nodes). */
    private def isTypeTag(tag: Int): Boolean =
        tag match
            case TastyFormat.UNITconst | TastyFormat.FALSEconst | TastyFormat.TRUEconst | TastyFormat.NULLconst => true
            case TastyFormat.SHAREDtype | TastyFormat.TERMREFdirect | TastyFormat.TYPEREFdirect |
                TastyFormat.TERMREFpkg | TastyFormat.TYPEREFpkg | TastyFormat.RECthis | TastyFormat.BYTEconst |
                TastyFormat.SHORTconst | TastyFormat.CHARconst | TastyFormat.INTconst | TastyFormat.LONGconst |
                TastyFormat.FLOATconst | TastyFormat.DOUBLEconst | TastyFormat.STRINGconst => true
            case TastyFormat.THIS | TastyFormat.QUALTHIS | TastyFormat.CLASSconst | TastyFormat.BYNAMEtype |
                TastyFormat.BYNAMEtpt | TastyFormat.RECtype | TastyFormat.SINGLETONtpt | TastyFormat.BOUNDED |
                TastyFormat.EXPLICITtpt => true
            case TastyFormat.IDENTtpt | TastyFormat.SELECTtpt | TastyFormat.TYPEREFsymbol | TastyFormat.TYPEREF |
                TastyFormat.TERMREFsymbol | TastyFormat.TERMREF => true
            case t if t >= TastyFormat.firstLengthTreeTag =>
                t match
                    case TastyFormat.APPLIEDtype | TastyFormat.APPLIEDtpt | TastyFormat.ANNOTATEDtype |
                        TastyFormat.ANNOTATEDtpt | TastyFormat.REFINEDtype | TastyFormat.REFINEDtpt |
                        TastyFormat.ANDtype | TastyFormat.ORtype | TastyFormat.POLYtype |
                        TastyFormat.TYPELAMBDAtype | TastyFormat.LAMBDAtpt | TastyFormat.PARAMtype |
                        TastyFormat.TERMREFin | TastyFormat.TYPEREFin | TastyFormat.TYPEBOUNDS |
                        TastyFormat.TYPEBOUNDStpt | TastyFormat.SUPERtype | TastyFormat.METHODtype |
                        TastyFormat.MATCHtype | TastyFormat.MATCHtpt | TastyFormat.MATCHCASEtype |
                        TastyFormat.FLEXIBLEtype | TastyFormat.REPEATED => true
                    case _ => false
            case _ => false
        end match
    end isTypeTag

    /** Returns true for tags that introduce term tree nodes (not types or modifiers). */
    private def isTreeTag(tag: Int): Boolean = !isModifierTag(tag) && !isTypeTag(tag)

    /** Tags that can appear in TEMPLATE parent position (Apply, TypeApply, Select, Ident, This). */
    private def isParentTag(tag: Int): Boolean =
        tag match
            case TastyFormat.APPLY | TastyFormat.TYPEAPPLY | TastyFormat.SELECT | TastyFormat.IDENT |
                TastyFormat.THIS | TastyFormat.QUALTHIS | TastyFormat.TERMREFsymbol | TastyFormat.TERMREFdirect |
                TastyFormat.TYPED | TastyFormat.NEW => true
            case _ => false
        end match
    end isParentTag

    // ── Name helper ───────────────────────────────────────────────────────────

    private def nameFromRef(nameRef: Int, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Name =
        if nameRef >= 0 && nameRef < ctx.names.length then ctx.names(nameRef)
        else Tasty.Name(s"name@$nameRef")
    end nameFromRef

    private def makeUnresolvedSym(name: String)(using AllowUnsafe): Tasty.Symbol =
        InternalSymbol.makeSymbol(
            Tasty.SymbolKind.Unresolved,
            Tasty.Flags.empty,
            Tasty.Name(name)
        )
    end makeUnresolvedSym

    /** Resolve a SymbolId back to a Symbol by scanning the addrMap.
      *
      * plan: phase-05 bridge; Tree.This still carries Symbol (migrated in Phase 09). Scans the addrMap values for a symbol whose id
      * matches. O(n) but used only in THIS/QUALTHIS decode.
      */
    private def resolveSymbolById(
        id: kyo.internal.tasty.symbol.SymbolId,
        ctx: DecodeCtx,
        fallbackName: String
    )(using AllowUnsafe): Tasty.Symbol =
        val idx = id.value
        ctx.addrMap.values.find(_.id.value == idx) match
            case Some(sym) => sym
            case None      => makeUnresolvedSym(fallbackName)
    end resolveSymbolById

    /** Decode a TPT-tag (type-position tree) node and return the underlying Type it wraps.
      *
      * TPT tags are wire-level tree nodes that exist at type-syntactic positions in DEFDEF, VALDEF, and type parameters. The caller (e.g.
      * AstUnpickler.decodeOneTypeIfPresent or TypeUnpickler.readTypeNode) is interested in the wrapped Type, not the Tree wrapper.
      *
      * F-I-004 fix: this is the entry point AstUnpickler.decodeOneTypeIfPresent dispatches to for tags classified by isTreeTptTag. It is
      * also called from TypeUnpickler.readTypeNode for nested TPT tags (e.g. APPLIEDtpt args that are themselves APPLIEDtpt).
      *
      * @param view
      *   ByteView positioned at the tag byte (tag not yet consumed).
      * @param session
      *   Shared decode session from AstUnpickler (carries names, addrMap, arena, and unresolvedIdToFqn).
      * @param tag
      *   The TPT tag byte value (already peeked by the caller; consumed here as first action).
      */
    private[reader] def decodeTptAsType(
        view: ByteView,
        session: TypeUnpickler.DecodeSession,
        tag: Int,
        sectionOffset: Int = 0
    )(using Frame)(using AllowUnsafe): Tasty.Type =
        discard(view.readByte()) // consume the tag byte
        tag match

            case TastyFormat.IDENTtpt =>
                // IDENTtpt (111): cat-4 (tag + Nat + AST). Nat is a name-ref (discard); AST is the resolved type.
                discard(view.readNat())
                TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)

            case TastyFormat.SELECTtpt =>
                // SELECTtpt (113): cat-4 (tag + NameRef + qual_Tree). Type-position member selection.
                // Encodes e.g. `scala.deprecated` as SELECT("deprecated", TERMREFpkg("scala")).
                // Encodes e.g. `scala.annotation.tailrec` as SELECT("tailrec", SELECT("annotation", TERMREFpkg("scala")))
                //   or as SELECT("tailrec", TERMREFsymbol(addr_for_scala.annotation_package))
                //   where the latter produces TermRef(outer_qual, "annotation").
                // Build a qualified FQN: decode qual to get its FQN, then combine with the selected name.
                val nameRef = view.readNat()
                val nm      = session.names(nameRef).asString
                val qual    = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                val qualFqn: String = qual match
                    case Tasty.Type.Named(sid) if sid.value < -1 =>
                        // Cross-file FQN reference (TERMREFpkg / TYPEREFpkg): look up the FQN from session.
                        session.unresolvedIdToFqn.getOrElse(sid.value, "")
                    case Tasty.Type.TermRef(innerQual, innerName) =>
                        // TERMREFsymbol for a package produces TermRef(outerQual, packageName).
                        // Reconstruct the FQN by walking the TermRef chain.
                        def termRefFqn(t: Tasty.Type): String = t match
                            case Tasty.Type.Named(sid) if sid.value < -1 =>
                                session.unresolvedIdToFqn.getOrElse(sid.value, "")
                            case Tasty.Type.TermRef(q2, n2) =>
                                import Tasty.Name.asString as nameAsString
                                val base = termRefFqn(q2)
                                if base.nonEmpty then base + "." + nameAsString(n2) else nameAsString(n2)
                            case _ => ""
                        import Tasty.Name.asString as nameAsString
                        val base = termRefFqn(innerQual)
                        if base.nonEmpty then base + "." + nameAsString(innerName) else nameAsString(innerName)
                    case _ =>
                        ""
                val fullFqn = if qualFqn.nonEmpty then qualFqn + "." + nm else nm
                val id      = session.nextUnresolvedId()
                session.unresolvedIdToFqn(id) = fullFqn
                Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(id))

            case TastyFormat.APPLIEDtpt =>
                // APPLIEDtpt (162): cat-5 (tag + Length + tycon_Tree + arg_Tree*).
                // F-A-004 fix: use TypeOps.applied to normalize FunctionN/ContextFunctionN/TupleN/Array,
                // same as the APPLIEDtype handler in TypeUnpickler.decodeTag.
                val payloadEnd = view.readEnd()
                val tycon      = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                val args       = new scala.collection.mutable.ArrayBuffer[Tasty.Type]()
                while view.position < payloadEnd do
                    args += TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                end while
                view.goto(payloadEnd)
                val fqnHint: String | Null = tycon match
                    case Tasty.Type.Named(id) if id.value < -1 =>
                        session.unresolvedIdToFqn.getOrElse(id.value, null)
                    case _ => null
                kyo.internal.tasty.type_.TypeOps.applied(tycon, Chunk.from(args.toSeq), fqnHint)

            case TastyFormat.TYPEBOUNDStpt =>
                // TYPEBOUNDStpt (164): cat-5 (tag + Length + lo_Tree + hi_Tree).
                // F-A-010: explicit declared bounds use Type.Bounds, not Type.Wildcard.
                val payloadEnd = view.readEnd()
                val lo         = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                val hi =
                    if view.position < payloadEnd then TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                    else lo
                view.goto(payloadEnd)
                Tasty.Type.Bounds(lo, hi)

            case TastyFormat.ANNOTATEDtpt =>
                // ANNOTATEDtpt (154): cat-5 (tag + Length + tpe_Tree + annot_Tree).
                // F-A2-013: if the annotation is @scala.annotation.internal.Repeated, the
                // parameter is a varargs parameter and the type should be wrapped in Type.Repeated.
                // The annotation term decodes via APPLY(NEW(Repeated_class), ...) which TypeUnpickler
                // unwraps to the Repeated class's type as Named(negId) with FQN in unresolvedIdToFqn.
                val payloadEnd = view.readEnd()
                val underlying = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                val isRepeated =
                    if view.position < payloadEnd then
                        try
                            val annotType = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                            // The annotation FQN may be a SIGNED constructor name like
                            // "<init>:scala.annotation.internal.Repeated()" or the plain class FQN.
                            // Both forms contain the class name as a substring.
                            annotType match
                                case Tasty.Type.Named(sid) if sid.value < -1 =>
                                    session.unresolvedIdToFqn.getOrElse(sid.value, "").contains(
                                        kyo.internal.tasty.type_.TypeOps.RepeatedAnnotationFqn
                                    )
                                case _ => false
                            end match
                        catch case _: Exception => false
                    else false
                view.goto(payloadEnd)
                if isRepeated then Tasty.Type.Repeated(underlying)
                else underlying

            case TastyFormat.SELECTin =>
                // SELECTin (176): cat-5 (tag + Length + nameRef + qual_Tree + owner_Tree).
                val payloadEnd = view.readEnd()
                val nameRef    = view.readNat()
                val nm         = session.names(nameRef).asString
                // Decode qual type to extract its FQN for cross-file resolution.
                val qualType = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                // Decode owner type (namespace); used for Phase C FQN resolution.
                val ownerType = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                view.goto(payloadEnd)
                // Build a qualified FQN: if qual resolves to a tracked cross-file ref, combine it
                // with the selected name to form the full FQN for Phase C lookup.
                val qualFqn: String = qualType match
                    case Tasty.Type.Named(sid) if sid.value < -1 =>
                        session.unresolvedIdToFqn.getOrElse(sid.value, "")
                    case _ =>
                        ""
                val fullFqn = if qualFqn.nonEmpty then qualFqn + "." + nm else nm
                val id      = session.nextUnresolvedId()
                session.unresolvedIdToFqn(id) = fullFqn
                Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(id))

            case TastyFormat.REFINEDtpt =>
                // REFINEDtpt (160): cat-5 (tag + Length + parent_Tree + decl_Tree*).
                // Phase 03 extracts parent type only; refinement decls are deferred to Phase 05.
                val payloadEnd = view.readEnd()
                val parent     = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                view.goto(payloadEnd)
                parent

            case TastyFormat.LAMBDAtpt =>
                // LAMBDAtpt (171): cat-5 (tag + Length + tparam_Tree* + body_Tree).
                // TYPEPARAM nodes carry cat-5 length-prefixed payloads; detect them by tag.
                val payloadEnd = view.readEnd()
                val tparamIds  = new scala.collection.mutable.ArrayBuffer[Tasty.SymbolId]()
                while view.position < payloadEnd && (view.peekByte(view.position) & 0xff) == TastyFormat.TYPEPARAM do
                    // Each TYPEPARAM: tag + Length + nameRef + bounds_Type.
                    discard(view.readByte()) // consume TYPEPARAM tag
                    val tpEnd   = view.readEnd()
                    val nameRef = view.readNat()
                    val symName = session.names(nameRef)
                    val sym = InternalSymbol.makeSymbol(
                        Tasty.SymbolKind.TypeParam,
                        Tasty.Flags.empty,
                        symName
                    )
                    tparamIds += sym.id
                    view.goto(tpEnd)
                end while
                val body = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                view.goto(payloadEnd)
                Tasty.Type.TypeLambda(Chunk.from(tparamIds.toSeq), body)

            case TastyFormat.MATCHtpt =>
                // MATCHtpt (191): cat-5 (tag + Length + scrutinee_Tree + bound_Tree + case_Tree*).
                val payloadEnd = view.readEnd()
                val scrutinee  = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                val bound      = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                val cases      = new scala.collection.mutable.ArrayBuffer[Tasty.Type]()
                while view.position < payloadEnd do
                    cases += TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                end while
                Tasty.Type.MatchType(bound, scrutinee, Chunk.from(cases.toSeq))

            case TastyFormat.MATCHCASEtype =>
                // MATCHCASEtype (192): cat-5 (tag + Length + pat_Tree + rhs_Tree).
                // F-A-006: Type.MatchCase is a first-class ADT case added in Phase 05.
                val payloadEnd = view.readEnd()
                val pat        = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                val rhs        = TypeUnpickler.readTypeIntoSession(view, session, sectionOffset)
                view.goto(payloadEnd)
                Tasty.Type.MatchCase(pat, rhs)

            case other =>
                // decodeTptAsType must only be called for tags in isTreeTptTag; any other tag is a bug.
                throw new IllegalStateException(
                    s"decodeTptAsType: not a TPT tag: $other at position ${view.positionInt}"
                )
        end match
    end decodeTptAsType

    /** Decode a TERM tag node and return the underlying Type it conveys at TYPE position.
      *
      * Companion of decodeTptAsType for the term-in-type-position tag set (F-A2-001). The 8 specific tags surface in ANNOTATEDtype payloads
      * (NEW, APPLY, SELECT), TEMPLATE parent expressions (TYPEAPPLY, SELECT, NEW), CASEDEF inside MATCHCASEtype rhs, and INLINED-as-type
      * body slots. Each handler consumes its byte payload precisely so the outer view advances correctly.
      *
      * The tag byte at view.position is consumed as the first action (same pattern as decodeTptAsType). Callers must only call this for
      * tags in AstUnpickler.isTermTagInTypePosition; any tag reaching case other throws, since the caller committed to the guard.
      *
      * Wire formats:
      *   SHAREDterm (60): cat-2 (tag + Nat). Nat is an AST section-relative offset (ASTRef).
      *   BYNAMEtpt  (94): cat-3 (tag + AST). AST is the element type.
      *   NEW        (95): cat-3 (tag + AST). AST is the constructed type (tpt).
      *   SINGLETONtpt (101): cat-3 (tag + AST). AST is the singleton's value term.
      *   SELECT    (112): cat-4 (tag + Nat + AST). Nat is nameRef; AST is qualifier.
      *   APPLY     (136): cat-5 (tag + Length + fn_AST + args_AST*). fn is the called method.
      *   TYPEAPPLY (137): cat-5 (tag + Length + fn_AST + targs_AST*). fn applied to type args.
      *   CASEDEF   (155): cat-5 (tag + Length + pat_AST + body_AST + guard_AST?). Skip all.
      */
    private[reader] def decodeTermTagInTypePosition(
        view: ByteView,
        typeSession: TypeUnpickler.DecodeSession,
        tag: Int,
        sectionOffset: Int = 0
    )(using Frame)(using AllowUnsafe): Tasty.Type =
        discard(view.readByte()) // consume tag byte
        tag match
            case TastyFormat.SHAREDterm =>
                // SHAREDterm (60): cat-2 (tag + Nat). The Nat is a section-relative ASTRef.
                // Look up the cached type from addrCache. On cache miss assign a unique tracked negative
                // ID so the result is Named(SymbolId(uniqueNeg)) with uniqueNeg < -1, not the Named(-1) sentinel.
                // sectionOffset converts section-relative ref to the absolute addrCache key.
                val ref    = view.readNat()
                val absRef = sectionOffset + ref
                typeSession.addrCache.getOrElse(
                    absRef,
                    Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(typeSession.nextUnresolvedId()))
                )

            case TastyFormat.BYNAMEtpt =>
                // BYNAMEtpt (94): cat-3 (tag + AST). The AST is the element type.
                // Distinct from BYNAMEtype (93) which is the type-position by-name encoding already handled
                // by TypeUnpickler. This TPT-position variant wraps the inner type in Type.ByName.
                val under = TypeUnpickler.readTypeIntoSession(view, typeSession, sectionOffset)
                Tasty.Type.ByName(under)

            case TastyFormat.NEW =>
                // NEW (95): cat-3 (tag + AST). The AST is the tpt (class type being constructed).
                // The tpt IS the result type of the new expression.
                TypeUnpickler.readTypeIntoSession(view, typeSession, sectionOffset)

            case TastyFormat.SINGLETONtpt =>
                // SINGLETONtpt (101): cat-3 (tag + AST). The AST is the singleton's value term.
                // Decode the term's type to get the singleton's underlying type.
                TypeUnpickler.readTypeIntoSession(view, typeSession, sectionOffset)

            case TastyFormat.SELECT =>
                // SELECT (112): cat-4 (tag + Nat + AST). Nat is nameRef; AST is the qualifier.
                // Build a qualified FQN from the qualifier's resolved type and the selected name,
                // mirroring SELECTtpt in decodeTptAsType (same pattern).
                val nameRef = view.readNat()
                val nm      = typeSession.names(nameRef).asString
                val qual    = TypeUnpickler.readTypeIntoSession(view, typeSession, sectionOffset)
                val qualFqn: String = qual match
                    case Tasty.Type.Named(sid) if sid.value < -1 =>
                        typeSession.unresolvedIdToFqn.getOrElse(sid.value, "")
                    case _ => ""
                val fullFqn = if qualFqn.nonEmpty then qualFqn + "." + nm else nm
                val id      = typeSession.nextUnresolvedId()
                typeSession.unresolvedIdToFqn(id) = fullFqn
                Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(id))

            case TastyFormat.APPLY =>
                // APPLY (136): cat-5 (tag + Length + fn_AST + args_AST*).
                // The result type of the application is the function's return type. Read fn type;
                // skip the remaining term args (they are values, not types).
                val end    = view.readEnd()
                val fnType = TypeUnpickler.readTypeIntoSession(view, typeSession, sectionOffset)
                view.goto(end)
                fnType

            case TastyFormat.TYPEAPPLY =>
                // TYPEAPPLY (137): cat-5 (tag + Length + fn_AST + targs_AST*).
                // fn is the method reference; targs are TYPE arguments.
                // Return Applied(fnType, targs) since targs are types and match Type.Applied semantics.
                val end    = view.readEnd()
                val fnType = TypeUnpickler.readTypeIntoSession(view, typeSession, sectionOffset)
                val targs  = new scala.collection.mutable.ArrayBuffer[Tasty.Type]()
                while view.position < end do
                    targs += TypeUnpickler.readTypeIntoSession(view, typeSession, sectionOffset)
                view.goto(end)
                if targs.isEmpty then fnType
                else Tasty.Type.Applied(fnType, Chunk.from(targs.toSeq))

            case TastyFormat.CASEDEF =>
                // CASEDEF (155): cat-5 (tag + Length + pat_AST + body_AST + guard_AST?).
                // In type position (rare, inside MATCHCASEtype rhs) the body is the result type.
                // Skip everything and return a Wildcard placeholder; the body's type is inaccessible
                // without a full term decoder. Use unique tracked negative IDs for the bounds to avoid
                // emitting the Named(-1) sentinel that would fail the INV-005 named-sentinel check.
                val end = view.readEnd()
                view.goto(end)
                val loId = typeSession.nextUnresolvedId()
                val hiId = typeSession.nextUnresolvedId()
                Tasty.Type.Wildcard(
                    Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(loId)),
                    Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(hiId))
                )

            case TastyFormat.QUALTHIS =>
                // QUALTHIS (91): cat-3 (tag + AST). The AST is the qualifier type reference.
                // Decode the qualifier type and return it as the this-type.
                TypeUnpickler.readTypeIntoSession(view, typeSession, sectionOffset)

            case TastyFormat.INLINED =>
                // INLINED (147): cat-5 (tag + Length + expr + call? + bindings*).
                // An inlined expression appearing in type position. Skip the payload and return a
                // unique tracked unresolved ID to avoid the Named(-1) sentinel.
                val end = view.readEnd()
                view.goto(end)
                Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(typeSession.nextUnresolvedId()))

            case other =>
                // Any tag reaching here is a routing invariant violation: the caller committed to
                // isTermTagInTypePosition returning true. Throw loudly so the mismatch is detectable.
                throw new IllegalStateException(
                    s"decodeTermTagInTypePosition: unhandled tag $other at position ${view.positionInt}; " +
                        s"check AstUnpickler.isTermTagInTypePosition routing."
                )
        end match
    end decodeTermTagInTypePosition

end TreeUnpickler
