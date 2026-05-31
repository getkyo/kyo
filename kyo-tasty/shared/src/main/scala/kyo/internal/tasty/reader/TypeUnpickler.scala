package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.type_.TypeArena
import kyo.internal.tasty.type_.TypeOps
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** Decodes TASTy type nodes into Tasty.Type values.
  *
  * Entry point: TypeUnpickler.readAllTypes is called by AstUnpickler (Phase 4 wiring) after pass 1 has produced addrMap. readType is the
  * internal recursive descent function.
  *
  * Key state per decode session (all mutable, file-scoped):
  *   - addrCache: Addr->Type, used for SHAREDtype back-references.
  *   - inProgressRec: Addr->Rec placeholder, used to break RECtype/RECthis cycles.
  *   - binderAddrMap: Addr->Chunk[Symbol], maps lambda start addr to param symbol list.
  *   - cross-file references: produce Named(SymbolId(-1)) entries directly (UnresolvedRef deleted in Phase 07).
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
    /** Phase-B temporary SymbolId offset.
      *
      * During Phase B decode, `TERMREFdirect` and `TYPEREFdirect` type nodes reference local symbols by TASTy byte address. Instead of
      * using `SymbolId(-1)` (which loses the address information), we encode the address as `SymbolId(PHASE_B_ADDR_OFFSET + addr)`. This
      * preserves the address so that Phase C `finalizeMerge` can remap these to correct final SymbolIds using the file's addrMap.
      *
      * The offset (2^28 = 268_435_456) is chosen so that it is far above any realistic classpath symbol count and distinguishable from both
      * negative sentinel (-1) and final SymbolIds (0 to N-1).
      */
    private[kyo] val PHASE_B_ADDR_OFFSET: Int = 1 << 28

    val MatchCaseSentinel: Tasty.Symbol =
        InternalSymbol.makeSymbol(
            Tasty.SymbolKind.Unresolved,
            Tasty.Flags.empty,
            Tasty.Name("$$MatchCase")
        )
    end MatchCaseSentinel

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
      *   (formerly ClasspathRef; deleted in Phase 07)
      * @return
      *   The decoded Tasty.Type, interned in `arena`.
      */
    def readType(
        view: ByteView,
        names: Array[Tasty.Name],
        addrMap: IntMap[Tasty.Symbol],
        arena: TypeArena,
        sectionBytes: Array[Byte],
        sectionOffset: Int
    )(using frame: Frame)(using AllowUnsafe): Tasty.Type < (Sync & Abort[TastyError]) =
        val result =
            try
                val addrCache     = new mutable.HashMap[Int, Tasty.Type]()
                val inProgressRec = new mutable.HashMap[Int, Tasty.Type.Rec]()
                val binderAddrMap = new mutable.HashMap[Int, Chunk[Tasty.Symbol]]()
                val ctx =
                    DecodeCtx(
                        names,
                        addrMap,
                        arena,
                        addrCache,
                        inProgressRec,
                        binderAddrMap,
                        sectionBytes,
                        sectionOffset,
                        frame
                    )
                val t = readTypeNode(view, ctx)
                Right(t)
            catch
                case ex: ArrayIndexOutOfBoundsException =>
                    Left(TastyError.MalformedSection("ASTs", s"unexpected end reading type: ${ex.getMessage}", view.position))
                case ex: Exception =>
                    Left(TastyError.MalformedSection("ASTs", s"type decode error: ${ex.getMessage}", view.position))
        result match
            case Right(r)  => Sync.defer(r)
            case Left(err) => Abort.fail(err)
    end readType

    /** Shared type decode state for use by TreeUnpickler during body decode.
      *
      * Created once per decodeSync call and threaded through all readType calls within the same body decode. Keeps a shared addrCache for
      * SHAREDtype back-references and inProgressRec for RECtype cycle-breaking.
      *
      * `sectionBytes` is the full AST section byte array; used by readTypeForTree to re-decode SHAREDtype back-references at addresses not
      * yet in addrCache (because Pass 1 decoded them from the section in a separate DecodeSession).
      *
      * Private to the tasty package; TreeUnpickler is a sibling object in the same package.
      */
    final private[tasty] class TreeTypeSession(
        val names: Array[Tasty.Name],
        val addrMap: scala.collection.Map[Int, Tasty.Symbol],
        val arena: TypeArena,
        val sectionBytes: Array[Byte],
        val sectionOffset: Int
    ):
        val addrCache: mutable.HashMap[Int, Tasty.Type]              = new mutable.HashMap()
        val inProgressRec: mutable.HashMap[Int, Tasty.Type.Rec]      = new mutable.HashMap()
        val binderAddrMap: mutable.HashMap[Int, Chunk[Tasty.Symbol]] = new mutable.HashMap()
    end TreeTypeSession

    /** Read one type node using a shared TreeTypeSession (called by TreeUnpickler).
      *
      * Handles SHAREDtype cache misses by re-decoding the referenced type from sectionBytes on demand.
      */
    private[tasty] def readTypeForTree(view: ByteView, session: TreeTypeSession)(using AllowUnsafe): Tasty.Type =
        // flow-allow: internal frame used here because readTypeForTree is called from
        // TreeUnpickler.decodeSync, which is the OnceCell init lambda for Symbol.body.
        // The init lambda has type () => Tree and cannot accept a Frame parameter.
        // This is the one legitimate flow-allow site; all other decode paths propagate a real Frame.
        val callFrame = Frame.internal
        val peek      = view.peekByte(view.position) & 0xff
        if peek == TastyFormat.SHAREDtype then
            discard(view.readByte()) // consume SHAREDtype tag
            val addr    = view.readNat()
            val absAddr = session.sectionOffset + addr
            session.addrCache.getOrElseUpdate(
                absAddr, {
                    // Cache miss: re-decode the type at the section-relative addr from the full section bytes.
                    // addr is section-relative; absAddr = sectionOffset + addr is the absolute position.
                    val forkView = ByteView(session.sectionBytes, absAddr, session.sectionBytes.length)
                    val forkCtx = DecodeCtx(
                        session.names,
                        session.addrMap,
                        session.arena,
                        session.addrCache,
                        session.inProgressRec,
                        session.binderAddrMap,
                        session.sectionBytes,
                        session.sectionOffset,
                        callFrame
                    )
                    readTypeNode(forkView, forkCtx)
                }
            )
        else
            val ctx = DecodeCtx(
                session.names,
                session.addrMap,
                session.arena,
                session.addrCache,
                session.inProgressRec,
                session.binderAddrMap,
                session.sectionBytes,
                session.sectionOffset,
                callFrame
            )
            readTypeNode(view, ctx)
        end if
    end readTypeForTree

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
      *   The decoded Tasty.Type, interned in `session.arena`.
      */
    private[kyo] def readTypeIntoSession(view: ByteView, session: DecodeSession)(using frame: Frame)(using AllowUnsafe): Tasty.Type =
        // Pass session.liveAddrMap directly -- no per-call IntMap snapshot.
        // Pass session reference for cross-file FQN tracking so Phase C can remap parent types.
        val ctx = DecodeCtx(
            session.names,
            session.liveAddrMap,
            session.arena,
            session.addrCache,
            session.inProgressRec,
            session.binderAddrMap,
            null,
            0,
            frame,
            session
        )
        readTypeNode(view, ctx)
    end readTypeIntoSession

    /** Variant of readTypeIntoSession that also provides sectionBytes, enabling eager annotation arg decode.
      *
      * Used in tests (Phase 09 followup for Phase 08 W-1/W-2) to verify that corrupt annotation arg bytes accumulate errors in
      * `session.annotationDecodeErrors` rather than propagating exceptions.
      *
      * Private to kyo; not part of the public API.
      */
    private[kyo] def readTypeIntoSessionWithBytes(
        view: ByteView,
        session: DecodeSession,
        sectionBytes: Array[Byte],
        sectionOffset: Int
    )(using frame: Frame)(using AllowUnsafe): Tasty.Type =
        val ctx = DecodeCtx(
            session.names,
            session.liveAddrMap,
            session.arena,
            session.addrCache,
            session.inProgressRec,
            session.binderAddrMap,
            sectionBytes,
            sectionOffset,
            frame,
            session
        )
        readTypeNode(view, ctx)
    end readTypeIntoSessionWithBytes

    /** Mutable decode session shared across all type positions in one file. Created by AstUnpickler and passed to readTypeIntoSession.
      *
      * `liveAddrMap` is the mutable HashMap being built by AstUnpickler.walkStats. Passing it directly (not as a snapshot) lets type decode
      * find locally-defined symbols as the walk progresses.
      *
      * `unresolvedIdToFqn` accumulates mappings from the unique negative SymbolIds assigned to cross-file unresolved symbols to their FQNs.
      * Phase C uses this map to look up the FQN and resolve to the correct final SymbolId via `fqnIndex`.
      */
    final private[kyo] class DecodeSession(
        val names: Array[Tasty.Name],
        val liveAddrMap: mutable.HashMap[Int, Tasty.Symbol],
        val arena: TypeArena
    ):
        val addrCache: mutable.HashMap[Int, Tasty.Type]              = new mutable.HashMap()
        val inProgressRec: mutable.HashMap[Int, Tasty.Type.Rec]      = new mutable.HashMap()
        val binderAddrMap: mutable.HashMap[Int, Chunk[Tasty.Symbol]] = new mutable.HashMap()
        val unresolvedIdToFqn: mutable.HashMap[Int, String]          = new mutable.HashMap()
        // Accumulates errors from eager annotation arg decodes in ANNOTATEDtype. Drained into
        // FileResult.errors at the end of AstUnpickler.runPass1 via Pass1Result.annotationDecodeErrors.
        val annotationDecodeErrors: mutable.ArrayBuffer[TastyError] = new mutable.ArrayBuffer()
        // Counter for unique negative IDs: starts at -2 (to distinguish from SymbolId(-1) which is the sentinel).
        private var _unresolvedIdCounter: Int = -2

        def nextUnresolvedId(): Int =
            val id = _unresolvedIdCounter
            _unresolvedIdCounter -= 1
            id
        end nextUnresolvedId
    end DecodeSession

    // Internal decode context passed through all recursive calls.
    // sectionBytes: full AST section bytes for on-demand SHAREDtype re-decode on cache miss.
    // Pass null when decoding from a live DecodeSession (Pass 1) where addrCache is always pre-populated.
    // addrMap accepts any scala.collection.Map so pass1 can pass liveAddrMap (mutable.HashMap) directly
    // without snapshotting into an IntMap on every type-node decode call.
    // frame: call-site Frame, used for Log.warn in the unknown-tag fallback branch.
    // session: reference to the owning DecodeSession for cross-file FQN tracking (null in body decode path).
    final private class DecodeCtx(
        val names: Array[Tasty.Name],
        val addrMap: scala.collection.Map[Int, Tasty.Symbol],
        val arena: TypeArena,
        val addrCache: mutable.HashMap[Int, Tasty.Type],
        val inProgressRec: mutable.HashMap[Int, Tasty.Type.Rec],
        val binderAddrMap: mutable.HashMap[Int, Chunk[Tasty.Symbol]],
        val sectionBytes: Array[Byte],
        val sectionOffset: Int,
        val frame: Frame,
        val session: DecodeSession | Null = null
    )

    private def makeUnresolvedSym(fqn: String)(using AllowUnsafe): Tasty.Symbol =
        InternalSymbol.makeSymbol(
            Tasty.SymbolKind.Unresolved,
            Tasty.Flags.empty,
            Tasty.Name(fqn)
        )

    /** Make a unique negative SymbolId for a cross-file FQN reference and record it in `fqns`.
      *
      * The caller must provide a unique negative `id` (obtained via `session.nextUnresolvedId()`). Records `id -> fqn` in `fqns` so Phase C
      * can resolve `Named(SymbolId(id))` to the correct final SymbolId via fqnIndex lookup.
      */
    private def makeTrackedUnresolvedSym(
        fqn: String,
        fqns: mutable.HashMap[Int, String],
        id: Int
    )(using AllowUnsafe): kyo.internal.tasty.symbol.SymbolId =
        fqns(id) = fqn
        kyo.internal.tasty.symbol.SymbolId(id)
    end makeTrackedUnresolvedSym

    /** Decode one type node. tag byte not yet consumed. Records startAddr -> result in addrCache. */
    private def readTypeNode(view: ByteView, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Type =
        val startAddr = view.positionInt
        val tag       = view.readByte() & 0xff
        val t         = decodeTag(tag, startAddr, view, ctx)
        val interned  = ctx.arena.intern(t)
        // Record in addrCache for SHAREDtype back-references (only for non-SHAREDtype results).
        if tag != TastyFormat.SHAREDtype then
            ctx.addrCache(startAddr) = interned
        interned
    end readTypeNode

    /** Dispatch on the tag byte (already consumed). */
    private def decodeTag(tag: Int, startAddr: Int, view: ByteView, ctx: DecodeCtx)(using AllowUnsafe): Tasty.Type =
        tag match

            // ── Category 1 constant types ─────────────────────────────────────────
            case TastyFormat.UNITconst  => Tasty.Type.ConstantType(Tasty.Constant.UnitConst)
            case TastyFormat.FALSEconst => Tasty.Type.ConstantType(Tasty.Constant.BooleanConst(false))
            case TastyFormat.TRUEconst  => Tasty.Type.ConstantType(Tasty.Constant.BooleanConst(true))
            case TastyFormat.NULLconst  => Tasty.Type.ConstantType(Tasty.Constant.NullConst)

            // ── Category 2 (tag + Nat) ────────────────────────────────────────────

            case TastyFormat.SHAREDtype =>
                // astRef is a section-relative offset; absRef is the absolute position in sectionBytes.
                val astRef = view.readNat()
                val absRef = if ctx.sectionBytes != null then ctx.sectionOffset + astRef else astRef
                ctx.addrCache.getOrElse(
                    absRef, {
                        if ctx.sectionBytes != null then
                            // Cache miss during body decode: re-decode the type at absRef from full section bytes.
                            val forkView = ByteView(ctx.sectionBytes, absRef, ctx.sectionBytes.length)
                            val forkCtx = DecodeCtx(
                                ctx.names,
                                ctx.addrMap,
                                ctx.arena,
                                ctx.addrCache,
                                ctx.inProgressRec,
                                ctx.binderAddrMap,
                                ctx.sectionBytes,
                                ctx.sectionOffset,
                                ctx.frame
                            )
                            val t = readTypeNode(forkView, forkCtx)
                            ctx.addrCache(absRef) = t
                            t
                        else
                            throw new ArrayIndexOutOfBoundsException(s"SHAREDtype ref $astRef not in addrCache")
                        end if
                    }
                )

            case TastyFormat.TERMREFdirect =>
                val astRef = view.readNat()
                // Use PHASE_B_ADDR_OFFSET + addr as a temporary SymbolId so Phase C can remap via addrMap.
                if ctx.addrMap.contains(astRef) then Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(PHASE_B_ADDR_OFFSET + astRef))
                else Tasty.Type.Named(makeUnresolvedSym(s"termref@$astRef").id)

            case TastyFormat.TYPEREFdirect =>
                val astRef = view.readNat()
                // Use PHASE_B_ADDR_OFFSET + addr as a temporary SymbolId so Phase C can remap via addrMap.
                if ctx.addrMap.contains(astRef) then Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(PHASE_B_ADDR_OFFSET + astRef))
                else Tasty.Type.Named(makeUnresolvedSym(s"typeref@$astRef").id)

            case TastyFormat.TERMREFpkg =>
                val nameRef = view.readNat()
                val fqn     = nameAt(ctx.names, nameRef).asString
                ctx.session match
                    case s: DecodeSession =>
                        Tasty.Type.Named(makeTrackedUnresolvedSym(fqn, s.unresolvedIdToFqn, s.nextUnresolvedId()))
                    case _ =>
                        Tasty.Type.Named(makeUnresolvedSym(fqn).id)
                end match

            case TastyFormat.TYPEREFpkg =>
                val nameRef = view.readNat()
                val fqn     = nameAt(ctx.names, nameRef).asString
                ctx.session match
                    case s: DecodeSession =>
                        Tasty.Type.Named(makeTrackedUnresolvedSym(fqn, s.unresolvedIdToFqn, s.nextUnresolvedId()))
                    case _ =>
                        Tasty.Type.Named(makeUnresolvedSym(fqn).id)
                end match

            case TastyFormat.RECthis =>
                val recAddr = view.readNat()
                // Look for the in-progress Rec node; if not found, use addrCache.
                ctx.inProgressRec.get(recAddr) match
                    case Some(recNode) => Tasty.Type.RecThis(recNode)
                    case None =>
                        ctx.addrCache.get(recAddr) match
                            case Some(recNode) => Tasty.Type.RecThis(recNode)
                            case None          =>
                                // The Rec node hasn't been decoded yet; return a placeholder.
                                Tasty.Type.RecThis(Tasty.Type.Named(makeUnresolvedSym(s"rec@$recAddr").id))
                end match

            // Constant types (category 2: tag + Nat)
            case TastyFormat.BYTEconst =>
                Tasty.Type.ConstantType(Tasty.Constant.ByteConst(view.readNat().toByte))
            case TastyFormat.SHORTconst =>
                Tasty.Type.ConstantType(Tasty.Constant.ShortConst(view.readNat().toShort))
            case TastyFormat.CHARconst =>
                Tasty.Type.ConstantType(Tasty.Constant.CharConst(view.readNat().toChar))
            case TastyFormat.INTconst =>
                Tasty.Type.ConstantType(Tasty.Constant.IntConst(view.readInt()))
            case TastyFormat.LONGconst =>
                Tasty.Type.ConstantType(Tasty.Constant.LongConst(view.readLongNat()))
            case TastyFormat.FLOATconst =>
                Tasty.Type.ConstantType(Tasty.Constant.FloatConst(java.lang.Float.intBitsToFloat(view.readNat())))
            case TastyFormat.DOUBLEconst =>
                Tasty.Type.ConstantType(Tasty.Constant.DoubleConst(java.lang.Double.longBitsToDouble(view.readLongNat())))
            case TastyFormat.STRINGconst =>
                val nameRef = view.readNat()
                Tasty.Type.ConstantType(Tasty.Constant.StringConst(nameAt(ctx.names, nameRef).asString))

            // ── Category 3 (tag + AST) ────────────────────────────────────────────

            case TastyFormat.THIS =>
                val inner = readTypeNode(view, ctx)
                inner match
                    case Tasty.Type.Named(id) => Tasty.Type.ThisType(id)
                    case _                    => Tasty.Type.ThisType(makeUnresolvedSym("this-unknown").id)

            case TastyFormat.CLASSconst =>
                val inner = readTypeNode(view, ctx)
                Tasty.Type.ConstantType(Tasty.Constant.ClassConst(inner))

            case TastyFormat.BYNAMEtype =>
                val underlying = readTypeNode(view, ctx)
                Tasty.Type.ByName(underlying)

            case TastyFormat.RECtype =>
                // Cycle-break: allocate a typed placeholder Rec before decoding parent.
                // inProgressRec is keyed by the RECtype node's start addr so RECthis nodes
                // encountered during parent decoding can find the placeholder.
                val sentinelSym                 = makeUnresolvedSym(s"rec-placeholder@$startAddr")
                val placeholder: Tasty.Type.Rec = Tasty.Type.Rec(Tasty.Type.Named(sentinelSym.id))
                ctx.inProgressRec(startAddr) = placeholder
                val parent = readTypeNode(view, ctx)
                discard(ctx.inProgressRec.remove(startAddr))
                val result = Tasty.Type.Rec(parent)
                // Patch addrCache so RECthis nodes that looked up startAddr find the real Rec.
                ctx.addrCache(startAddr) = result
                result

            // ── Category 4 (tag + Nat + AST) ─────────────────────────────────────

            case TastyFormat.TERMREFsymbol =>
                val astRef = view.readNat()
                val qual   = readTypeNode(view, ctx)
                ctx.addrMap.get(astRef) match
                    case Some(sym) => Tasty.Type.TermRef(qual, sym.name)
                    case None      => Tasty.Type.TermRef(qual, Tasty.Name(s"termrefsym@$astRef"))

            case TastyFormat.TERMREF =>
                val nameRef = view.readNat()
                val qual    = readTypeNode(view, ctx)
                Tasty.Type.TermRef(qual, nameAt(ctx.names, nameRef))

            case TastyFormat.TYPEREFsymbol =>
                val astRef = view.readNat()
                // qual is present but for Named resolution we only need the sym.
                discard(readTypeNode(view, ctx)) // consume qual
                // Use PHASE_B_ADDR_OFFSET + addr so Phase C can remap via addrMap.
                if ctx.addrMap.contains(astRef) then Tasty.Type.Named(kyo.internal.tasty.symbol.SymbolId(PHASE_B_ADDR_OFFSET + astRef))
                else Tasty.Type.Named(makeUnresolvedSym(s"typerefsym@$astRef").id)

            case TastyFormat.TYPEREF =>
                val nameRef = view.readNat()
                val qual    = readTypeNode(view, ctx)
                // For TYPEREF, try to resolve from qual context; return Named if resolvable.
                // For now, build a TermRef-style qualified reference.
                Tasty.Type.TermRef(qual, nameAt(ctx.names, nameRef))

            // ── Category 5 (tag + Length + payload) ──────────────────────────────

            case TastyFormat.APPLIEDtype =>
                val end   = view.readEnd()
                val tycon = readTypeNode(view, ctx)
                val args  = readTypesUntil(view, end, ctx)
                val fqnHint: String | Null = tycon match
                    case Tasty.Type.Named(id) =>
                        import kyo.internal.tasty.symbol.SymbolId.value
                        ctx.session match
                            case s: DecodeSession => s.unresolvedIdToFqn.getOrElse(id.value, null)
                            case _                => null
                    case _ => null
                TypeOps.applied(tycon, Chunk.from(args), fqnHint)

            case TastyFormat.ANNOTATEDtype =>
                val end        = view.readEnd()
                val underlying = readTypeNode(view, ctx)
                // Eagerly decode the annotation term's byte slice into a Tree. The term spans from
                // the current cursor up to `end`. When sectionBytes is unavailable (the rare cached
                // re-decode path), produce args = Maybe.Absent with no error (same silent treatment as
                // the previous Chunk.empty fallback).
                val termStart = view.positionInt
                val endInt    = Math.toIntExact(end)
                skipToEnd(view, end)
                val annotationType = Tasty.Type.Named(makeUnresolvedSym("ann").id)
                val annotation =
                    if ctx.sectionBytes == null then
                        Tasty.Annotation(annotationType, Maybe.Absent)
                    else
                        val pickle = java.util.Arrays.copyOfRange(ctx.sectionBytes, termStart, endInt)
                        val maybeTree: Maybe[Tasty.Tree] =
                            try
                                Maybe(kyo.internal.tasty.reader.TreeUnpickler.decodeAnnotationTerm(
                                    pickle,
                                    ctx.names,
                                    ctx.addrMap,
                                    ctx.sectionBytes,
                                    ctx.sectionOffset
                                ))
                            catch
                                case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                                    val err = TastyError.MalformedSection(
                                        "ASTs",
                                        s"annotation args: ${ex.getMessage}",
                                        ex.byteOffset
                                    )
                                    ctx.session match
                                        case s: DecodeSession => discard(s.annotationDecodeErrors += err)
                                        case null             => ()
                                    end match
                                    Maybe.Absent
                                case ex: ArrayIndexOutOfBoundsException =>
                                    val err = TastyError.MalformedSection("ASTs", "annotation args truncated", 0L)
                                    ctx.session match
                                        case s: DecodeSession => discard(s.annotationDecodeErrors += err)
                                        case null             => ()
                                    end match
                                    Maybe.Absent
                        Tasty.Annotation(annotationType, maybeTree)
                Tasty.Type.Annotated(underlying, annotation)

            case TastyFormat.ANDtype =>
                val end   = view.readEnd()
                val left  = readTypeNode(view, ctx)
                val right = readTypeNode(view, ctx)
                view.goto(end)
                def namedFqn(t: Tasty.Type): String | Null = t match
                    case Tasty.Type.Named(id) =>
                        import kyo.internal.tasty.symbol.SymbolId.value
                        ctx.session match
                            case s: DecodeSession => s.unresolvedIdToFqn.getOrElse(id.value, null)
                            case _                => null
                    case _ => null
                TypeOps.andType(left, right, namedFqn(left), namedFqn(right))

            case TastyFormat.ORtype =>
                val end   = view.readEnd()
                val left  = readTypeNode(view, ctx)
                val right = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.OrType(left, right)

            case TastyFormat.SUPERtype =>
                val end        = view.readEnd()
                val thisType   = readTypeNode(view, ctx)
                val underlying = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.SuperType(thisType, underlying)

            case TastyFormat.REFINEDtype =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val parent  = readTypeNode(view, ctx)
                val info    = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.Refinement(parent, nameAt(ctx.names, nameRef), info)

            case TastyFormat.MATCHtype =>
                val end   = view.readEnd()
                val bound = readTypeNode(view, ctx)
                val scrut = readTypeNode(view, ctx)
                val cases = readTypesUntil(view, end, ctx)
                Tasty.Type.MatchType(bound, scrut, Chunk.from(cases))

            case TastyFormat.MATCHCASEtype =>
                val end = view.readEnd()
                val pat = readTypeNode(view, ctx)
                val rhs = readTypeNode(view, ctx)
                view.goto(end)
                // Represent as Applied(Named(MatchCaseSentinel.id), Chunk(pat, rhs))
                Tasty.Type.Applied(Tasty.Type.Named(MatchCaseSentinel.id), Chunk(pat, rhs))

            case TastyFormat.FLEXIBLEtype =>
                val end        = view.readEnd()
                val underlying = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.FlexibleType(underlying)

            case TastyFormat.TYPELAMBDAtype =>
                val end = view.readEnd()
                // Pre-scan TypesNames (which come AFTER result in the payload), allocate param symbols,
                // register in binderAddrMap (keyed on this node's start addr), then decode result.
                // PARAMtype nodes in the result use binder_ASTRef = startAddr to look up params.
                val paramSyms = readTypeLambdaParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(_.id)), resultType)

            case TastyFormat.POLYtype =>
                val end       = view.readEnd()
                val paramSyms = readTypeLambdaParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(_.id)), resultType)

            case TastyFormat.METHODtype =>
                val end       = view.readEnd()
                val paramSyms = readMethodParams(view, end, ctx)
                ctx.binderAddrMap(startAddr) = Chunk.from(paramSyms)
                val resultType = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.TypeLambda(Chunk.from(paramSyms.map(_.id)), resultType)

            case TastyFormat.PARAMtype =>
                val end        = view.readEnd()
                val binderAddr = view.readNat()
                val paramNum   = view.readNat()
                view.goto(end)
                ctx.binderAddrMap.get(binderAddr) match
                    case Some(params) if paramNum < params.length => Tasty.Type.ParamRef(params(paramNum).id, paramNum)
                    case _                                        => Tasty.Type.Named(makeUnresolvedSym(s"param@$binderAddr:$paramNum").id)

            case TastyFormat.TYPEREFin =>
                val end        = view.readEnd()
                val nameRef    = view.readNat()
                val simpleName = nameAt(ctx.names, nameRef).asString
                val qual       = readTypeNode(view, ctx) // decode qual to extract package FQN
                discard(readTypeNode(view, ctx)) // namespace
                view.goto(end)
                // Reconstruct full FQN: qual_fqn + "." + simpleName.
                // qual is typically TYPEREFpkg(package_fqn) -> Named(trackedId) where trackedId maps to package_fqn.
                // For Phase C lookup, use qual_fqn + "." + simpleName.
                ctx.session match
                    case s: DecodeSession =>
                        val qualFqn = qual match
                            case Tasty.Type.Named(sid) if sid.value < -1 =>
                                // Tracked cross-file ref: look up fqn from unresolvedIdToFqn
                                s.unresolvedIdToFqn.getOrElse(sid.value, simpleName)
                            case _ =>
                                simpleName
                        val fullFqn = if qualFqn != simpleName && qualFqn.nonEmpty then qualFqn + "." + simpleName else simpleName
                        Tasty.Type.Named(makeTrackedUnresolvedSym(fullFqn, s.unresolvedIdToFqn, s.nextUnresolvedId()))
                    case _ =>
                        Tasty.Type.Named(makeUnresolvedSym(simpleName).id)
                end match

            case TastyFormat.TERMREFin =>
                val end     = view.readEnd()
                val nameRef = view.readNat()
                val qual    = readTypeNode(view, ctx)
                discard(readTypeNode(view, ctx)) // namespace
                view.goto(end)
                Tasty.Type.TermRef(qual, nameAt(ctx.names, nameRef))

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
                        Tasty.Type.Wildcard(lo, hi)
                    end if
                end if

            case TastyFormat.REPEATED =>
                val end  = view.readEnd()
                val elem = readTypeNode(view, ctx)
                view.goto(end)
                Tasty.Type.Repeated(elem)

            case other if other >= TastyFormat.firstLengthTreeTag =>
                // Unknown category 5 node: log a warning, skip and return a placeholder.
                // AllowUnsafe is already in scope; evalOrThrow executes the Sync computation synchronously.
                given Frame = ctx.frame
                Sync.Unsafe.evalOrThrow(Log.warn(
                    s"TypeUnpickler: unknown TASTy type tag $other at offset ${view.positionInt}"
                ))
                val end = view.readEnd()
                view.goto(end)
                Tasty.Type.Named(makeUnresolvedSym(s"unknown-type-tag-$other").id)

            case other =>
                // Unknown category 1-4 node: log a warning, skip body and return placeholder.
                // AllowUnsafe is already in scope; evalOrThrow executes the Sync computation synchronously.
                given Frame = ctx.frame
                Sync.Unsafe.evalOrThrow(Log.warn(
                    s"TypeUnpickler: unknown TASTy type tag $other at offset ${view.positionInt}"
                ))
                skipTreeBody(other, view)
                Tasty.Type.Named(makeUnresolvedSym(s"unknown-type-tag-$other").id)

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
        end: Long,
        ctx: DecodeCtx
    )(using AllowUnsafe): mutable.ArrayBuffer[Tasty.Symbol] =
        val resultStart = view.position
        // Skip result type to find TypesNames start.
        skipOneType(view)
        // Collect TypesNames = (typeOrBounds_ASTRef paramName_NameRef)*
        val paramSyms = new mutable.ArrayBuffer[Tasty.Symbol]()
        while view.position < end do
            val typeRef = view.readNat()
            val nameRef = view.readNat()
            val symName = nameAt(ctx.names, nameRef)
            val sym = ctx.addrMap.get(typeRef) match
                case Some(existingSym) => existingSym
                case None =>
                    InternalSymbol.makeSymbol(
                        Tasty.SymbolKind.TypeParam,
                        Tasty.Flags.empty,
                        symName
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
        end: Long,
        ctx: DecodeCtx
    )(using AllowUnsafe): mutable.ArrayBuffer[Tasty.Symbol] =
        val resultStart = view.position
        // Skip result type.
        skipOneType(view)
        // Collect TypesNames until we hit modifiers or end.
        val paramSyms = new mutable.ArrayBuffer[Tasty.Symbol]()
        while view.position < end do
            val tag = view.peekByte(view.position) & 0xff
            if isModifierOrVarianceTag(tag) then
                view.goto(end)
            else
                val typeRef = view.readNat()
                val nameRef = view.readNat()
                val symName = nameAt(ctx.names, nameRef)
                val sym = ctx.addrMap.get(typeRef) match
                    case Some(existingSym) => existingSym
                    case None =>
                        InternalSymbol.makeSymbol(
                            Tasty.SymbolKind.Parameter,
                            Tasty.Flags.empty,
                            symName
                        )
                paramSyms += sym
            end if
        end while
        view.goto(resultStart)
        paramSyms
    end readMethodParams

    /** Read type nodes until position reaches `end`. */
    private def readTypesUntil(view: ByteView, end: Long, ctx: DecodeCtx)(using AllowUnsafe): mutable.ArrayBuffer[Tasty.Type] =
        val buf = new mutable.ArrayBuffer[Tasty.Type]()
        while view.position < end do
            buf += readTypeNode(view, ctx)
        buf
    end readTypesUntil

    /** Skip to the given end position. */
    private def skipToEnd(view: ByteView, end: Long)(using AllowUnsafe): Unit =
        view.goto(end)

    /** Skip exactly one type node from the current position (tag not yet consumed). */
    private def skipOneType(view: ByteView)(using AllowUnsafe): Unit =
        val tag = view.readByte() & 0xff
        skipTreeBody(tag, view)

    /** Skip the body of a tree node whose tag has already been consumed. */
    private def skipTreeBody(tag: Int, view: ByteView)(using AllowUnsafe): Unit =
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

    /** Return the Tasty.Name at `idx` in `names`, throwing ArrayIndexOutOfBoundsException explicitly.
      *
      * ScalaJS fastLinkJS wraps raw array out-of-bounds into UndefinedBehaviorError (a JS class) which is NOT caught by Java
      * `catch case _: ArrayIndexOutOfBoundsException` handlers. Adding an explicit guard here ensures the AIOBE is thrown as a proper Java
      * exception that callers (Symbol.body) can catch and map to TastyError.MalformedSection.
      */
    private def nameAt(names: Array[Tasty.Name], idx: Int): Tasty.Name =
        if idx < 0 || idx >= names.length then throw new ArrayIndexOutOfBoundsException(idx)
        names(idx)

    private def isVarianceTag(tag: Int): Boolean =
        tag == TastyFormat.STABLE || tag == TastyFormat.COVARIANT || tag == TastyFormat.CONTRAVARIANT

    private def isModifierOrVarianceTag(tag: Int): Boolean =
        (tag >= 1 && tag <= 59) ||
            tag == TastyFormat.PRIVATEqualified ||
            tag == TastyFormat.PROTECTEDqualified ||
            tag == TastyFormat.ANNOTATION

end TypeUnpickler
