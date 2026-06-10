package kyo.test.internal

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Duration
import kyo.Maybe
import kyo.Scope
import kyo.kernel.<
import kyo.test.AssertScope
import kyo.test.TestBuilder
import kyo.test.TestResult
import scala.collection.mutable.ListBuffer

/** Cursor-based registration context for the V3 (next) self-contained base.
  *
  * Each instance targets a single DFS cursor (`Chunk[Int]`). The class-body scan walks the tree, descends into groups whose prefix matches
  * the target, and records terminal leaf results (ignored / pending / skipped) when the cursor lands on the target. The baseline-row body
  * thunk of a normal leaf is buffered for the runner to discharge; this context does NOT execute leaf bodies (the single terminal
  * `Fiber#toFuture` and per-leaf discharge are runner work). Group bodies are walked so children register.
  *
  * @param target
  *   DFS cursor identifying the node to register against.
  * @param discovery
  *   when true, leaf bodies are not buffered for execution; a synthetic marker records the position so the runner counts it.
  */
final class TestContext private[test] (val target: Chunk[Int], private val discovery: Boolean = false):

    // ── Cursor state (single-threaded: class body runs on one thread) ────────────────────────

    private val pathStack: AtomicReference[Chunk[Int]]                            = new AtomicReference(Chunk.empty)
    private val nextChildIndex: AtomicInteger                                     = new AtomicInteger(0)
    private val nameStack: ListBuffer[String]                                     = ListBuffer.empty
    private val producedLeaf: AtomicReference[Maybe[(Chunk[String], TestResult)]] = new AtomicReference(Maybe.empty)

    // True when the target cursor lands on a GROUP node (its body contains nested `-` calls), so the
    // runner's discovery walk knows to descend into the group's children rather than treating the
    // cursor as past-end. Set in `visitGroupImpl` / `EnrichedTestBuilder` group dispatch when
    // `mine == target`. The runner's `peekWasGroup` reads it during discovery.
    private val wasGroup: java.util.concurrent.atomic.AtomicBoolean = new java.util.concurrent.atomic.AtomicBoolean(false)

    // Buffered baseline-row leaf body thunks awaiting runner discharge. Keyed by the leaf's full name path.
    // The body's residual `S` row is discharged by the suite's `.handle` chain before reaching here; for a raw
    // `Test[Any]` suite the body is already baseline-shaped (S = Any unions to the baseline). The thunk returns the baseline
    // computation `Unit < (Async & Abort[Any] & Scope)`: the runner that owns the single terminal `Fiber#toFuture`
    // discharges it under its Scope/Fiber/Abort pipeline. Both registration paths (raw `-` at S = Any, and the
    // `EnrichedTestBuilder` terminal which calls `visitLeafWithBuilder[Async & Abort[Any] & Scope]`) supply a
    // baseline-shaped body. The thunk is stored as `() => Unit < (Async & Abort[Any] & Scope)` with the phantom-S
    // coercion performed at insertion (see `visitLeafImpl`), keeping `takeRegisteredBody` cast-free.
    private val pendingBodies: ListBuffer[(Chunk[String], AssertScope => Unit < (Async & Abort[Any] & Scope))] = ListBuffer.empty

    // ── Builder metadata ─────────────────────────────────────────────────────────────────────────

    private val builderByPath: java.util.concurrent.ConcurrentHashMap[Chunk[String], TestBuilder] =
        new java.util.concurrent.ConcurrentHashMap[Chunk[String], TestBuilder]()

    private[test] def builderFor(path: Chunk[String]): Maybe[TestBuilder] =
        Maybe(builderByPath.get(path))

    // ── Entry points: visitLeaf and visitGroup ────────────────────────────────────────────────

    /** Register a leaf `-` node (body contains no nested `-` calls).
      *
      * Walks the cursor; when the cursor matches the target, buffers the baseline-row body thunk for the runner to discharge. The
      * body is NEVER executed here.
      */
    def visitLeaf[S](
        name: String,
        body: AssertScope ?=> Unit < (S & Async & Abort[Any] & Scope)
    ): Unit =
        visitLeafImpl(name, Maybe.empty, as => body(using as))

    /** Register a leaf `-` node with TestBuilder metadata. */
    def visitLeafWithBuilder[S](
        name: String,
        builder: TestBuilder,
        body: AssertScope ?=> Unit < (S & Async & Abort[Any] & Scope)
    ): Unit =
        visitLeafImpl(name, Maybe(builder), as => body(using as))

    /** Register a group `-` node (body contains nested `-` calls). Group bodies always run so children register. */
    def visitGroup[S](
        name: String,
        body: => Unit < (S & Async & Abort[Any] & Scope)
    ): Unit =
        visitGroupImpl(name, Maybe.empty, () => body)

    /** Register a group `-` node with TestBuilder metadata. */
    def visitGroupWithBuilder[S](
        name: String,
        builder: TestBuilder,
        body: => Unit < (S & Async & Abort[Any] & Scope)
    ): Unit =
        visitGroupImpl(name, Maybe(builder), () => body)

    private def visitLeafImpl(
        name: String,
        builderOpt: Maybe[TestBuilder],
        body: AssertScope => Any
    ): Unit =
        if producedLeaf.get().isDefined then return
        val myIndex = nextChildIndex.getAndIncrement()
        val mine    = pathStack.get().append(myIndex)
        nameStack += name
        builderOpt match
            case Maybe.Present(builder) => builderByPath.put(Chunk.from(nameStack), builder): Unit
            case _                      => ()
        try
            if mine == target then
                if discovery then
                    producedLeaf.set(Maybe.Present((Chunk.from(nameStack), TestResult.Passed(Duration.Zero))))
                else
                    // Unsafe: S is phantom in <[+A, -S]; issue #903 forbids statically widening
                    // `Unit < (S & baseline)` to `Unit < baseline` at the call site, but the runtime
                    // value is always the baseline computation (discharged by .handle or S = Any).
                    val baselineBody: AssertScope => Unit < (Async & Abort[Any] & Scope) =
                        as => body(as).asInstanceOf[Unit < (Async & Abort[Any] & Scope)]
                    // The runner discharges the buffered baseline-row body via a single terminal Fiber#toFuture.
                    pendingBodies += ((Chunk.from(nameStack), baselineBody))
                end if
            else () // SKIP: diverged path or leaf with no children to descend into
        finally
            if nameStack.nonEmpty then nameStack.remove(nameStack.size - 1): Unit
        end try
    end visitLeafImpl

    private def visitGroupImpl(
        name: String,
        builderOpt: Maybe[TestBuilder],
        body: () => Any
    ): Unit =
        if producedLeaf.get().isDefined then return
        val myIndex = nextChildIndex.getAndIncrement()
        val mine    = pathStack.get().append(myIndex)
        nameStack += name
        builderOpt match
            case Maybe.Present(builder) => builderByPath.put(Chunk.from(nameStack), builder): Unit
            case _                      => ()
        try
            if mine == target || target.startsWith(mine) then
                if mine == target then wasGroup.set(true)
                descend(myIndex, body)
            else () // SKIP: diverged path
        finally
            if nameStack.nonEmpty then nameStack.remove(nameStack.size - 1): Unit
        end try
    end visitGroupImpl

    // ── Terminal leaf registrations (no body) ────────────────────────────────────────────────

    /** Record this cursor as Ignored without running a body, carrying the optional reason. */
    def registerIgnored(name: String, reason: String): Unit =
        if producedLeaf.get().isDefined then return
        val myIndex = nextChildIndex.getAndIncrement()
        val mine    = pathStack.get().append(myIndex)
        if mine == target then
            producedLeaf.set(Maybe.Present((Chunk.from(nameStack) :+ name, TestResult.Ignored(reason))))
    end registerIgnored

    /** Record this cursor as Skipped. */
    def registerSkipped(name: String, reason: String): Unit =
        if producedLeaf.get().isDefined then return
        val myIndex = nextChildIndex.getAndIncrement()
        val mine    = pathStack.get().append(myIndex)
        if mine == target then
            producedLeaf.set(Maybe.Present((Chunk.from(nameStack) :+ name, TestResult.Skipped(reason))))
    end registerSkipped

    // ── Accessors ─────────────────────────────────────────────────────────────────────────────

    /** Signal that the constructor has finished and no more registrations are coming. The runner wires completion. */
    private[test] def signalPastEnd(): Unit = ()

    /** Test accessor: the leaf result produced at the target cursor, if any. */
    private[test] def peekRegisteredLeaf: Maybe[(Chunk[String], TestResult)] = producedLeaf.get()

    /** Runner accessor: true when the target cursor lands on a GROUP node. Discovery uses this to decide whether to descend into the
      * cursor's children (group) or treat a `producedLeaf`-absent cursor as past-end.
      */
    private[test] def peekWasGroup: Boolean = wasGroup.get()

    /** Runner accessor: the deferred baseline-row leaf body buffered at the target cursor, or a unit no-op when none was registered
      * (past-end / group / terminal marker). The runner discharges this under its `Scope` / `Fiber.initUnscoped` / `Abort.run` pipeline.
      * `private[test]` so the sibling `kyo.test.runner` package reaches it.
      */
    private[test] def takeRegisteredBody(as: AssertScope): Unit < (Async & Abort[Any] & Scope) =
        pendingBodies.headOption match
            case Some((_, thunk)) => thunk(as)
            case None             => ()

    /** Test accessor: the number of baseline-row leaf bodies buffered for runner discharge. */
    private[test] def pendingBodyCount: Int = pendingBodies.size

    /** Test accessor: the full name path of the baseline-row leaf body buffered at the target cursor, or `Absent` when none was registered
      * (past-end / group / terminal marker). Pairs with [[takeRegisteredBody]] so a non-runner driver (the api self-tests, which cannot
      * depend on `kyo-test-runner`) can label the discharged leaf with its path.
      */
    private[test] def peekRegisteredPath: Maybe[Chunk[String]] =
        pendingBodies.headOption match
            case Some((path, _)) => Maybe(path)
            case None            => Maybe.empty

    /** Test accessor for pathStack. */
    private[test] def _pathStack: Chunk[Int] = pathStack.get()

    // ── Private helpers ───────────────────────────────────────────────────────────────────────

    /** Descend into a group node: push its index onto pathStack and reset nextChildIndex for children. */
    private def descend(myIndex: Int, body: () => Any): Unit =
        val savedNext = nextChildIndex.get()
        pathStack.set(pathStack.get().append(myIndex))
        nextChildIndex.set(0)
        // Walk the group body so nested `-` registrations fire. Constructing the Kyo computation executes the suite-author's
        // registration side effects (the nested `-` calls) without discharging effects.
        val _ = body()
        pathStack.set(pathStack.get().dropRight(1))
        nextChildIndex.set(savedNext)
    end descend

end TestContext

object TestContext:
    private val tl: ThreadLocal[TestContext | Null] = new ThreadLocal[TestContext | Null]()

    def setForInstantiation(ctx: TestContext): Unit = tl.set(ctx)

    def takeFromThreadLocal(): TestContext =
        val ctx = tl.get()
        if ctx == null then
            throw new IllegalStateException(
                "kyo.test.Test must be instantiated by the kyo-test runner"
            )
        end if
        tl.set(null)
        ctx
    end takeFromThreadLocal
end TestContext
