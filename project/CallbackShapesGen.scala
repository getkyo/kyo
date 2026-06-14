import sbt.*
import sbt.Keys.*

/** Single source of truth for the Scala Native retained-callback shape catalog.
  *
  * Adding a new supported callback signature = ONE append to [[CallbackShapesGen.SHAPES]] here, plus an optional mirror match arm in
  * `kyo-ffi/codegen/src/main/scala/kyo/ffi/codegen/emitters/NativeCallbackCatalog.scala` (the codegen module resolves `TypeRef` patterns;
  * this build-side source only emits the runtime trampolines + registry glue on the Native side).
  *
  * Emits at build time (under `sourceManaged / main / kyo/ffi/internal`):
  *
  *   - `CallbackRegistryShapes.scala`, trait holding `retainedSlots_SHAPE` arrays, free-lists, claim/release, transient push/pop/peek,
  *     observability counters, top-level transient trampolines, `releaseRetainedSlotByName`, `poolStats`, and the shared helpers
  *     (`onClaim`/`onRelease`/`mustPeek`/`exhausted`/`newFreeList`/`newTransientStack`). [[kyo.ffi.internal.CallbackRegistry]] extends it.
  *   - `RetainedTrampolines_<SHAPE>.scala` (one per shape), per-slot top-level `def`s plus `ptrsR: Array[CFuncPtr*]` initialisers.
  *     Split per shape to avoid JVM class-size limits at large pool sizes.
  *
  * Pool size is [[CallbackShapesGen.PoolSize]], defaulting to 1024 slots per shape. It is configurable at build time via the
  * system property `-Dkyo.ffi.native.poolSize=N` (e.g. `sbt -Dkyo.ffi.native.poolSize=512 compile`). The runtime property
  * `-Dkyo.ffi.native.retainedCallbackPoolSize=` can further override the runtime default reported by `poolStats`, but the number of
  * per-slot `def`s emitted in the `RetainedTrampolines_*.scala` files is fixed at build time and controlled solely by the build-time property.
  * Any guard can claim any free slot from the global pool; there is no per-guard partitioning.
  */
object CallbackShapesGen {

    sealed trait CType {
        def scala: String // Scala Native unsafe type
        def user: String  // Scala user-facing type
        def tag: String
    }
    object CType {
        case object V extends CType { val scala = "Unit"; val user = "Unit"; val tag = "U"           }
        case object I extends CType { val scala = "CInt"; val user = "Int"; val tag = "I"            }
        case object J extends CType { val scala = "CLongLong"; val user = "Long"; val tag = "J"      }
        case object D extends CType { val scala = "CDouble"; val user = "Double"; val tag = "D"      }
        case object P extends CType { val scala = "Ptr[Byte]"; val user = "Ptr[Byte]"; val tag = "P" }
    }

    case class CallbackShape(params: List[CType], result: CType) {
        import CType.*
        def name: String = {
            val paramTag = if (params.isEmpty) "V" else params.map(_.tag).mkString
            val retTag   = if (result == V) "U" else result.tag
            s"${paramTag}_$retTag"
        }
        def arity: Int = params.length
        def userFnType: String =
            if (params.isEmpty) s"() => ${result.user}" else s"(${params.map(_.user).mkString(", ")}) => ${result.user}"
        def cFuncPtrType: String      = s"CFuncPtr$arity[${(params.map(_.scala) :+ result.scala).mkString(", ")}]"
        def cFuncPtrCompanion: String = s"CFuncPtr$arity"
        def paramListDecl: String     = params.zipWithIndex.map { case (t, i) => s"p$i: ${t.scala}" }.mkString(", ")
        def argList: String           = params.indices.map(i => s"p$i").mkString(", ")
    }

    val SHAPES: List[CallbackShape] = {
        import CType.*
        List(
            CallbackShape(Nil, V),           // V_U  : () => Unit
            CallbackShape(I :: Nil, V),      // I_U  : Int => Unit
            CallbackShape(I :: Nil, I),      // I_I  : Int => Int
            CallbackShape(I :: I :: Nil, I), // II_I : (Int, Int) => Int
            CallbackShape(J :: J :: Nil, I), // JJ_I : (Long, Long) => Int
            CallbackShape(P :: Nil, V),      // P_U  : Ptr[Byte] => Unit
            CallbackShape(P :: I :: Nil, V), // PI_U : (Ptr[Byte], Int) => Unit
            CallbackShape(J :: Nil, J),      // J_J  : Long => Long
            CallbackShape(J :: Nil, V),      // J_U  : Long => Unit
            CallbackShape(D :: Nil, D),      // D_D  : Double => Double
            CallbackShape(I :: I :: Nil, V), // II_U : (Int, Int) => Unit
            CallbackShape(J :: J :: Nil, J)  // JJ_J : (Long, Long) => Long
        )
    }

    /** Build-time pool size per retained shape. Configurable via `-Dkyo.ffi.native.poolSize=N`; defaults to 1024.
      * Any guard can claim any free slot from the global pool.
      */
    val PoolSize: Int =
        try sys.props.getOrElse("kyo.ffi.native.poolSize", "1024").toInt
        catch { case _: Throwable => 1024 }

    /** Task body for `Compile / sourceGenerators`. Writes both generated sources under `outDir / kyo / ffi / internal /`. */
    def generate(outDir: File): Seq[File] = {
        val pkgDir = outDir / "kyo" / "ffi" / "internal"
        IO.createDirectory(pkgDir)
        val registryFile = pkgDir / "CallbackRegistryShapes.scala"
        IO.write(registryFile, emitRegistryShapes(SHAPES))
        val trampolineFiles = emitRetainedTrampolines(pkgDir, SHAPES, PoolSize)
        Seq(registryFile) ++ trampolineFiles
    }

    // ---------- CallbackRegistryShapes.scala ----------

    def emitRegistryShapes(shapes: List[CallbackShape]): String = {
        val sb        = new StringBuilder
        val shapeList = shapes.map(_.name).mkString(", ")

        sb.append("// AUTO-GENERATED by project/CallbackShapesGen.scala from SHAPES.\n")
        sb.append("// DO NOT EDIT BY HAND, edit project/CallbackShapesGen.scala and rebuild.\n")
        sb.append("package kyo.ffi.internal\n\n")
        sb.append("import java.util.concurrent.atomic.AtomicBoolean\n")
        sb.append("import java.util.concurrent.atomic.AtomicInteger\n")
        sb.append("import java.util.concurrent.atomic.AtomicLongArray\n")
        sb.append("import java.util.concurrent.locks.LockSupport\n")
        sb.append("import scala.scalanative.unsafe.*\n\n")
        sb.append("/** Per-shape state + methods for the Scala Native callback registry. Mixed into [[CallbackRegistry]]. */\n")
        sb.append("private[ffi] trait CallbackRegistryShapes:\n\n")

        // PoolSize + WarnThresholdPercent live on the trait so the generated per-shape vals can reference them during init without needing
        // a self-type pointing back at CallbackRegistry. Runtime values come from sys-props, read once when the mixing object initializes.
        sb.append("    /** Pool size per retained shape. Configurable at build time via `-Dkyo.ffi.native.poolSize=N`; defaults to 1024.\n")
        sb.append("      * Any guard can claim any free slot from the global pool; there is no per-guard partitioning.\n")
        sb.append(
            "      * The runtime property `-Dkyo.ffi.native.retainedCallbackPoolSize=` can also override this value, but only slots\n"
        )
        sb.append("      * emitted at build time are available, raise `-Dkyo.ffi.native.poolSize=N` and recompile to add more. */\n")
        sb.append("    val PoolSize: Int =\n")
        sb.append("        try sys.props.getOrElse(\"kyo.ffi.native.retainedCallbackPoolSize\", \"" + PoolSize + "\").toInt\n")
        sb.append("        catch case _: Throwable => " + PoolSize + "\n\n")
        sb.append(
            "    /** One-shot high-watermark percentage. Configurable via `-Dkyo.ffi.native.retainedCallbackPoolWarnPercent=`; defaults to 75. */\n"
        )
        sb.append("    val WarnThresholdPercent: Int =\n")
        sb.append("        try sys.props.getOrElse(\"kyo.ffi.native.retainedCallbackPoolWarnPercent\", \"75\").toInt\n")
        sb.append("        catch case _: Throwable => 75\n\n")
        sb.append("    /** Opt-in: when exhausted, block waiting for a slot rather than throwing immediately.\n")
        sb.append("      * Read on every claim (not cached) so tests can flip the flag without reloading the class. The sysprop lookup\n")
        sb.append("      * is in the `exhausted` slow path only, the bitset fast path is unaffected. */\n")
        sb.append("    def backpressureEnabled: Boolean =\n")
        sb.append("        try sys.props.getOrElse(\"kyo.ffi.native.retainedCallbackPoolBackpressure\", \"false\") == \"true\"\n")
        sb.append("        catch case _: Throwable => false\n\n")
        sb.append("    /** Timeout for the backpressure wait in milliseconds. Default 5000. */\n")
        sb.append("    def backpressureTimeoutMs: Long =\n")
        sb.append("        try sys.props.getOrElse(\"kyo.ffi.native.retainedCallbackPoolBackpressureTimeoutMs\", \"5000\").toLong\n")
        sb.append("        catch case _: Throwable => 5000L\n\n")
        sb.append(
            "    /** Number of 64-bit words in the per-shape free-slot bitset. One bit per slot, bit=0 means free, bit=1 means claimed. */\n"
        )
        sb.append("    val BitsetWords: Int = (PoolSize + 63) / 64\n\n")

        // Observability counters
        sb.append("    // Observability (per-shape claim-count + one-shot high-watermark warning flag).\n")
        shapes.foreach { s =>
            sb.append(s"    private[internal] val used_${s.name}: AtomicInteger    = new AtomicInteger(0)\n")
        }
        sb.append("\n")
        shapes.foreach { s =>
            sb.append(s"    private[internal] val warned_${s.name}: AtomicBoolean = new AtomicBoolean(false)\n")
        }
        sb.append("\n")

        // Shared helpers
        sb.append("    private def onClaim(shape: String, used: AtomicInteger, warned: AtomicBoolean): Unit =\n")
        sb.append("        val newUsed = used.incrementAndGet()\n")
        sb.append("        if PoolSize > 0 then\n")
        sb.append("            val pct = newUsed * 100 / PoolSize\n")
        sb.append("            if pct >= WarnThresholdPercent && !warned.getAndSet(true) then\n")
        sb.append("                java.lang.System.err.println(FfiErrors.poolHighWatermark(shape, newUsed, PoolSize, pct))\n")
        sb.append("        end if\n")
        sb.append("    end onClaim\n\n")
        sb.append("    private def onRelease(used: AtomicInteger, warned: AtomicBoolean): Unit =\n")
        sb.append("        val newUsed = used.decrementAndGet()\n")
        sb.append("        if PoolSize > 0 then\n")
        sb.append("            val pct = newUsed * 100 / PoolSize\n")
        sb.append("            if pct < WarnThresholdPercent then warned.set(false)\n")
        sb.append("    end onRelease\n\n")
        sb.append("    private def exhausted(shape: String): Nothing =\n")
        sb.append("        throw new IllegalStateException(\n")
        sb.append("            s\"Native callback pool exhausted for shape '$shape' (size=$PoolSize). \" +\n")
        sb.append("                \"Close unused Ffi.Guard instances, raise -Dkyo.ffi.native.retainedCallbackPoolSize=, \" +\n")
        sb.append("                \"or reduce the number of concurrently-retained callbacks of this shape.\"\n")
        sb.append("        )\n\n")
        sb.append("    /** Per-shape park/unpark coordination for backpressure.\n")
        sb.append("      *\n")
        sb.append(
            "      * `waiters` counts threads currently parked in `waitForSlot`. `release` only bothers to iterate the waiter list when\n"
        )
        sb.append("      * this count is nonzero. The `waitList` is a bounded-size array accessed under the object's monitor, simple and\n")
        sb.append("      * low-contention because (a) the bitset fast path avoids the wait queue entirely when slots are available, and\n")
        sb.append("      * (b) the release side only grabs it when `waiters > 0`. */\n")
        sb.append("    final class WaitQueue:\n")
        sb.append("        val waiters: AtomicInteger              = new AtomicInteger(0)\n")
        sb.append("        private val waitList: java.util.concurrent.ConcurrentLinkedQueue[Thread] =\n")
        sb.append("            new java.util.concurrent.ConcurrentLinkedQueue[Thread]()\n")
        sb.append("        def enqueue(t: Thread): Unit = { val _ = waitList.add(t) }\n")
        sb.append("        def remove(t: Thread): Unit  = { val _ = waitList.remove(t) }\n")
        sb.append("        def unparkOne(): Unit =\n")
        sb.append("            val t = waitList.peek()\n")
        sb.append("            if t != null then LockSupport.unpark(t)\n")
        sb.append("        def unparkAll(): Unit =\n")
        sb.append("            val it = waitList.iterator().nn\n")
        sb.append("            while it.hasNext do LockSupport.unpark(it.next())\n")
        sb.append("    end WaitQueue\n\n")
        sb.append("    /** Construct a fresh per-shape free-slot bitset. All bits start 0 (free). */\n")
        sb.append("    private def newBitset(): AtomicLongArray =\n")
        sb.append("        new AtomicLongArray(BitsetWords)\n\n")
        sb.append(
            "    /** Try once to claim a free slot via CAS on the per-shape bitset. Returns the slot index or -1 if all slots are full. */\n"
        )
        sb.append("    private def tryClaimBit(bits: AtomicLongArray): Int =\n")
        sb.append("        var wi = 0\n")
        sb.append("        while wi < BitsetWords do\n")
        sb.append("            val w = bits.get(wi)\n")
        sb.append("            if w != -1L then\n")
        sb.append("                // Inverse: bit==0 means free. Find lowest zero bit within this word.\n")
        sb.append("                val free = (~w) & -(~w) // isolate lowest set bit of ~w\n")
        sb.append("                if free != 0L then\n")
        sb.append("                    val bit    = java.lang.Long.numberOfTrailingZeros(free)\n")
        sb.append("                    val global = wi * 64 + bit\n")
        sb.append("                    if global < PoolSize then\n")
        sb.append("                        val next = w | free\n")
        sb.append("                        if bits.compareAndSet(wi, w, next) then return global\n")
        sb.append("                    end if\n")
        sb.append("                end if\n")
        sb.append("                // CAS miss or out-of-range bit: retry same word without advancing wi.\n")
        sb.append("            else\n")
        sb.append("                wi += 1\n")
        sb.append("            end if\n")
        sb.append("        end while\n")
        sb.append("        -1\n")
        sb.append("    end tryClaimBit\n\n")
        sb.append("    /** Clear the bit for `slot` in `bits`. Lock-free. */\n")
        sb.append("    private def clearBit(bits: AtomicLongArray, slot: Int): Unit =\n")
        sb.append("        val wi   = slot >>> 6\n")
        sb.append("        val mask = 1L << (slot & 63)\n")
        sb.append("        var w    = bits.get(wi)\n")
        sb.append("        while !bits.compareAndSet(wi, w, w & ~mask) do w = bits.get(wi)\n")
        sb.append("    end clearBit\n\n")
        sb.append("    /** Backpressure wait loop used by `claimOrBlock`. Returns the claimed slot or throws `exhausted(shape)` if the\n")
        sb.append("      * timeout elapses. */\n")
        sb.append("    private def waitForSlot(shape: String, bits: AtomicLongArray, q: WaitQueue): Int =\n")
        sb.append("        val deadline = System.nanoTime() + backpressureTimeoutMs * 1000000L\n")
        sb.append("        val t        = Thread.currentThread().nn\n")
        sb.append("        val _        = q.waiters.incrementAndGet()\n")
        sb.append("        q.enqueue(t)\n")
        sb.append("        try\n")
        sb.append("            while true do\n")
        sb.append("                val slot = tryClaimBit(bits)\n")
        sb.append("                if slot >= 0 then return slot\n")
        sb.append("                val remaining = deadline - System.nanoTime()\n")
        sb.append("                if remaining <= 0L then\n")
        sb.append("                    java.lang.System.err.println(FfiErrors.poolBackpressureTimeout(shape, backpressureTimeoutMs))\n")
        sb.append("                    exhausted(shape)\n")
        sb.append("                end if\n")
        sb.append("                LockSupport.parkNanos(math.min(remaining, 1000000L))\n")
        sb.append("            end while\n")
        sb.append("            -1 // unreachable\n")
        sb.append("        finally\n")
        sb.append("            q.remove(t)\n")
        sb.append("            val _ = q.waiters.decrementAndGet()\n")
        sb.append("        end try\n")
        sb.append("    end waitForSlot\n\n")
        sb.append("    /** Full claim path: CAS-fast on the bitset, fall back to backpressure wait if enabled, else throw. */\n")
        sb.append("    private def claimOrBlock(shape: String, bits: AtomicLongArray, q: WaitQueue): Int =\n")
        sb.append("        val first = tryClaimBit(bits)\n")
        sb.append("        if first >= 0 then first\n")
        sb.append("        else if backpressureEnabled then waitForSlot(shape, bits, q)\n")
        sb.append("        else exhausted(shape)\n")
        sb.append("    end claimOrBlock\n\n")
        sb.append("    private def newTransientStack(): ThreadLocal[java.util.ArrayDeque[AnyRef]] =\n")
        sb.append("        new ThreadLocal[java.util.ArrayDeque[AnyRef]]:\n")
        sb.append("            override def initialValue(): java.util.ArrayDeque[AnyRef] =\n")
        sb.append("                new java.util.ArrayDeque[AnyRef]()\n\n")
        sb.append("    private def mustPeek(stack: ThreadLocal[java.util.ArrayDeque[AnyRef]], shape: String): AnyRef =\n")
        sb.append("        val v = stack.get().nn.peek()\n")
        sb.append("        if v == null then\n")
        sb.append("            throw new IllegalStateException(\n")
        sb.append(
            "                s\"CallbackRegistry.peekTransient_$shape: empty stack, a native trampoline fired with no active transient callback of this shape. \" +\n"
        )
        sb.append(
            "                    \"Either the generated code failed to push before the FFI call, or the C side invoked the callback after the transient window closed.\"\n"
        )
        sb.append("            )\n")
        sb.append("        end if\n")
        sb.append("        v\n")
        sb.append("    end mustPeek\n\n")

        // releaseRetainedSlotByName
        sb.append("    /** Dispatch `releaseRetainedSlot_<shape>(slot)` via the shape's string name. */\n")
        sb.append("    def releaseRetainedSlotByName(shape: String, slot: Int): Unit =\n")
        sb.append("        shape match\n")
        shapes.foreach { s =>
            sb.append(s"""            case "${s.name}" => releaseRetainedSlot_${s.name}(slot)\n""")
        }
        sb.append("            case other =>\n")
        sb.append("                throw new IllegalArgumentException(\n")
        sb.append(s"""                    s"CallbackRegistry.releaseRetainedSlotByName: unknown shape '$$other'. " +\n""")
        sb.append(s"""                        "Expected one of: ${shapeList}."\n""")
        sb.append("                )\n")
        sb.append("    end releaseRetainedSlotByName\n\n")

        // poolStats
        sb.append("    /** Read-only snapshot of a shape's retained-pool utilization. */\n")
        sb.append("    def poolStats(shape: String): PoolStats =\n")
        sb.append("        val used = shape match\n")
        shapes.foreach { s =>
            sb.append(s"""            case "${s.name}" => used_${s.name}.get()\n""")
        }
        sb.append("            case other =>\n")
        sb.append("                throw new IllegalArgumentException(\n")
        sb.append(s"""                    s"CallbackRegistry.poolStats: unknown shape '$$other'. " +\n""")
        sb.append(s"""                        "Expected one of: ${shapeList}."\n""")
        sb.append("                )\n")
        sb.append("        val pct = if PoolSize == 0 then 0.0 else used * 100.0 / PoolSize\n")
        sb.append("        PoolStats(used, PoolSize, pct)\n")
        sb.append("    end poolStats\n\n")

        // poolUsage, simple (used, total) accessor for operators
        sb.append(
            "    /** Simplified pool-usage accessor returning `(used, total)` for the given shape. Throws `IllegalArgumentException`\n"
        )
        sb.append("      * if `shapeId` is not a known shape name. Operators can use this for lightweight runtime monitoring without\n")
        sb.append("      * constructing a full [[PoolStats]] snapshot. */\n")
        sb.append("    def poolUsage(shapeId: String): (Int, Int) =\n")
        sb.append("        val st = poolStats(shapeId)\n")
        sb.append("        (st.used, st.total)\n")
        sb.append("    end poolUsage\n\n")

        // Typed-zero fallbacks used when a user callback throws, the generated trampolines below
        // MUST NOT propagate exceptions into C.
        sb.append("    // Typed-zero defaults for callback-exception path.\n")
        sb.append("    // Returned to C when a user callback throws; the throwable is reported via `FfiErrors.reportCallbackFailed`\n")
        sb.append("    // before the zero default is returned, see README 'Callback exception handling'.\n\n")

        // Transient per-shape
        sb.append("    // Transient: per-shape ThreadLocal LIFO stack + push/pop/peek + top-level trampoline def.\n")
        sb.append("    // The stack stores a `TaggedCallback` pair so the trampoline can name the binding + method when the user\n")
        sb.append("    // callback throws.\n")
        shapes.foreach { s =>
            val n = s.name
            val applyCall =
                if (s.params.isEmpty) s"tagged.fn.asInstanceOf[${s.userFnType}].apply()"
                else s"tagged.fn.asInstanceOf[${s.userFnType}].apply(${s.argList})"
            val zeroExpr = s.result match {
                case CType.V => "()"
                case CType.I => "0"
                case CType.J => "0L"
                case CType.D => "0.0"
                case CType.P => "null"
            }
            sb.append(s"    private[internal] val transientStack_$n: ThreadLocal[java.util.ArrayDeque[AnyRef]] = newTransientStack()\n")
            sb.append(s"    def pushTransient_$n(bindingFqn: String, methodName: String, f: ${s.userFnType}): Unit =\n")
            sb.append(
                s"""        transientStack_$n.get().nn.push(new TaggedCallback(bindingFqn, methodName, "transient", f.asInstanceOf[AnyRef]))"""
            )
            sb.append("\n")
            sb.append(s"    def popTransient_$n(): Unit =\n")
            sb.append(s"        val _ = transientStack_$n.get().nn.pop()\n")
            sb.append(
                "    private def peekTransient_" + n + "(): TaggedCallback = mustPeek(transientStack_" + n + ", \"" + n + "\").asInstanceOf[TaggedCallback]\n"
            )
            sb.append(s"    def trampolineT_$n(${s.paramListDecl}): ${s.result.scala} =\n")
            sb.append(s"        val tagged = peekTransient_$n()\n")
            sb.append(s"        try $applyCall\n")
            sb.append(s"        catch\n")
            sb.append(s"            case t: Throwable =>\n")
            sb.append(s"                FfiGenErrors.reportCallbackFailed(tagged.bindingFqn, tagged.methodName, tagged.kind, t)\n")
            sb.append(s"                $zeroExpr\n")
            sb.append(s"    end trampolineT_$n\n\n")
        }

        // Retained per-shape, CAS-bitset free-slot tracking + opt-in backpressure.
        // Any guard can claim any free slot from the global pool; there is no per-guard partitioning.
        sb.append("    // Retained: fixed-size pool per shape. Free-slot tracking is an `AtomicLongArray` bitset scanned via CAS.\n")
        sb.append("    // Pool exhaustion either throws `IllegalStateException` or parks on a per-shape waiter queue when\n")
        sb.append("    // `backpressureEnabled` is true.\n")
        sb.append("    // Any guard can claim any free slot from the global pool; there is no per-guard partitioning.\n")
        shapes.foreach { s =>
            val n = s.name
            sb.append(s"    private[internal] val retainedSlots_$n: Array[AnyRef]   = new Array[AnyRef](PoolSize)\n")
            sb.append(s"    private[internal] val retainedBits_$n: AtomicLongArray  = newBitset()\n")
            sb.append(s"    private[internal] val retainedWait_$n: WaitQueue       = new WaitQueue()\n\n")
            sb.append(
                s"    def claimRetainedSlot_$n(guardToken: AnyRef, bindingFqn: String, methodName: String, fn: ${s.userFnType}): (Int, ${s.cFuncPtrType}) =\n"
            )
            sb.append(s"""        val idx  = claimOrBlock("$n", retainedBits_$n, retainedWait_$n)\n""")
            sb.append(
                s"""        retainedSlots_$n(idx) = new TaggedCallback(bindingFqn, methodName, "retained", fn.asInstanceOf[AnyRef])"""
            )
            sb.append("\n")
            sb.append(s"""        onClaim("$n", used_$n, warned_$n)\n""")
            sb.append(s"        (idx, RetainedTrampolines_$n.ptrsR(idx))\n")
            sb.append(s"    end claimRetainedSlot_$n\n\n")
            sb.append(s"    def releaseRetainedSlot_$n(idx: Int): Unit =\n")
            sb.append(s"        retainedSlots_$n(idx) = null\n")
            sb.append(s"        clearBit(retainedBits_$n, idx)\n")
            sb.append(s"        onRelease(used_$n, warned_$n)\n")
            sb.append(s"        if retainedWait_$n.waiters.get() > 0 then retainedWait_$n.unparkOne()\n")
            sb.append(s"    end releaseRetainedSlot_$n\n\n")
        }

        // bindSlotToGuard, dispatch via shape name to set the GuardCore back-reference on the TaggedCallback at `slot`.
        sb.append("    /** Patch the `guardCore` back-reference onto the retained slot's [[TaggedCallback]] so the retained\n")
        sb.append("      * trampoline can gate slot access on the guard's state machine. Silently no-ops if the slot is\n")
        sb.append("      * unexpectedly empty. */\n")
        sb.append("    def bindSlotToGuard(shape: String, slot: Int, core: GuardCore): Unit =\n")
        sb.append("        val arr: Array[AnyRef] = shape match\n")
        shapes.foreach { s =>
            sb.append(s"""            case "${s.name}" => retainedSlots_${s.name}\n""")
        }
        sb.append("            case other =>\n")
        sb.append("                throw new IllegalArgumentException(\n")
        sb.append(s"""                    s"CallbackRegistry.bindSlotToGuard: unknown shape '$$other'. " +\n""")
        sb.append(s"""                        "Expected one of: ${shapeList}."\n""")
        sb.append("                )\n")
        sb.append("        val tc = arr(slot)\n")
        sb.append("        if tc != null then tc.asInstanceOf[TaggedCallback].guardCore = core\n")
        sb.append("    end bindSlotToGuard\n\n")

        // Kept as a no-op for binary compatibility, NativeGuard.close() calls this; with no sub-pools, there is nothing to release.
        sb.append("    /** No-op. Retained for API compatibility with callers (e.g. `NativeGuard.close()`). With global-pool allocation\n")
        sb.append(
            "      * there is no per-guard state to release, individual slots are freed by `releaseRetainedSlot_*` before this is called. */\n"
        )
        sb.append("    def unregisterGuard(guardToken: AnyRef): Unit = ()\n\n")

        sb.append("end CallbackRegistryShapes\n")
        sb.toString
    }

    // ---------- RetainedTrampolines_<SHAPE>.scala (one per shape) ----------

    /** Emit one file per shape to avoid JVM class-size limits when PoolSize is large. */
    def emitRetainedTrampolines(pkgDir: File, shapes: List[CallbackShape], poolSize: Int): Seq[File] = {
        shapes.map { s =>
            val file = pkgDir / s"RetainedTrampolines_${s.name}.scala"
            IO.write(file, emitRetainedTrampolinesForShape(s, poolSize))
            file
        }
    }

    private def emitRetainedTrampolinesForShape(s: CallbackShape, poolSize: Int): String = {
        val sb = new StringBuilder
        val zeroExpr = s.result match {
            case CType.V => "()"
            case CType.I => "0"
            case CType.J => "0L"
            case CType.D => "0.0"
            case CType.P => "null"
        }
        sb.append("// AUTO-GENERATED by project/CallbackShapesGen.scala from SHAPES + PoolSize.\n")
        sb.append("// DO NOT EDIT BY HAND, edit project/CallbackShapesGen.scala and rebuild.\n")
        sb.append("package kyo.ffi.internal\n\n")
        sb.append("import scala.scalanative.unsafe.*\n\n")
        sb.append(s"private[ffi] object RetainedTrampolines_${s.name}:\n\n")
        sb.append(s"    private val N = CallbackRegistry.PoolSize\n\n")

        (0 until poolSize).foreach { i =>
            sb.append(s"    def trampR_$i(${s.paramListDecl}): ${s.result.scala} =\n")
            sb.append(s"        val tagged = CallbackRegistry.retainedSlots_${s.name}($i).asInstanceOf[TaggedCallback]\n")
            sb.append(s"        if tagged == null then return $zeroExpr\n")
            sb.append(s"        val core = tagged.guardCore\n")
            sb.append(s"        if core != null && !core.beginCallback() then\n")
            sb.append(
                s"            java.lang.System.err.println(FfiErrors.callbackInvokedAfterClose(tagged.bindingFqn, tagged.methodName))\n"
            )
            sb.append(s"            return $zeroExpr\n")
            sb.append(s"        try tagged.fn.asInstanceOf[${s.userFnType}].apply(${s.argList})\n")
            sb.append(s"        catch\n")
            sb.append(s"            case t: Throwable =>\n")
            sb.append(s"                FfiGenErrors.reportCallbackFailed(tagged.bindingFqn, tagged.methodName, tagged.kind, t)\n")
            sb.append(s"                $zeroExpr\n")
            sb.append(s"        finally\n")
            sb.append(s"            if core != null then core.endCallback()\n")
            sb.append(s"    end trampR_$i\n")
        }
        sb.append("\n")

        // Split array initialization into chunks to avoid JVM 64KB method size limit.
        val chunkSize = 128
        val chunks    = (0 until poolSize).grouped(chunkSize).zipWithIndex.toList
        chunks.foreach { case (range, chunkIdx) =>
            sb.append(s"    private def initPtrs_$chunkIdx(arr: Array[${s.cFuncPtrType}]): Unit =\n")
            range.foreach { i =>
                sb.append(s"        arr($i) = ${s.cFuncPtrCompanion}.fromScalaFunction(trampR_$i)\n")
            }
            sb.append(s"    end initPtrs_$chunkIdx\n")
        }
        sb.append(s"    val ptrsR: Array[${s.cFuncPtrType}] =\n")
        sb.append(s"        val arr = new Array[${s.cFuncPtrType}](N)\n")
        chunks.foreach { case (_, chunkIdx) =>
            sb.append(s"        initPtrs_$chunkIdx(arr)\n")
        }
        sb.append("        arr\n")
        sb.append(s"    end ptrsR\n")

        sb.append(s"\nend RetainedTrampolines_${s.name}\n")
        sb.toString
    }
}
