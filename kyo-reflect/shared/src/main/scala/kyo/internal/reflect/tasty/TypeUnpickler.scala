package kyo.internal.reflect.tasty

import kyo.*
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.query.UnresolvedRef
import kyo.internal.reflect.symbol.SingleAssign
import kyo.internal.reflect.symbol.Symbol as InternalSymbol
import kyo.internal.reflect.type_.TypeArena
import kyo.internal.reflect.type_.TypeOps
import scala.collection.mutable

/** Decodes TASTy type nodes into Reflect.Type values.
  *
  * Entry point: TypeUnpickler.readAllTypes is called by AstUnpickler (Phase 4 wiring) after pass 1 has produced addrMap. readType is the
  * internal recursive descent function.
  *
  * Key state per decode session (all mutable, file-scoped):
  *   - addrCache: Addr->Type, used for SHAREDtype back-references.
  *   - inProgressRec: Addr->Rec placeholder, used to break RECtype/RECthis cycles.
  *   - binderAddrMap: Addr->Chunk[Symbol], maps lambda start addr to param symbol list.
  *   - placeholders: accumulates UnresolvedRef entries for cross-file references.
  *
  * Cross-platform: no JVM-only APIs; all bit-pattern conversions use java.lang.Float/Double which are available on Scala.js and Scala
  * Native.
  */
object TypeUnpickler:

    /** Synthetic sentinel symbol for MatchCaseType representation.
      *
      * Each MATCHCASEtype node is decoded as Applied(Named(MatchCaseSentinel), Chunk(pat, rhs)). Consumers extract pat and rhs by position
      * from the args chunk.
      */
    val MatchCaseSentinel: Reflect.Symbol =
        Reflect.Symbol.make(
            Reflect.SymbolKind.Unresolved,
            Reflect.Flags.empty,
            Reflect.Name("$$MatchCase"),
            null,
            new ClasspathRef,
            Reflect.Symbol.TastyOrigin(Map.empty, 0, 0)
        )

    /** Decode a single type node from `view`.
      *
      * @param view
      *   ByteView positioned at the tag byte of the type node.
      * @param names
      *   0-based name array from NameUnpickler.
      * @param addrMap
      *   Addr->Symbol map from AstUnpickler pass 1.
      * @param arena
      *   Per-file TypeArena for hash-consing decoded types.
      * @param home
      *   ClasspathRef for synthetic symbols (unresolved cross-file refs).
      * @return
      *   The decoded Reflect.Type, interned in `arena`.
      */
    def readType(
        view: ByteView,
        names: Array[Reflect.Name],
        addrMap: Map[Int, Reflect.Symbol],
        arena: TypeArena,
        home: ClasspathRef
    )(using Frame): (Reflect.Type, Chunk[UnresolvedRef]) < (Sync & Abort[ReflectError]) =
        val result =
            try
                val addrCache     = new mutable.HashMap[Int, Reflect.Type]()
                val inProgressRec = new mutable.HashMap[Int, Reflect.Type.Rec]()
                val binderAddrMap = new mutable.HashMap[Int, Chunk[Reflect.Symbol]]()
                val placeholders  = new mutable.ArrayBuffer[UnresolvedRef]()
                val ctx           = DecodeCtx(names, addrMap, arena, home, addrCache, inProgressRec, binderAddrMap, placeholders)
                val t             = readTypeNode(view, ctx)
                Right((t, Chunk.from(placeholders)))
            catch
                case ex: ArrayIndexOutOfBoundsException =>
                    Left(ReflectError.MalformedSection("ASTs", s"unexpected end reading type: ${ex.getMessage}"))
                case ex: Exception =>
                    Left(ReflectError.MalformedSection("ASTs", s"type decode error: ${ex.getMessage}"))
        result match
            case Right(r)  => Sync.defer(r)
            case Left(err) => Abort.fail(err)
    end readType

    /** Synchronous (non-Kyo) variant used by AstUnpickler.runPass1.
      *
      * Shares state across all type positions in one file by accepting a shared DecodeSession. Throws on decode error (caught by
      * readPass1's try/catch).
      *
      * @param view
      *   ByteView positioned at the tag byte of the type node.
      * @param session
      *   Shared decode session for the file being decoded.
      * @return
      *   The decoded Reflect.Type, interned in `session.arena`.
      */
    private[kyo] def readTypeIntoSession(view: ByteView, session: DecodeSession): Reflect.Type =
        // Use the live addrMap snapshot at call time so locally-defined symbols found so far are visible.
        val ctx = DecodeCtx(
            session.names,
            session.liveAddrMap.toMap,
            session.arena,
            session.home,
            session.addrCache,
            session.inProgressRec,
            session.binderAddrMap,
            session.placeholders
        )
        readTypeNode(view, ctx)
    end readTypeIntoSession

    /** Mutable decode session shared across all type positions in one file. Created by AstUnpickler and passed to readTypeIntoSession.
      *
      * `liveAddrMap` is the mutable HashMap being built by AstUnpickler.walkStats. Passing it directly (not as a snapshot) lets type decode
      * find locally-defined symbols as the walk progresses.
      */
    final private[kyo] class DecodeSession(
        val names: Array[Reflect.Name],
        val liveAddrMap: mutable.HashMap[Int, Reflect.Symbol],
        val arena: TypeArena,
        val home: ClasspathRef
    ):
        val addrCache: mutable.HashMap[Int, Reflect.Type]              = new mutable.HashMap()
        val inProgressRec: mutable.HashMap[Int, Reflect.Type.Rec]      = new mutable.HashMap()
        val binderAddrMap: mutable.HashMap[Int, Chunk[Reflect.Symbol]] = new mutable.HashMap()
        val placeholders: mutable.ArrayBuffer[UnresolvedRef]           = new mutable.ArrayBuffer()
    end DecodeSession

    // Internal decode context passed through all recursive calls.
    final private class DecodeCtx(
        val names: Array[Reflect.Name],
        val addrMap: Map[Int, Reflect.Symbol],
        val arena: TypeArena,
        val home: ClasspathRef,
        val addrCache: mutable.HashMap[Int, Reflect.Type],
        val inProgressRec: mutable.HashMap[Int, Reflect.Type.Rec],
        val binderAddrMap: mutable.HashMap[Int, Chunk[Reflect.Symbol]],
        val placeholders: mutable.ArrayBuffer[UnresolvedRef]
    )

    private def makeUnresolvedSym(fqn: String, home: ClasspathRef): Reflect.Symbol =
        InternalSymbol.makeSymbol(
            Reflect.SymbolKind.Unresolved,
            Reflect.Flags.empty,
            Reflect.Name(fqn),
            null,
            home,
            Reflect.Symbol.TastyOrigin(Map.empty, 0, 0)
        )

    /** Decode one type node. tag byte not yet consumed. Records startAddr -> result in addrCache. */
    private def readTypeNode(view: ByteView, ctx: DecodeCtx): Reflect.Type =
        val startAddr = view.position
        val tag       = view.readByte() & 0xff
        val t         = decodeTag(tag, startAddr, view, ctx)
        val interned  = ctx.arena.intern(t)
        // Record in addrCache for SHAREDtype back-references (only for non-SHAREDtype results).
        if tag != TastyFormat.SHAREDtype then
            ctx.addrCache(startAddr) = interned
        interned
    end readTypeNode

    /** Dispatch on the tag byte (already consumed). */
    private def decodeTag(tag: Int, startAddr: Int, view: ByteView, ctx: DecodeCtx): Reflect.Type =
        tag match

            // ── Category 1 constant types ─────────────────────────────────────────
            case TastyFormat.UNITconst  => Reflect.Type.ConstantType(Reflect.Constant.UnitConst)
            case TastyFormat.FALSEconst => Reflect.Type.ConstantType(Reflect.Constant.BooleanConst(false))
            case TastyFormat.TRUEconst  => Reflect.Type.ConstantType(Reflect.Constant.BooleanConst(true))
            case TastyFormat.NULLconst  => Reflect.Type.ConstantType(Reflect.Constant.NullConst)

            // ── Category 2 (tag + Nat) ────────────────────────────────────────────

            case TastyFormat.SHAREDtype =>
                val astRef = view.readNat()
                ctx.addrCache.getOrElse(
                    astRef, {
                        throw new ArrayIndexOutOfBoundsException(s"SHAREDtype ref $astRef not in addrCache")
                    }
                )

            case TastyFormat.TERMREFdirect =>
                val astRef = view.readNat()
                ctx.addrMap.get(astRef) match
                    case Some(sym) => Reflect.Type.Named(sym)
                    case None      => Reflect.Type.Named(makeUnresolvedSym(s"termref@$astRef", ctx.home))

            case TastyFormat.TYPEREFdirect =>
                val astRef = view.readNat()
                ctx.addrMap.get(astRef) match
                    case Some(sym) => Reflect.Type.Named(sym)
                    case None      => Reflect.Type.Named(makeUnresolvedSym(s"typeref@$astRef", ctx.home))

            case TastyFormat.TERMREFpkg =>
                val nameRef = view.readNat()
                val fqn     = ctx.names(nameRef).asString
                val ref     = new UnresolvedRef(fqn, new SingleAssign[Reflect.Type])
                ctx.placeholders += ref
                Reflect.Type.Named(makeUnresolvedSym(fqn, ctx.home))

            case TastyFormat.TYPEREFpkg =>
                val nameRef = view.readNat()
                val fqn     = ctx.names(nameRef).asString
                val ref     = new UnresolvedRef(fqn, new SingleAssign[Reflect.Type])
                ctx.placeholders += ref
                Reflect.Type.Named(makeUnresolvedSym(fqn, ctx.home))

            case TastyFormat.RECthis =>
                val recAddr = view.readNat()
                // Look for the in-progress Rec node; if not found, use addrCache.
                ctx.inProgressRec.get(recAddr) match
                    case Some(recNode) => Reflect.Type.RecThis(recNode)
                    case None =>
                        ctx.addrCache.get(recAddr) match
                            case Some(recNode) => Reflect.Type.RecThis(recNode)
                            case None          =>
                                // The Rec node hasn't been decoded yet; return a placeholder.
                                Reflect.Type.RecThis(Reflect.Type.Named(makeUnresolvedSym(s"rec@$recAddr", ctx.home)))
                end match

            // Constant types (category 2: tag + Nat)
            case TastyFormat.BYTEconst =>
                Reflect.Type.ConstantType(Reflect.Constant.ByteConst(view.readNat().toByte))
            case TastyFormat.SHORTconst =>
                Reflect.Type.ConstantType(Reflect.Constant.ShortConst(view.readNat().toShort))
            case TastyFormat.CHARconst =>
                Reflect.Type.ConstantType(Reflect.Constant.CharConst(view.readNat().toChar))
            case TastyFormat.INTconst =>
                Reflect.Type.ConstantType(Reflect.Constant.IntConst(view.readInt()))
            case TastyFormat.LONGconst =>
                Reflect.Type.ConstantType(Reflect.Constant.LongConst(view.readLongNat()))
            case TastyFormat.FLOATconst =>
                Reflect.Type.ConstantType(Reflect.Constant.FloatConst(java.lang.Float.intBitsToFloat(view.readNat())))
            case TastyFormat.DOUBLEconst =>
                Reflect.Type.ConstantType(Reflect.Constant.DoubleConst(java.lang.Double.longBitsToDouble(view.readLongNat())))
            case TastyFormat.STRINGconst =>
                val nameRef = view.readNat()
                Reflect.Type.ConstantType(Reflect.Constant.StringConst(ctx.names(nameRef).asString))

            // ── Category 3 (tag + AST) ────────────────────────────────────────────

            case TastyFormat.THIS =>
                val inner = readTypeNode(view, ctx)
                inner match
                    case Reflect.Type.Named(sym) => Reflect.Type.ThisType(sym)
                    case _                       => Reflect.Type.ThisType(makeUnresolvedSym("this-unknown", ctx.home))

            case TastyFormat.CLASSconst =>
                val inner = readTypeNode(view, ctx)
                Reflect.Type.ConstantType(Reflect.Constant.ClassConst(inner))

            case TastyFormat.BYNAMEtype =>
                val underlying = readTypeNode(view, ctx)
                Reflect.Type.ByName(underlying)

            case TastyFormat.RECtype =>
                // Cycle-break: allocate a typed placeholder Rec before decoding parent.
                // inProgressRec is keyed by the RECtype node's start addr so RECthis nodes
                // encountered during parent decoding can find the placeholder.
                val sentinelSym                   = makeUnresolvedSym(s"rec-placeholder@$startAddr", ctx.home)
                val placeholder: Reflect.Type.Rec = Reflect.Type.Rec(Reflect.Type.Named(sentinelSym))
                ctx.inProgressRec(startAddr) = placeholder
                val parent = readTypeNode(view, ctx)
                discard(ctx.inProgressRec.remove(startAddr))
                val result = Reflect.Type.Rec(parent)
                // Patch addrCache so RECthis nodes that looked up startAddr find the real Rec.
                ctx.addrCache(startAddr) = result
                result

            // ── Category 4 (tag + Nat + AST) ─────────────────────────────────────

            case TastyFormat.TERMREFsymbol =>
                val astRef = view.readNat()
                val qual   = readTypeNode(view, ctx)
                ctx.addrMap.get(astRef) match
                    case Some(sym) => Reflect.Type.TermRef(qual, sym.name)
                    case None      => Reflect.Type.TermRef(qual, Reflect.Name(s"termrefsym@$astRef"))

            case TastyFormat.TERMREF =>
                val nameRef = view.readNat()
                val qual    = readTypeNode(view, ctx)
                Reflect.Type.TermRef(qual, ctx.names(nameRef))

            case TastyFormat.TYPEREFsymbol =>
                val astRef = view.readNat()
                // qual is present but for Named resolution we only need the sym.
                discard(readTypeNode(view, ctx)) // consume qual
                ctx.addrMap.get(astRef) match
                    case Some(sym) => Reflect.Type.Named(sym)
                    case None      => Reflect.Type.Named(makeUnresolvedSym(s"typerefsym@$astRef", ctx.home))

            case TastyFormat.TYPEREF =>
                val nameRef = view.readNat()
                val qual    = readTypeNode(view, ctx)
                // For TYPEREF, try to resolve from qual context; return Named if resolvable.
                // For now, build a TermRef-style qualified reference.
                Reflect.Type.TermRef(qual, ctx.names(nameRef))

            // ── Category 5 (tag + Length + payload) ──────────────────────────────

            case TastyFormat.APPLIEDtype =>
                val end   = view.readEnd()
                val tycon = readTypeNode(view, ctx)
                val args  = readTypesUntil(view, end, ctx)
                TypeOps.applied(tycon, Chunk.from(args))

            case TastyFormat.ANNOTATEDtype =>
                val end        = view.readEnd()
                val underlying = readTypeNode(view, ctx)
                // Skip the annotation term (a full TASTy term tree).
                // Full annotation decode is deferred; store empty argsPickle for now.
                skipToEnd(view, end)
                Reflect.Type.Annotated(
                    underlying,
                    Reflect.Annotation(Reflect.Type.Named(makeUnresolvedSym("ann", ctx.home)), Chunk.empty)
                )

            case TastyFormat.ANDtype =>
                val end   = view.readEnd()
                val left  = readTypeNode(view, ctx)
                val right = readTypeNode(view, ctx)
                view.goto(end)
                TypeOps.andType(left, right)

            case TastyFormat.ORtype =>
                val end   = view.readEnd()
                val left  = readTypeNode(view, ctx)
                val right = readTypeNode(view, ctx)
                view.goto(end)
                Reflect.Type.OrType(left, right)

            case TastyFormat.SUPERtype =>
                val end        = view.readEnd()
                val thisType   = readTypeNode(view, ctx)
                val underlying = readTypeNode(view, ctx)
                view.goto(end)
                Reflect.Type.SuperType(thisType, underlying)

            case TastyFormat.REFINEDtype =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val parent  = readTypeNode(view, ctx)
                val info    = readTypeNode(view, ctx)
                view.goto(end)
                Reflect.Type.Refinement(parent, ctx.names(nameRef), info)

            case TastyFormat.MATCHtype =>
                val end   = view.readEnd()
                val bound = readTypeNode(view, ctx)
                val scrut = readTypeNode(view, ctx)
                val cases = readTypesUntil(view, end, ctx)
                Reflect.Type.MatchType(bound, scrut, Chunk.from(cases))

            case TastyFormat.MATCHCASEtype =>
                val end = view.readEnd()
                val pat = readTypeNode(view, ctx)
                val rhs = readTypeNode(view, ctx)
                view.goto(end)
                // Represent as Applied(Named(MatchCaseSentinel), Chunk(pat, rhs))
                Reflect.Type.Applied(Reflect.Type.Named(MatchCaseSentinel), Chunk(pat, rhs))

            case TastyFormat.FLEXIBLEtype =>
                val end        = view.readEnd()
                val underlying = readTypeNode(view, ctx)
                view.goto(end)
                Reflect.Type.FlexibleType(underlying)

            case TastyFormat.TYPELAMBDAtype =>
                val end = view.readEnd()
                // Pre-scan TypesNames (which come AFTER result in the payload), allocate param symbols,
                // register in binderAddrMap (keyed on this node's start addr), then decode result.
                // PARAMtype nodes in the result use binder_ASTRef = startAddr to look up params.
                val paramSyms = readTypeLambdaParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Reflect.Type.TypeLambda(Chunk.from(paramSyms), resultType)

            case TastyFormat.POLYtype =>
                val end       = view.readEnd()
                val paramSyms = readTypeLambdaParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Reflect.Type.TypeLambda(Chunk.from(paramSyms), resultType)

            case TastyFormat.METHODtype =>
                val end       = view.readEnd()
                val paramSyms = readMethodParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Reflect.Type.TypeLambda(Chunk.from(paramSyms), resultType)

            case TastyFormat.PARAMtype =>
                val end        = view.readEnd()
                val binderAddr = view.readNat()
                val paramNum   = view.readNat()
                view.goto(end)
                ctx.binderAddrMap.get(binderAddr) match
                    case Some(params) if paramNum < params.length => Reflect.Type.ParamRef(params(paramNum), paramNum)
                    case _ => Reflect.Type.Named(makeUnresolvedSym(s"param@$binderAddr:$paramNum", ctx.home))

            case TastyFormat.TYPEREFin =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                discard(readTypeNode(view, ctx)) // qual
                discard(readTypeNode(view, ctx)) // namespace
                view.goto(end)
                val fqn = ctx.names(nameRef).asString
                val ref = new UnresolvedRef(fqn, new SingleAssign[Reflect.Type])
                ctx.placeholders += ref
                Reflect.Type.Named(makeUnresolvedSym(fqn, ctx.home))

            case TastyFormat.TERMREFin =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val qual    = readTypeNode(view, ctx)
                discard(readTypeNode(view, ctx)) // namespace
                view.goto(end)
                Reflect.Type.TermRef(qual, ctx.names(nameRef))

            case TastyFormat.TYPEBOUNDS =>
                // TYPEBOUNDS Length lowOrAlias_Type high_Type? Variance*
                // When high is present: Wildcard(lo, hi). When only alias: the alias type directly.
                val end = view.readEnd()
                val lo  = readTypeNode(view, ctx)
                // Peek: if next byte is a variance tag (category 1, 1-59 but specifically STABLE/COVARIANT/CONTRAVARIANT)
                // or we're at end, there is no hi.
                if view.position >= end then
                    view.goto(end)
                    lo
                else
                    val nextTag = view.peekByte(view.position) & 0xff
                    if isVarianceTag(nextTag) || nextTag == 0 then
                        // Only alias; skip variance tags and return lo directly.
                        view.goto(end)
                        lo
                    else
                        val hi = readTypeNode(view, ctx)
                        // Skip variance tags.
                        view.goto(end)
                        Reflect.Type.Wildcard(lo, hi)
                    end if
                end if

            case TastyFormat.REPEATED =>
                val end  = view.readEnd()
                val elem = readTypeNode(view, ctx)
                view.goto(end)
                Reflect.Type.Repeated(elem)

            case other if other >= TastyFormat.firstLengthTreeTag =>
                // Unknown category 5 node: skip and return a placeholder.
                val end = view.readEnd()
                view.goto(end)
                Reflect.Type.Named(makeUnresolvedSym(s"unknown-type-tag-$other", ctx.home))

            case other =>
                // Unknown category 1-4 node: skip body and return placeholder.
                skipTreeBody(other, view)
                Reflect.Type.Named(makeUnresolvedSym(s"unknown-type-tag-$other", ctx.home))

        end match
    end decodeTag

    /** Read type lambda / poly type parameters from the payload.
      *
      * TypesNames = TypeName* where TypeName = typeOrBounds_ASTRef paramName_NameRef. The params come BEFORE the result type in the payload
      * for TYPELAMBDAtype/POLYtype. We read them here (scanning forward), allocate symbols, then caller reads result type.
      *
      * Since the payload is: result_Type TypesNames (per grammar), we need to read result first and params after. But to support PARAMtype
      * references in result, we use a forward scan.
      *
      * Implementation: skip the result type first (to find TypesNames), collect param names, rewind to just after result, return param
      * symbols. The result is then re-read by the caller.
      *
      * Actually per the grammar: POLYtype = result_Type TypesNames, where result comes first. PARAMtype uses the binder's start addr. We
      * pre-register an empty binding, decode result (PARAMtype will return placeholder), then return params.
      *
      * Simpler correct approach: for Phase 4, we use a two-pass for lambda bodies:
      *   1. Skip result type (but we cannot skip because it may contain type refs we need)
      *   2. After result, read TypesNames to get param symbols
      *   3. Register binderAddrMap, return params
      *
      * The result is decoded by the caller AFTER this returns (result position has been rewound to the start of result type after skipping
      * TypesNames).
      *
      * Actually: the grammar for POLYtype/TYPELAMBDAtype puts result BEFORE TypesNames. So layout in bytes is: [result_Type bytes]
      * [typeRef0 nameRef0] [typeRef1 nameRef1] ...
      *
      * We: skip result, collect TypesNames, rewind to result start, return param syms. Caller then reads result from the rewound position.
      */
    private def readTypeLambdaParams(
        view: ByteView,
        end: Int,
        ctx: DecodeCtx
    ): mutable.ArrayBuffer[Reflect.Symbol] =
        val resultStart = view.position
        // Skip result type to find TypesNames start.
        skipOneType(view)
        // Collect TypesNames = (typeOrBounds_ASTRef paramName_NameRef)*
        val paramSyms = new mutable.ArrayBuffer[Reflect.Symbol]()
        while view.position < end do
            val typeRef = view.readNat()
            val nameRef = view.readNat()
            val symName = ctx.names(nameRef)
            val sym = ctx.addrMap.get(typeRef) match
                case Some(existingSym) => existingSym
                case None =>
                    InternalSymbol.makeSymbol(
                        Reflect.SymbolKind.TypeParam,
                        Reflect.Flags.empty,
                        symName,
                        null,
                        ctx.home,
                        Reflect.Symbol.TastyOrigin(Map.empty, 0, 0)
                    )
            paramSyms += sym
        end while
        // Rewind to result type start so caller can decode result.
        view.goto(resultStart)
        paramSyms
    end readTypeLambdaParams

    /** Read method params from a METHODtype payload.
      *
      * METHODtype = result_Type TypesNames Modifier* TypesNames = TypeName* where TypeName = typeOrBounds_ASTRef paramName_NameRef
      *
      * Same two-pass approach as readTypeLambdaParams.
      */
    private def readMethodParams(
        view: ByteView,
        end: Int,
        ctx: DecodeCtx
    ): mutable.ArrayBuffer[Reflect.Symbol] =
        val resultStart = view.position
        // Skip result type.
        skipOneType(view)
        // Collect TypesNames until we hit modifiers or end.
        val paramSyms = new mutable.ArrayBuffer[Reflect.Symbol]()
        while view.position < end do
            val tag = view.peekByte(view.position) & 0xff
            if isModifierOrVarianceTag(tag) then
                view.goto(end)
            else
                val typeRef = view.readNat()
                val nameRef = view.readNat()
                val symName = ctx.names(nameRef)
                val sym = ctx.addrMap.get(typeRef) match
                    case Some(existingSym) => existingSym
                    case None =>
                        InternalSymbol.makeSymbol(
                            Reflect.SymbolKind.Parameter,
                            Reflect.Flags.empty,
                            symName,
                            null,
                            ctx.home,
                            Reflect.Symbol.TastyOrigin(Map.empty, 0, 0)
                        )
                paramSyms += sym
            end if
        end while
        view.goto(resultStart)
        paramSyms
    end readMethodParams

    /** Read type nodes until position reaches `end`. */
    private def readTypesUntil(view: ByteView, end: Int, ctx: DecodeCtx): mutable.ArrayBuffer[Reflect.Type] =
        val buf = new mutable.ArrayBuffer[Reflect.Type]()
        while view.position < end do
            buf += readTypeNode(view, ctx)
        buf
    end readTypesUntil

    /** Skip to the given end position. */
    private def skipToEnd(view: ByteView, end: Int): Unit =
        view.goto(end)

    /** Skip exactly one type node from the current position (tag not yet consumed). */
    private def skipOneType(view: ByteView): Unit =
        val tag = view.readByte() & 0xff
        skipTreeBody(tag, view)

    /** Skip the body of a tree node whose tag has already been consumed. */
    private def skipTreeBody(tag: Int, view: ByteView): Unit =
        if tag >= TastyFormat.firstLengthTreeTag then
            val end = view.readEnd()
            view.goto(end)
        else if tag >= 110 && tag <= 127 then
            discard(view.readNat())
            skipOneType(view)
        else if tag >= 90 && tag <= 109 then
            skipOneType(view)
        else if tag >= 60 && tag <= 89 then
            discard(view.readNat())
        // else category 1: nothing to skip

    private def isVarianceTag(tag: Int): Boolean =
        tag == TastyFormat.STABLE || tag == TastyFormat.COVARIANT || tag == TastyFormat.CONTRAVARIANT

    private def isModifierOrVarianceTag(tag: Int): Boolean =
        (tag >= 1 && tag <= 59) ||
            tag == TastyFormat.PRIVATEqualified ||
            tag == TastyFormat.PROTECTEDqualified ||
            tag == TastyFormat.ANNOTATION

end TypeUnpickler
