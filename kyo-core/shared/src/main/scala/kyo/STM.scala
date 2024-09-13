package kyo

import STM.internal.*
import java.util.concurrent.atomic as j
import kyo.kernel.*

sealed trait STM extends ArrowEffect[Op, Id]

export STM.Ref

object STM:

    opaque type Ref[A] = j.AtomicReference[State[A]]

    object Ref:
        inline given [A]: Flat[Ref[A]] = Flat.unsafe.bypass

    extension [A](inline self: Ref[A])

        inline def get(using inline frame: Frame): A < STM =
            ArrowEffect.suspend(Tag[STM], Op.Get(self))

        inline def set(value: A)(using inline frame: Frame): Unit < STM =
            ArrowEffect.suspend(Tag[STM], Op.Set(self, value))

    end extension

    import internal.*

    inline def initRef[A](inline value: A)(using inline frame: Frame): Ref[A] < STM =
        ArrowEffect.suspend(Tag[STM], Op.Init(value))

    // inline def initRefNow[A](inline value: A)(using inline frame: Frame): Ref[A] < IO =
    //     IO(new j.AtomicReference(State(nextTid.incrementAndGet(), value)))

    inline def retry: Nothing < STM =
        ArrowEffect.suspend(Tag[STM], Op.Retry)

    def runCommit[A: Flat, S](v: A < (STM & S))(using Frame): A < (Async & S) =
        IO {
            val tid = nextTid.incrementAndGet()
            ArrowEffect.handle.state(Tag[STM], Log(Map.empty, Map.empty), v.map(Maybe(_)))(
                handle = [C] =>
                    (input, log, cont) =>
                        IO {
                            input match
                                case Op.Get(ref) =>
                                    val refState = ref.get()
                                    val refAny   = ref.asInstanceOf[Ref[Any]]
                                    val value    = log.writes.getOrElse(refAny, refState.value).asInstanceOf[C]
                                    val newLog   = Log(log.reads + (refAny -> refState.tid), log.writes)
                                    (newLog, cont(value))
                                case Op.Set(ref, value) =>
                                    val refState = ref.get()
                                    val refAny   = ref.asInstanceOf[Ref[Any]]
                                    val newLog   = Log(log.reads + (refAny -> refState.tid), log.writes + (refAny -> value))
                                    (newLog, cont(()))
                                case Op.Init(value) =>
                                    val ref    = new j.AtomicReference(State(tid, value))
                                    val refAny = ref.asInstanceOf[Ref[Any]]
                                    val newLog = Log(log.reads + (refAny -> tid), log.writes + (refAny -> value))
                                    (newLog, cont(ref))
                                case Op.Retry =>
                                    (log, Maybe.empty[A]: Maybe[A] < STM)
                    },
                done = (log, result) =>
                    result match
                        case Maybe.Empty =>
                            runCommit(v)
                        case Maybe.Defined(value) =>
                            globalMutex.run {
                                IO {
                                    if !log.reads.forall((ref, tid) => ref.get().tid == tid) then
                                        runCommit(v)
                                    else
                                        log.writes.foreach((ref, value) => ref.set(State(tid, value)))
                                        value
                                }
                            }
            )
        }
    end runCommit

    object internal:

        case class Log(reads: Map[Ref[Any], Long], writes: Map[Ref[Any], Any])

        val nextTid = new j.AtomicLong

        val globalMutex =
            given Frame = Frame.internal
            IO.run(Meter.initMutex).eval

        case class State[A](tid: Long, value: A)

        enum Op[A] derives CanEqual:
            case Init[A](value: A)             extends Op[Ref[A]]
            case Get[A](ref: Ref[A])           extends Op[A]
            case Set[A](ref: Ref[A], value: A) extends Op[Unit]
            case Retry                         extends Op[Nothing]
        end Op
    end internal
end STM
