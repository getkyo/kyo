package kyo

import kyo.STMCommitInterleavingTest.*
import kyo.TRef.State
import kyo.TRef.State.*
import kyo.TRefLog.*
import scala.annotation.tailrec

/** Exhaustive interleaving check of STM execution reads and the commit protocol.
  *
  * A step is one shared-memory access of the real code (a load of a TRef's entry; a load, CAS or store of its packed state; a draw of
  * the global tick), because the protocol's races live between those accesses: a coarser step would hide exactly the windows a lock-free
  * read check or an execution read must close. The checker expands every reachable state once, which covers every interleaving, and at
  * each end state requires a serial order of the committed transactions, respecting real time, that reproduces every value they read
  * and the final memory, with every aborted transaction having observed the state after some prefix of that order (opacity). Written
  * values are unique, so a read identifies the write it came from.
  *
  * Faithfulness: the memory is real TRefs, and a single-access step calls the real member (`validate`, `unlock`, the `AtomicLong`
  * operations behind `getState`/`casState`/`setState`, the `State` operations). A method making several accesses is split into one step
  * per access, and the "split steps match the real methods" leaves run each split alone against the real method on the same state. Two
  * limits: run alone, a re-load always agrees with the load before it, so those leaves cannot see a re-load added or dropped (the split
  * follows the source by reading, and the "not vacuous" leaves show what dropping each one costs); and the entry field is private, so the
  * checker shadows each ref's (tick, value) and asserts through the real `validate` on every load that the ref holds that tick.
  *
  * Not modeled: retries (a retry is a fresh transaction with a fresh tick, so a failed transaction ends aborted), nested `STM.run`, and
  * the probe commit after a user error.
  */
class STMCommitInterleavingTest extends kyo.test.Test[Any]:

    given [A, B]: CanEqual[A, B] = CanEqual.derived

    "split steps match the real methods" - {

        "CommitBuffer.lock, TRef.lock and validateUnlocked" in {
            import AllowUnsafe.embrace.danger
            import CommitBuffer.*
            val txnTick = 50L
            val states  = Vector(0L, raw(lockedState), 60L << 8, 40L << 8)
            val perRef  =
                for
                    write <- Vector(false, true)
                    stale <- Vector(false, true)
                    state <- states
                yield (write, stale, state)
            val mismatches =
                for
                    a     <- perRef
                    b     <- perRef
                    barge <- Vector(false, true)
                    checker = Checker(Scenario("probe", 2, Vector(Vector.empty)), Variant.Source, barge)
                    cells   = Vector(Cell(10, 0, a._3), Cell(11, 0, b._3))
                    entries = Vector((0, a), (1, b)).map { case (r, (write, stale, _)) =>
                        LogE(r, write, cells(r).tick - (if stale then 1 else 0), 0)
                    }
                    (splitOk, splitMem) = checker.soloClaim(cells, txnTick, entries)
                    real                = cells.map(c => fresh(c))
                    realOk              = CommitBuffer.withBuffer { buffer =>
                        entries.foreach(e => buffer.append(real(e.ref).asInstanceOf[TRef[Any]], entryOf(e)))
                        buffer.sort(entries.size)
                        buffer.lock(tick(txnTick), entries.size, barge)
                    }
                    realMem = real.indices.map(r => (real(r).validate(Read(tick(cells(r).tick), 0)), real(r).get())).toVector
                    if splitOk != realOk || realMem != splitMem.map(c => (true, c.state))
                yield s"a=$a b=$b barge=$barge split=($splitOk, $splitMem) real=($realOk, $realMem)"
            assert(mismatches.isEmpty, mismatches.mkString("\n"))
        }

        "TRef.use" in {
            import AllowUnsafe.embrace.danger
            // The real transaction draws its tick from the global counter, so "older" and "newer" are the extremes of the tick range.
            val cases =
                for
                    newer      <- Vector(false, true)
                    locked     <- Vector(false, true)
                    readTickUp <- Vector(false, true)
                yield (newer, locked, readTickUp)
            Kyo.foreach(cases) { case (newer, locked, readTickUp) =>
                val lockBits     = if locked then raw(lockedState) else 0L
                val splitState   = lockBits | ((if readTickUp then 1500L else 0L) << 8)
                val realState    = lockBits | ((if readTickUp then 1L << 54 else 0L) << 8)
                val checker      = Checker(Scenario("probe", 1, Vector(Vector(rd(0)))), Variant.Source, barge = false)
                val (splitOk, c) = checker.soloTxn(Vector(Cell(if newer then 2000 else 5, 0, splitState)), txnTick = 1000)
                val ref          = new TRef[Int](Write(tick(if newer then Long.MaxValue - 1 else 0L), 0))
                ref.set(realState)
                Abort.run(STM.run(Schedule.never)(ref.get)).map { result =>
                    val realRaised  = st(ref.get()).readTick != st(realState).readTick
                    val splitRaised = st(c(0).state).readTick != st(splitState).readTick
                    Maybe.when(result.isSuccess != splitOk || realRaised != splitRaised)(
                        s"newer=$newer locked=$locked readTickUp=$readTickUp split=($splitOk, $splitRaised) real=($result, $realRaised)"
                    )
                }
            }.map(found => assert(found.flatMap(_.toList).isEmpty, found.mkString("\n")))
        }

        "TRef.commit" in {
            import AllowUnsafe.embrace.danger
            val mismatches =
                for
                    readTick <- Vector(0L, 77L)
                    checker = Checker(Scenario("probe", 1, Vector(Vector.empty)), Variant.Source, barge = false)
                    state   = raw(lockedState) | (readTick << 8)
                    split   = checker.soloStore(Vector(Cell(10, 0, state)), LogE(0, true, 10, 42), commitTick = 90)
                    ref     = fresh(Cell(10, 0, state))
                    _       = ref.commit(tick(90), Write(tick(10), 42))
                    real    = (ref.validate(Read(tick(90), 0)), ref.get())
                    if real != (true, split(0).state) || split(0).tick != 90 || split(0).value != 42
                yield s"readTick=$readTick split=$split real=$real"
            assert(mismatches.isEmpty, mismatches.mkString("\n"))
        }

        "a transaction run alone matches STM.run" in {
            import AllowUnsafe.embrace.danger
            Kyo.foreach(scenarios) { s =>
                val checker    = Checker(s, Variant.Source, barge = false)
                val (ths, mem) = checker.serial()
                val refs       = Vector.tabulate(s.refs)(r => new TRef[Int](Write(tick(r + 1L), 0)))
                Kyo.foreach(s.txns.indices)(i => runReal(refs, i, s.txns(i))).map { observed =>
                    Kyo.foreach(refs)(ref => STM.run(ref.get)).map { values =>
                        Maybe.when(observed != ths.map(_.observed) || values != mem.map(_.value) || !ths.forall(_.status == Committed))(
                            s"${s.name}: split=(${ths.map(_.observed)}, ${mem.map(_.value)}) real=($observed, $values)"
                        )
                    }
                }
            }.map(found => assert(found.flatMap(_.toList).isEmpty, found.mkString("\n")))
        }
    }

    "every interleaving is serializable and opaque" - {
        for
            s     <- scenarios
            barge <- Vector(false, true)
        do
            s"${s.name}, barge=$barge" in {
                explore(s, Variant.Source, barge).map { result =>
                    assert(result.violating == 0, result.report)
                    assert(result.outcomes.contains(allCommitted(s)), result.report)
                    assert(result.outcomes.exists(_ != allCommitted(s)), result.report)
                }
            }
    }

    "the checker is not vacuous" - {

        "checking Read entries before locking Write entries lets write skew commit" in {
            explore(writeSkew, Variant.ReadsBeforeWrites, barge = true).map { result =>
                assert(result.violating > 0 && result.counterexample.exists(_.contains("not serializable")), result.report)
            }
        }

        "validateUnlocked without its second tick load lets write skew commit" in {
            explore(writeSkew, Variant.SingleTickLoad, barge = true).map { result =>
                assert(result.violating > 0 && result.counterexample.exists(_.contains("not serializable")), result.report)
            }
        }

        "an execution read without its entry recheck observes a half-applied commit" in {
            explore(readOnlyVsWriter, Variant.UseWithoutRecheck, barge = true).map { result =>
                assert(result.violating > 0 && result.counterexample.exists(_.contains("inconsistent snapshot")), result.report)
            }
        }
    }

end STMCommitInterleavingTest

object STMCommitInterleavingTest:

    given [A, B]: CanEqual[A, B] = CanEqual.derived

    /** The protocol as written in source, and three variants that each drop one ordering or re-load it depends on. */
    enum Variant:
        case Source, ReadsBeforeWrites, SingleTickLoad, UseWithoutRecheck

    final case class Op(ref: Int, write: Boolean)
    def rd(ref: Int): Op = Op(ref, false)
    def wr(ref: Int): Op = Op(ref, true)

    final case class Scenario(name: String, refs: Int, txns: Vector[Vector[Op]])

    val writeSkew        = Scenario("write skew", 2, Vector(Vector(rd(0), wr(1)), Vector(rd(1), wr(0))))
    val readOnlyVsWriter = Scenario("read-only over a, b vs writer of a, b", 2, Vector(Vector(rd(0), rd(1)), Vector(wr(0), wr(1))))

    val scenarios: Vector[Scenario] = Vector(
        writeSkew,
        Scenario("read a write b vs write a", 2, Vector(Vector(rd(0), wr(1)), Vector(wr(0)))),
        readOnlyVsWriter,
        Scenario(
            "read-only over a, b vs read-modify-write of a, b",
            2,
            Vector(Vector(rd(0), rd(1)), Vector(rd(0), wr(0), rd(1), wr(1)))
        ),
        Scenario("lost update on a single ref", 1, Vector(Vector(rd(0), wr(0)), Vector(rd(0), wr(0)))),
        Scenario(
            "read-modify-write of a plus a second ref",
            2,
            Vector(Vector(rd(0), wr(0), rd(1)), Vector(rd(0), wr(0), wr(1)))
        ),
        Scenario("blind write then read", 2, Vector(Vector(wr(0), rd(1)), Vector(wr(1), rd(0)))),
        Scenario("read-only over a, b, c vs writer of a, c", 3, Vector(Vector(rd(0), rd(1), rd(2)), Vector(wr(0), wr(2)))),
        Scenario("read a write b read c vs read c write a", 3, Vector(Vector(rd(0), wr(1), rd(2)), Vector(rd(2), wr(0)))),
        Scenario("cycle over three refs", 3, Vector(Vector(rd(0), wr(1)), Vector(rd(1), wr(2)), Vector(rd(2), wr(0)))),
        Scenario(
            "write skew plus a read-only observer",
            2,
            Vector(Vector(rd(0), wr(1)), Vector(rd(1), wr(0)), Vector(rd(0), rd(1)))
        ),
        Scenario(
            "read-only over a, b, c vs writers of a, b and b, c",
            3,
            Vector(Vector(rd(0), rd(1), rd(2)), Vector(wr(0), wr(1)), Vector(wr(1), wr(2)))
        ),
        Scenario("three read-modify-writes of one ref", 1, Vector.fill(3)(Vector(rd(0), wr(0))))
    )

    def explore(s: Scenario, variant: Variant, barge: Boolean)(using Frame): Explored < Sync =
        Sync.Unsafe.defer(Checker(s, variant, barge).explore())

    def allCommitted(s: Scenario): Vector[Int] = Vector.fill(s.txns.size)(Committed)

    /** Distinct from every initial value (0) and from every other transaction's, so a read identifies its writer. */
    def value(i: Int, ref: Int): Int = (i + 1) * 10 + ref + 1

    inline def tick(l: Long): STM.Tick = l.asInstanceOf[STM.Tick]
    inline def st(l: Long): State      = l.asInstanceOf[State]
    inline def raw(s: State): Long     = s.asInstanceOf[Long]
    val lockedState: State             = State.free.acquireWriterBarging.getOrElse(State.free)

    final case class Cell(tick: Long, value: Int, state: Long)
    final case class LogE(ref: Int, write: Boolean, tick: Long, value: Int)

    def entryOf(e: LogE): Entry[Any] = if e.write then Write(tick(e.tick), e.value) else Read(tick(e.tick), e.value)

    def fresh(c: Cell)(using AllowUnsafe): TRef[Int] =
        val ref = new TRef[Int](Write(tick(c.tick), c.value))
        ref.set(c.state)
        ref
    end fresh

    def runReal(refs: Vector[TRef[Int]], i: Int, ops: Vector[Op])(using Frame): Vector[(Int, Int)] < (Async & Abort[FailedTransaction]) =
        STM.run {
            Kyo.foldLeft(ops)((Vector.empty[(Int, Int)], Set.empty[Int])) { case ((observed, touched), op) =>
                if op.write then refs(op.ref).set(value(i, op.ref)).andThen((observed, touched + op.ref))
                else
                    refs(op.ref).get.map { v =>
                        if touched.contains(op.ref) then (observed, touched)
                        else (observed :+ (op.ref -> v), touched + op.ref)
                    }
            }.map(_._1)
        }

    inline val Running   = 0
    inline val Committed = 1
    inline val Aborted   = 2

    inline val LockWrites = 0
    inline val CheckReads = 1

    /** A transaction's next shared-memory access; `j` indexes the id-sorted commit entries. */
    sealed trait Pc
    object Pc:
        case object Start                                    extends Pc
        case object Done                                     extends Pc
        case class RdEntry(r: Int)                           extends Pc
        case class RdState(r: Int, et: Long, ev: Int)        extends Pc
        case class RdRecheck(r: Int, et: Long, ev: Int)      extends Pc
        case class RtLoad(r: Int, et: Long, ev: Int)         extends Pc
        case class RtCas(r: Int, et: Long, ev: Int, s: Long) extends Pc
        case class WrEntry(r: Int)                           extends Pc
        case class PreVal(k: Int)                            extends Pc
        case object SingleVal                                extends Pc
        case class LkVal(j: Int)                             extends Pc
        case class LkLoad(j: Int)                            extends Pc
        case class LkCas(j: Int, s: Long)                    extends Pc
        case class LkPost(j: Int)                            extends Pc
        case class LkUndo(j: Int)                            extends Pc
        case class ChkVal(j: Int)                            extends Pc
        case class ChkState(j: Int)                          extends Pc
        case class ChkRecheck(j: Int)                        extends Pc
        case object TickNext                                 extends Pc
        case class St1(j: Int)                               extends Pc
        case class St2(j: Int)                               extends Pc
        case class St3(j: Int, s: Long)                      extends Pc
        case class Rel(j: Int, upTo: Int, ok: Boolean)       extends Pc
    end Pc

    /** `seen` is the set of transactions finished when this one started, the real-time order the verdict respects. */
    final case class Th(
        pc: Pc,
        tick: Long,
        opIdx: Int,
        log: Vector[LogE],
        observed: Vector[(Int, Int)],
        sorted: Vector[LogE],
        phase: Int,
        status: Int,
        seen: Int,
        commitTick: Long
    )

    val idle: Th = Th(Pc.Start, 0, 0, Vector.empty, Vector.empty, Vector.empty, 0, Running, 0, 0)

    final case class Global(mem: Vector[Cell], counter: Long, ths: Vector[Th], done: Int)

    final case class Explored(
        scenario: String,
        variant: Variant,
        barge: Boolean,
        states: Int,
        interleavings: BigInt,
        violating: BigInt,
        outcomes: Set[Vector[Int]],
        counterexample: Maybe[String]
    ):
        def report: String =
            s"$scenario [$variant, barge=$barge]: $interleavings interleavings, $states states, $violating violating, " +
                s"outcomes=${outcomes.map(_.map(s => if s == Committed then "C" else "A").mkString).toVector.sorted.mkString(",")}" +
                counterexample.fold("")(c => s"\n$c")
    end Explored

    final class Checker(scenario: Scenario, variant: Variant, barge: Boolean)(using AllowUnsafe):

        private val n    = scenario.txns.size
        private val refs = Vector.tabulate(scenario.refs)(r => new TRef[Any](Write(tick(r + 1L), 0)))
        private val held = Array.tabulate(scenario.refs)(r => Cell(r + 1L, 0, 0L))

        private val phases: Vector[Int] =
            if variant == Variant.ReadsBeforeWrites then Vector(CheckReads, LockWrites) else Vector(LockWrites, CheckReads)

        val initial: Global =
            Global(Vector.tabulate(scenario.refs)(r => Cell(r + 1L, 0, 0L)), scenario.refs.toLong, Vector.fill(n)(idle), 0)

        private def check(cond: Boolean, msg: => String): Unit =
            if !cond then throw new AssertionError(msg)

        private def sync(mem: Vector[Cell]): Unit =
            mem.indices.foreach { r =>
                val c = mem(r)
                if held(r) != c then
                    refs(r).commit(tick(c.tick), Write(tick(c.tick), c.value))
                    storeState(r, c.state)
                    held(r) = c
                end if
            }

        private def storeState(r: Int, s: Long): Unit = (refs(r): java.util.concurrent.atomic.AtomicLong).set(s)

        private def loadEntry(mem: Vector[Cell], r: Int): Cell =
            val c = mem(r)
            check(refs(r).validate(Read(tick(c.tick), c.value)), s"ref $r does not hold tick ${c.tick}")
            c
        end loadEntry

        private def loadState(r: Int): State                 = st(refs(r).get())
        private def validate(e: LogE): Boolean               = refs(e.ref).validate(entryOf(e))
        private def acquire(s: State, t: Long): Maybe[State] = if barge then s.acquireWriterBarging else s.acquireWriter(t)

        private def finish(th: Th, status: Int): Th = th.copy(pc = Pc.Done, status = status)

        @tailrec private def exec(i: Int, th: Th): Th =
            val ops = scenario.txns(i)
            if th.opIdx == ops.size then beginCommit(th)
            else
                val op   = ops(th.opIdx)
                val prev = th.log.indexWhere(_.ref == op.ref)
                if prev < 0 then th.copy(pc = if op.write then Pc.WrEntry(op.ref) else Pc.RdEntry(op.ref))
                else if op.write then
                    exec(i, th.copy(log = put(th.log, LogE(op.ref, true, th.log(prev).tick, value(i, op.ref))), opIdx = th.opIdx + 1))
                else exec(i, th.copy(opIdx = th.opIdx + 1))
                end if
            end if
        end exec

        private def put(log: Vector[LogE], e: LogE): Vector[LogE] =
            val idx = log.indexWhere(_.ref == e.ref)
            if idx < 0 then log :+ e else log.updated(idx, e)

        private def beginCommit(th: Th): Th =
            th.log.size match
                case 0 => finish(th, Committed)
                case 1 => if th.log(0).write then claim(th.copy(sorted = th.log), 0, 0) else th.copy(pc = Pc.SingleVal)
                case _ => th.copy(pc = Pc.PreVal(0))

        @tailrec private def claim(th: Th, phase: Int, from: Int): Th =
            if phase == phases.size then th.copy(pc = Pc.TickNext, phase = phase)
            else
                val checkReads = phases(phase) == CheckReads
                val j          = th.sorted.indexWhere(_.write != checkReads, from)
                if j < 0 then claim(th, phase + 1, 0)
                else th.copy(pc = if checkReads then Pc.ChkVal(j) else Pc.LkVal(j), phase = phase)
            end if
        end claim

        private def advance(th: Th, j: Int): Th = claim(th, th.phase, j + 1)

        /** What `CommitBuffer.lock` releases on failure: the Write entries before the failing one, or all of them when the failure is a
          * Read check after every Write is locked.
          */
        private def fail(th: Th, j: Int): Th =
            val upTo =
                if phases(th.phase) != CheckReads then j
                else if phases.indexOf(LockWrites) < th.phase then th.sorted.size
                else 0
            release(th, 0, upTo, ok = false)
        end fail

        @tailrec private def release(th: Th, from: Int, upTo: Int, ok: Boolean): Th =
            if from == upTo then finish(th, if ok then Committed else Aborted)
            else if th.sorted(from).write then th.copy(pc = Pc.Rel(from, upTo, ok))
            else release(th, from + 1, upTo, ok)

        @tailrec private def stores(th: Th, from: Int): Th =
            if from == th.sorted.size then release(th, 0, th.sorted.size, ok = true)
            else if th.sorted(from).write then th.copy(pc = Pc.St1(from))
            else stores(th, from + 1)

        private def read(i: Int, th: Th, r: Int, et: Long, ev: Int): Th =
            exec(i, th.copy(log = put(th.log, LogE(r, false, et, ev)), observed = th.observed :+ (r -> ev), opIdx = th.opIdx + 1))

        /** Performs transaction `i`'s next shared-memory access on the real refs, then its thread-local work up to the following one. */
        def step(g: Global, i: Int): Global =
            sync(g.mem)
            val th                    = g.ths(i)
            var mem                   = g.mem
            var counter               = g.counter
            def touched(r: Int): Unit =
                val c = mem(r).copy(state = refs(r).get())
                held(r) = c
                mem = mem.updated(r, c)
            end touched
            def ref(j: Int): Int = th.sorted(j).ref
            val next             = th.pc match
                case Pc.Start =>
                    counter += 1
                    exec(i, th.copy(tick = counter, seen = g.done))
                case Pc.RdEntry(r) =>
                    val c = loadEntry(mem, r)
                    if c.tick > th.tick then finish(th, Aborted) else th.copy(pc = Pc.RdState(r, c.tick, c.value))
                case Pc.RdState(r, et, ev) =>
                    if loadState(r).isWriteLocked then finish(th, Aborted)
                    else if variant == Variant.UseWithoutRecheck then th.copy(pc = Pc.RtLoad(r, et, ev))
                    else th.copy(pc = Pc.RdRecheck(r, et, ev))
                case Pc.RdRecheck(r, et, ev) =>
                    if loadEntry(mem, r).tick != et then finish(th, Aborted) else th.copy(pc = Pc.RtLoad(r, et, ev))
                case Pc.RtLoad(r, et, ev) =>
                    val s = loadState(r)
                    if th.tick > s.readTick then th.copy(pc = Pc.RtCas(r, et, ev, raw(s))) else read(i, th, r, et, ev)
                case Pc.RtCas(r, et, ev, s) =>
                    if refs(r).compareAndSet(s, raw(st(s).withReadTick(th.tick))) then
                        touched(r)
                        read(i, th, r, et, ev)
                    else th.copy(pc = Pc.RtLoad(r, et, ev))
                case Pc.WrEntry(r) =>
                    val c = loadEntry(mem, r)
                    if c.tick > th.tick then finish(th, Aborted)
                    else exec(i, th.copy(log = put(th.log, LogE(r, true, c.tick, value(i, r))), opIdx = th.opIdx + 1))
                case Pc.PreVal(k) =>
                    if !validate(th.log(k)) then finish(th, Aborted)
                    else if k + 1 < th.log.size then th.copy(pc = Pc.PreVal(k + 1))
                    else if !th.log.exists(_.write) then finish(th, Committed)
                    else claim(th.copy(sorted = th.log.sortBy(e => refs(e.ref).id)), 0, 0)
                case Pc.SingleVal =>
                    finish(th, if validate(th.log(0)) then Committed else Aborted)
                case Pc.LkVal(j) =>
                    if validate(th.sorted(j)) then th.copy(pc = Pc.LkLoad(j)) else fail(th, j)
                case Pc.LkLoad(j) =>
                    val s = loadState(ref(j))
                    if acquire(s, th.tick).isEmpty then fail(th, j) else th.copy(pc = Pc.LkCas(j, raw(s)))
                case Pc.LkCas(j, s) =>
                    if refs(ref(j)).compareAndSet(s, raw(acquire(st(s), th.tick).getOrElse(st(s)))) then
                        touched(ref(j))
                        th.copy(pc = Pc.LkPost(j))
                    else th.copy(pc = Pc.LkVal(j))
                case Pc.LkPost(j) =>
                    if validate(th.sorted(j)) then advance(th, j) else th.copy(pc = Pc.LkUndo(j))
                case Pc.LkUndo(j) =>
                    refs(ref(j)).unlock(entryOf(th.sorted(j)))
                    touched(ref(j))
                    fail(th, j)
                case Pc.ChkVal(j) =>
                    if validate(th.sorted(j)) then th.copy(pc = Pc.ChkState(j)) else fail(th, j)
                case Pc.ChkState(j) =>
                    if loadState(ref(j)).isWriteLocked then fail(th, j)
                    else if variant == Variant.SingleTickLoad then advance(th, j)
                    else th.copy(pc = Pc.ChkRecheck(j))
                case Pc.ChkRecheck(j) =>
                    if validate(th.sorted(j)) then advance(th, j) else fail(th, j)
                case Pc.TickNext =>
                    counter += 1
                    stores(th.copy(commitTick = counter), 0)
                case Pc.St1(j) =>
                    // The entry store alone: `commit` also reads and writes the state, which St2 and St3 replay as separate accesses.
                    val r     = ref(j)
                    val state = refs(r).get()
                    refs(r).commit(tick(th.commitTick), entryOf(th.sorted(j)))
                    storeState(r, state)
                    val c = Cell(th.commitTick, th.sorted(j).value, state)
                    held(r) = c
                    mem = mem.updated(r, c)
                    discard(loadEntry(mem, r))
                    th.copy(pc = Pc.St2(j))
                case Pc.St2(j) =>
                    th.copy(pc = Pc.St3(j, raw(loadState(ref(j)))))
                case Pc.St3(j, s) =>
                    storeState(ref(j), raw(st(s).withoutReadTick))
                    touched(ref(j))
                    stores(th, j + 1)
                case Pc.Rel(j, upTo, ok) =>
                    refs(ref(j)).unlock(entryOf(th.sorted(j)))
                    touched(ref(j))
                    release(th, j + 1, upTo, ok)
                case Pc.Done =>
                    throw new AssertionError(s"T${i + 1} has no next step")
            val done = if next.pc == Pc.Done then g.done | (1 << i) else g.done
            Global(mem, counter, g.ths.updated(i, next), done)
        end step

        def verdict(g: Global): Maybe[String] =
            val committed = g.ths.indices.filter(g.ths(_).status == Committed)
            val aborted   = g.ths.indices.filter(t => g.ths(t).status == Aborted && g.ths(t).observed.nonEmpty)
            val finalVals = g.mem.map(_.value)
            def finishedBefore(a: Int, b: Int): Boolean                             = (g.ths(b).seen & (1 << a)) != 0
            def matches(observed: Vector[(Int, Int)], values: Vector[Int]): Boolean =
                observed.forall((r, v) => values(r) == v)
            def snapshots(order: IndexedSeq[Int]): IndexedSeq[Vector[Int]] =
                order.scanLeft(Vector.fill(scenario.refs)(0)) { (values, t) =>
                    g.ths(t).log.filter(_.write).foldLeft(values)((acc, e) => acc.updated(e.ref, e.value))
                }
            def serial(order: IndexedSeq[Int]): Boolean =
                val snaps = snapshots(order)
                order.indices.forall(k => order.drop(k + 1).forall(later => !finishedBefore(later, order(k)))) &&
                order.indices.forall(k => matches(g.ths(order(k)).observed, snaps(k))) &&
                snaps.last == finalVals
            end serial
            def opaque(order: IndexedSeq[Int]): Boolean =
                val snaps = snapshots(order)
                aborted.forall { a =>
                    (0 to order.size).exists { k =>
                        matches(g.ths(a).observed, snaps(k)) &&
                        order.take(k).forall(t => !finishedBefore(a, t)) &&
                        order.drop(k).forall(t => !finishedBefore(t, a))
                    }
                }
            end opaque
            val orders = committed.permutations.filter(serial).toVector
            if orders.isEmpty then Present("not serializable")
            else if !orders.exists(opaque) then Present("inconsistent snapshot observed by an aborted transaction")
            else Absent
            end if
        end verdict

        def explore(): Explored =
            val memo                                             = new java.util.HashMap[Global, (BigInt, BigInt)]
            val outcomes                                         = scala.collection.mutable.Set.empty[Vector[Int]]
            var firstBad: Maybe[(List[Int], String)]             = Absent
            def go(g: Global, path: List[Int]): (BigInt, BigInt) =
                val hit = memo.get(g)
                if hit != null then hit
                else
                    check(path.size < 1000, "step graph has a cycle")
                    val enabled = (0 until n).filter(g.ths(_).pc != Pc.Done)
                    val res     =
                        if enabled.isEmpty then
                            outcomes += g.ths.map(_.status)
                            verdict(g) match
                                case Present(why) =>
                                    if firstBad.isEmpty then firstBad = Present((path.reverse, why))
                                    (BigInt(1), BigInt(1))
                                case Absent => (BigInt(1), BigInt(0))
                            end match
                        else
                            enabled.foldLeft((BigInt(0), BigInt(0))) { (acc, i) =>
                                val (all, bad) = go(step(g, i), i :: path)
                                (acc._1 + all, acc._2 + bad)
                            }
                    memo.put(g, res)
                    res
                end if
            end go
            val (all, bad) = go(initial, Nil)
            Explored(
                scenario.name,
                variant,
                barge,
                memo.size,
                all,
                bad,
                outcomes.toSet,
                firstBad.map((path, why) => s"$why\n${replay(path)}")
            )
        end explore

        private def replay(path: List[Int]): String =
            val names                    = "abcdefgh"
            def cells(g: Global): String =
                g.mem.zipWithIndex.map { (c, r) =>
                    val s = st(c.state)
                    s"${names(r)}=(v${c.value}@t${c.tick},${if s.isWriteLocked then "W" else "free"},rt${s.readTick})"
                }.mkString(" ")
            def describe(th: Th): String =
                def at(j: Int) = names(th.sorted(j).ref)
                th.pc match
                    case Pc.Start              => "start: draw tick"
                    case Pc.RdEntry(r)         => s"read ${names(r)}: load entry"
                    case Pc.RdState(r, _, _)   => s"read ${names(r)}: load state"
                    case Pc.RdRecheck(r, _, _) => s"read ${names(r)}: reload entry"
                    case Pc.RtLoad(r, _, _)    => s"read ${names(r)}: load state for readTick"
                    case Pc.RtCas(r, _, _, _)  => s"read ${names(r)}: CAS readTick"
                    case Pc.WrEntry(r)         => s"write ${names(r)}: load entry"
                    case Pc.PreVal(k)          => s"commit: pre-validate ${names(th.log(k).ref)}"
                    case Pc.SingleVal          => s"commit: validate ${names(th.log(0).ref)}"
                    case Pc.LkVal(j)           => s"commit: lock ${at(j)}: validate"
                    case Pc.LkLoad(j)          => s"commit: lock ${at(j)}: load state"
                    case Pc.LkCas(j, _)        => s"commit: lock ${at(j)}: CAS"
                    case Pc.LkPost(j)          => s"commit: lock ${at(j)}: validate after CAS"
                    case Pc.LkUndo(j)          => s"commit: lock ${at(j)}: roll back"
                    case Pc.ChkVal(j)          => s"commit: validateUnlocked ${at(j)}: first tick load"
                    case Pc.ChkState(j)        => s"commit: validateUnlocked ${at(j)}: state load"
                    case Pc.ChkRecheck(j)      => s"commit: validateUnlocked ${at(j)}: second tick load"
                    case Pc.TickNext           => "commit: draw commit tick"
                    case Pc.St1(j)             => s"commit: store ${at(j)} entry"
                    case Pc.St2(j)             => s"commit: store ${at(j)}: load state"
                    case Pc.St3(j, _)          => s"commit: store ${at(j)}: clear readTick"
                    case Pc.Rel(j, _, _)       => s"unlock ${at(j)}"
                    case Pc.Done               => "done"
                end match
            end describe
            val header = scenario.txns.zipWithIndex.map { (ops, i) =>
                s"T${i + 1}: " + ops.map(op => s"${if op.write then "write" else "read"} ${names(op.ref)}").mkString(", ")
            }
            val (lines, last) = path.foldLeft((Vector.empty[String], initial)) { case ((acc, g), i) =>
                val next   = step(g, i)
                val th     = next.ths(i)
                val status = if th.pc == Pc.Done then (if th.status == Committed then " => COMMITTED" else " => ABORTED") else ""
                (acc :+ f"  T${i + 1} ${describe(g.ths(i))}%-48s | ${cells(next)}$status", next)
            }
            val observed = last.ths.zipWithIndex.map { (th, i) =>
                s"T${i + 1} observed " + th.observed.map((r, v) => s"${names(r)}=v$v").mkString(", ")
            }
            (header ++ lines ++ observed).mkString("\n")
        end replay

        def serial(): (Vector[Th], Vector[Cell]) =
            val last = (0 until n).foldLeft(initial)((g, i) => runAlone(g, i, _ == Pc.Done))
            (last.ths, last.mem)

        @tailrec private def runAlone(g: Global, i: Int, stop: Pc => Boolean): Global =
            if stop(g.ths(i).pc) || g.ths(i).pc == Pc.Done then g else runAlone(step(g, i), i, stop)

        /** Whether `CommitBuffer.lock`'s split, run alone, locked everything, and the memory it left. */
        def soloClaim(mem: Vector[Cell], txnTick: Long, entries: Vector[LogE]): (Boolean, Vector[Cell]) =
            val th = claim(idle.copy(tick = txnTick, log = entries, sorted = entries.sortBy(e => refs(e.ref).id)), 0, 0)
            val g  = runAlone(Global(mem, 100, Vector(th), 0), 0, pc => pc == Pc.TickNext)
            (g.ths(0).pc == Pc.TickNext, g.mem)
        end soloClaim

        def soloTxn(mem: Vector[Cell], txnTick: Long): (Boolean, Vector[Cell]) =
            val g = runAlone(Global(mem, 100, Vector(exec(0, idle.copy(tick = txnTick))), 0), 0, _ => false)
            (g.ths(0).status == Committed, g.mem)

        /** The memory after the commit's store of one locked Write entry, before its unlock. */
        def soloStore(mem: Vector[Cell], entry: LogE, commitTick: Long): Vector[Cell] =
            val th = idle.copy(pc = Pc.St1(0), sorted = Vector(entry), commitTick = commitTick)
            runAlone(Global(mem, 100, Vector(th), 0), 0, pc => pc.isInstanceOf[Pc.Rel]).mem
    end Checker

end STMCommitInterleavingTest
