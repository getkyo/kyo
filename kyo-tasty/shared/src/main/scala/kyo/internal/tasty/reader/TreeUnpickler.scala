package kyo.internal.tasty.reader

import kyo.*
import kyo.Tasty.Name.asString
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.ClasspathRef
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
      * Called by `Tasty.Annotation.args` on demand. The pickle is a self-contained slice of a TASTy AST section corresponding to one
      * annotation term (typically a `New` or `Apply` expression).
      *
      * @param pickle
      *   raw annotation term bytes (as captured by TypeUnpickler.ANNOTATEDtype into `Annotation.argsPickle`).
      * @param ctx
      *   the file-scoped decode context: names array, addrMap, home, full section bytes.
      */
    private[kyo] def decodeAnnotationTerm(
        pickle: kyo.Chunk[Byte],
        ctx: Tasty.Annotation.DecodeContext
    ): Tasty.Tree =
        val bytes      = pickle.toArray
        val view       = ByteView(bytes, 0, bytes.length)
        val dummyArena = TypeArena.canonical()
        val typeSession =
            new TypeUnpickler.TreeTypeSession(ctx.names, ctx.addrMap, dummyArena, ctx.home, ctx.sectionBytes, ctx.sectionOffset)
        val treeAddrCache = new mutable.HashMap[Int, Tasty.Tree]()
        val decodeCtx     = DecodeCtx(ctx.names, ctx.addrMap, ctx.home, typeSession, treeAddrCache)
        readTree(view, decodeCtx)
    end decodeAnnotationTerm

    /** Synchronously decode the body byte slice for `sym` (described by `origin`) into a Tree.
      *
      * Called from the OnceCell init lambda; must not use Kyo effects. Throws DecodeException or ArrayIndexOutOfBoundsException on error.
      *
      * @param origin
      *   the TastyOrigin carrying sectionBytes, bodyStart, bodyEnd, names, and addrMap.
      * @param sym
      *   the symbol whose body is being decoded; used to determine body-slice layout (VALDEF / DEFDEF / class / package).
      */
    def decodeSync(origin: Tasty.Symbol.TastyOrigin, sym: Tasty.Symbol): Tasty.Tree =
        import AllowUnsafe.embrace.danger
        val addrMap = origin.addrMap
        val names   = origin.names
        // Prefer the pre-constructed ByteView (mmap-backed) if present; otherwise build from sectionBytes.
        // Reading from a closed mmap arena throws IllegalStateException, which Symbol.body maps to ClasspathClosed.
        val (view, bytes) = origin.bodyView match
            case bv: kyo.internal.tasty.binary.ByteView =>
                val sub = bv.subView(origin.bodyStart, origin.bodyEnd)
                (sub, origin.sectionBytes)
            case null =>
                val b = origin.sectionBytes
                if b == null || b.isEmpty then
                    // No cursor: the symbol has no body bytes to position into.
                    throw new DecodeException("body bytes not available (snapshot-loaded symbol)", 0L)
                end if
                (ByteView(b, origin.bodyStart, origin.bodyEnd), b)
        val treeAddrCache = new mutable.HashMap[Int, Tasty.Tree]()
        val dummyHome     = new ClasspathRef
        val dummyArena    = TypeArena.canonical()
        val typeSession   = new TypeUnpickler.TreeTypeSession(names, addrMap, dummyArena, dummyHome, bytes, origin.sectionOffset)
        val ctx = DecodeCtx(
            names,
            addrMap,
            dummyHome,
            typeSession,
            treeAddrCache
        )
        decodeSymBody(sym, view, origin.bodyEnd, ctx)
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
    ): Tasty.Tree =
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
        val home: ClasspathRef,
        val typeSession: TypeUnpickler.TreeTypeSession,
        val treeAddrCache: mutable.HashMap[Int, Tasty.Tree]
    )

    // ── Tree dispatch ─────────────────────────────────────────────────────────

    /** Read one tree node; tag byte not yet consumed. Records startAddr in treeAddrCache. */
    private def readTree(view: ByteView, ctx: DecodeCtx): Tasty.Tree =
        val startAddr = view.positionInt
        val tag       = view.readByte() & 0xff
        val result    = decodeTreeTag(tag, startAddr, view, ctx)
        if tag != TastyFormat.SHAREDterm && tag != TastyFormat.SHAREDtype then
            ctx.treeAddrCache(startAddr) = result
        result
    end readTree

    /** Read all trees until view.position >= end. */
    private def readTreesUntil(view: ByteView, end: Long, ctx: DecodeCtx): Chunk[Tasty.Tree] =
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
    private def decodeTreeTag(tag: Int, startAddr: Int, view: ByteView, ctx: DecodeCtx): Tasty.Tree =
        // Unsafe: Name.asString requires AllowUnsafe; embraced here in the tree-decode context (§839 case 3).
        import AllowUnsafe.embrace.danger
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
                val sym  = ctx.addrMap.getOrElse(addr, makeUnresolvedSym(s"termref@$addr", ctx.home))
                Tasty.Tree.Ident(sym.name, Tasty.Type.Named(sym))

            case TastyFormat.TERMREFpkg =>
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                Tasty.Tree.Ident(name, Tasty.Type.Named(makeUnresolvedSym(name.asString, ctx.home)))

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

            // These category-2 tags are not term trees; skip Nat and return Unknown.
            case TastyFormat.TYPEREFdirect | TastyFormat.TYPEREFpkg | TastyFormat.RECthis |
                TastyFormat.IMPORTED | TastyFormat.RENAMED =>
                discard(view.readNat())
                Tasty.Tree.Unknown(tag, 0)

            // ── Category 3: tag + sub-AST (90-109) ───────────────────────────────

            case TastyFormat.THIS =>
                val tpe = readType(view, ctx)
                val sym = tpe match
                    case Tasty.Type.Named(s)    => s
                    case Tasty.Type.ThisType(s) => s
                    case _                      => makeUnresolvedSym("this-class", ctx.home)
                Tasty.Tree.This(sym)

            case TastyFormat.QUALTHIS =>
                val tpe = readType(view, ctx)
                val sym = tpe match
                    case Tasty.Type.Named(s) => s
                    case _                   => makeUnresolvedSym("qualthis", ctx.home)
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

            // Type nodes in category 3; skip them.
            case TastyFormat.BYNAMEtype | TastyFormat.BYNAMEtpt | TastyFormat.RECtype | TastyFormat.SINGLETONtpt |
                TastyFormat.BOUNDED | TastyFormat.EXPLICITtpt =>
                skipOneTree(view)
                Tasty.Tree.Unknown(tag, 0)

            case TastyFormat.ELIDED =>
                skipOneTree(view)
                Tasty.Tree.Unknown(tag, 0)

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
                        Tasty.Type.Named(makeUnresolvedSym("lo", ctx.home)),
                        Tasty.Type.Named(makeUnresolvedSym("hi", ctx.home))
                    )
                )

            case TastyFormat.TERMREFsymbol =>
                // TERMREFsymbol addr qualifier_Type
                val addr = view.readNat()
                discard(readType(view, ctx)) // consume qualifier type
                val sym = ctx.addrMap.getOrElse(addr, makeUnresolvedSym(s"termrefsym@$addr", ctx.home))
                Tasty.Tree.Ident(sym.name, Tasty.Type.Named(sym))

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

            // Type-position category 4 nodes; skip and return Unknown.
            case TastyFormat.IDENTtpt | TastyFormat.SELECTtpt | TastyFormat.TYPEREFsymbol | TastyFormat.TYPEREF =>
                discard(view.readNat())
                skipOneTree(view)
                Tasty.Tree.Unknown(tag, 0)

            case TastyFormat.SELFDEF =>
                // SELFDEF nameRef type; appears in TEMPLATE; skip in tree position.
                discard(view.readNat())
                skipOneTree(view)
                Tasty.Tree.Unknown(tag, 0)

            // ── Category 5: tag + Length + payload (128-255) ─────────────────────

            case TastyFormat.PACKAGE =>
                val end   = view.readEnd()
                val name  = extractPackageName(view, ctx)
                val sym   = makeUnresolvedSym(name.asString, ctx.home)
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
                val sym = ctx.addrMap.getOrElse(startAddr, makeUnresolvedSym(name.asString, ctx.home))
                Tasty.Tree.ValDef(sym, tpt, rhs)

            case TastyFormat.DEFDEF =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                val sym     = ctx.addrMap.getOrElse(startAddr, makeUnresolvedSym(name.asString, ctx.home))
                // Collect parameter clauses (TYPEPARAM and PARAM nodes), then result type, then optional rhs.
                val (paramss, tpt) = readDefDefParamsAndTpt(view, end, ctx)
                val rhs            = readOptionalRhs(view, end, ctx)
                view.goto(end)
                Tasty.Tree.DefDef(sym, paramss, tpt, rhs)

            case TastyFormat.TYPEDEF =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                val sym     = ctx.addrMap.getOrElse(startAddr, makeUnresolvedSym(name.asString, ctx.home))
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
                val end = view.readEnd()
                view.goto(end)
                Tasty.Tree.Unknown(TastyFormat.IMPORT, Math.toIntExact(end - startAddr))

            case TastyFormat.TYPEPARAM =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                val sym     = ctx.addrMap.getOrElse(startAddr, makeUnresolvedSym(name.asString, ctx.home))
                val tpt     = readTypeOrSkip(view, end, ctx)
                view.goto(end)
                Tasty.Tree.TypeDef(sym, tpt)

            case TastyFormat.PARAM =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val name    = nameFromRef(nameRef, ctx)
                val sym     = ctx.addrMap.getOrElse(startAddr, makeUnresolvedSym(name.asString, ctx.home))
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
                val from = ctx.addrMap.getOrElse(addr, makeUnresolvedSym(s"return-target@$addr", ctx.home))
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
                val end = view.readEnd()
                view.goto(end)
                Tasty.Tree.Unknown(TastyFormat.SELECTouter, Math.toIntExact(end - startAddr))

            case TastyFormat.REPEATED =>
                val end   = view.readEnd()
                val trees = readTreesUntil(view, end, ctx)
                view.goto(end)
                // Represent as Apply to SeqLiteral-like.
                Tasty.Tree.Apply(
                    Tasty.Tree.Ident(Tasty.Name("_repeated"), Tasty.Type.Named(makeUnresolvedSym("repeated", ctx.home))),
                    trees
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

            case TastyFormat.ANNOTATEDtype | TastyFormat.ANNOTATEDtpt =>
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
                Tasty.Tree.ClassDef(makeUnresolvedSym("template", ctx.home), tmpl)

            case TastyFormat.SUPER =>
                val end  = view.readEnd()
                val qual = readTree(view, ctx)
                val mix =
                    if view.position < end then
                        Maybe(nameFromRef(view.readNat(), ctx))
                    else Maybe.Absent
                view.goto(end)
                Tasty.Tree.Super(qual, mix)

            // All remaining category-5 nodes: skip and return Unknown.
            case other if other >= TastyFormat.firstLengthTreeTag =>
                val end = view.readEnd()
                view.goto(end)
                Tasty.Tree.Unknown(other, Math.toIntExact(end - startAddr))

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
            case other =>
                throw new DecodeException(s"unknown category-1 modifier tag $other", view.position.toLong)
        Tasty.Tree.Modifier(flag)
    end decodeCategoryOneModifier

    // ── Template decode ───────────────────────────────────────────────────────

    /** Read a TEMPLATE node. Tag byte has already been consumed (or not; we consume it here if present). */
    private def readTemplate(view: ByteView, ctx: DecodeCtx): Tasty.Tree.Template =
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
    private def readTemplateBody(view: ByteView, end: Long, ctx: DecodeCtx): Tasty.Tree.Template =
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
    private def readCaseDefs(view: ByteView, end: Long, ctx: DecodeCtx): Chunk[Tasty.Tree.CaseDef] =
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

    /** Read optional guard + mandatory body from a CASEDEF payload (after pattern has been read). */
    private def readCaseDefGuardAndBody(view: ByteView, end: Long, ctx: DecodeCtx): (Maybe[Tasty.Tree], Tasty.Tree) =
        // dotty places guard then body; guard is optional.
        // Heuristic: if two trees remain, first is guard, second is body. If one, it is the body.
        if view.position >= end then
            (Maybe.Absent, Tasty.Tree.Unknown(0, 0))
        else
            val first = readTree(view, ctx)
            if view.position < end then
                val body = readTree(view, ctx)
                (Maybe(first), body)
            else
                (Maybe.Absent, first)
            end if
        end if
    end readCaseDefGuardAndBody

    // ── Unapply helpers ───────────────────────────────────────────────────────

    /** Read implicits (IMPLICITarg-tagged) and patterns from UNAPPLY payload (after fun). */
    private def readUnapplyParts(
        view: ByteView,
        end: Long,
        ctx: DecodeCtx
    ): (Chunk[Tasty.Tree], Chunk[Tasty.Tree]) =
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

    // ── DefDef helpers ────────────────────────────────────────────────────────

    /** Read TYPEPARAM and PARAM groups until the result type, then read result type. */
    private def readDefDefParamsAndTpt(
        view: ByteView,
        end: Long,
        ctx: DecodeCtx
    ): (Chunk[Chunk[Tasty.Tree]], Tasty.Type) =
        val paramss = new mutable.ArrayBuffer[Chunk[Tasty.Tree]]()
        var tpt     = Tasty.Type.Named(makeUnresolvedSym("unknown-tpt", ctx.home)): Tasty.Type
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
    private def readOneParamClause(view: ByteView, end: Long, ctx: DecodeCtx): Chunk[Tasty.Tree] =
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
    private def readType(view: ByteView, ctx: DecodeCtx): Tasty.Type =
        TypeUnpickler.readTypeForTree(view, ctx.typeSession)
    end readType

    /** Read type nodes until position reaches end. */
    private def readTypesUntil(view: ByteView, end: Long, ctx: DecodeCtx): Chunk[Tasty.Type] =
        val buf = new mutable.ArrayBuffer[Tasty.Type]()
        while view.position < end do
            val peek = view.peekByte(view.position) & 0xff
            if !isTypeTag(peek) then return Chunk.from(buf.toSeq)
            buf += readType(view, ctx)
        end while
        Chunk.from(buf.toSeq)
    end readTypesUntil

    /** Read the first type node in the payload if the current tag looks like a type tag; skip modifiers first. */
    private def readTypeOrSkip(view: ByteView, end: Long, ctx: DecodeCtx): Tasty.Type =
        skipModifierTags(view, end)
        if view.position < end then
            val peek = view.peekByte(view.position) & 0xff
            if isTypeTag(peek) then
                readType(view, ctx)
            else
                Tasty.Type.Named(makeUnresolvedSym("unknown-tpt", ctx.home))
            end if
        else
            Tasty.Type.Named(makeUnresolvedSym("unknown-tpt", ctx.home))
        end if
    end readTypeOrSkip

    /** Read an optional rhs tree: the first remaining content after modifiers, or Absent.
      *
      * Note: does not filter by isTreeTag because literal constants (INTconst etc.) are valid rhs trees but are also returned by isTypeTag
      * (they can appear as ConstantType in type position). Any non-modifier byte after the declared type is interpreted as the rhs tree.
      */
    private def readOptionalRhs(view: ByteView, end: Long, ctx: DecodeCtx): Maybe[Tasty.Tree] =
        skipModifierTags(view, end)
        if view.position < end then Maybe(readTree(view, ctx))
        else Maybe.Absent
    end readOptionalRhs

    // ── Package name extraction ───────────────────────────────────────────────

    private def extractPackageName(view: ByteView, ctx: DecodeCtx): Tasty.Name =
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
    private def skipOneTree(view: ByteView): Unit =
        val tag = view.readByte() & 0xff
        skipTreeBody(tag, view)
    end skipOneTree

    /** Skip the body of a tree node whose tag byte has already been consumed. */
    private def skipTreeBody(tag: Int, view: ByteView): Unit =
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
    private def skipModifierTags(view: ByteView, end: Long): Unit =
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

    private def nameFromRef(nameRef: Int, ctx: DecodeCtx): Tasty.Name =
        if nameRef >= 0 && nameRef < ctx.names.length then ctx.names(nameRef)
        else Tasty.Name(s"name@$nameRef")
    end nameFromRef

    private def makeUnresolvedSym(name: String, home: ClasspathRef): Tasty.Symbol =
        InternalSymbol.makeSymbol(
            Tasty.SymbolKind.Unresolved,
            Tasty.Flags.empty,
            Tasty.Name(name),
            null,
            home,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
    end makeUnresolvedSym

end TreeUnpickler
