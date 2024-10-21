package kyo

import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport
import kyo.scheduler.IOPromise
import scala.annotation.tailrec
import scala.collection.mutable.PriorityQueue

object Timer2:

    import internal.*

    private val timeShift = Local.init(1d)

    def withTimeShift[A, S](shift: Double)(v: A < S)(using Frame): A < S =
        timeShift.let(Math.max(0.001, shift))(v)

    def scheduleWithFixedDelay[E, S](schedule: Schedule)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        scheduleWithFixedDelay(schedule, ())(_ => f)

    def scheduleWithFixedDelay[E, A: Flat, S](schedule: Schedule, state: A)(f: A => A < (Async & Abort[E]))(using Frame): Fiber[E, A] < IO =
        Async.run {
            Clock.use { clock =>
                timeShift.use { shift =>
                    IO.Unsafe {
                        Loop(state, schedule) { (state, schedule) =>
                            schedule.next match
                                case Absent => Loop.done(state)
                                case Present((duration, nextSchedule)) =>
                                    Dispatcher(clock.unsafe.now() + (duration * (1.toDouble / shift))).safe
                                        .use(_ => f(state).map(Loop.continue(_, nextSchedule)))
                        }
                    }
                }
            }
        }

    def scheduleAtFixedRate[E, S](period: Schedule)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        scheduleAtFixedRate(period, ())(_ => f)

    def scheduleAtFixedRate[E, A: Flat, S](period: Schedule, state: A)(f: A => A < (Async & Abort[E]))(using Frame): Fiber[E, A] < IO =
        Async.run {
            Clock.use { clock =>
                timeShift.use { shift =>
                    IO.Unsafe {
                        Loop(clock.unsafe.now(), state, period) { (lastExecution, state, period) =>
                            period.next match
                                case Absent => Loop.done(state)
                                case Present((duration, nextSchedule)) =>
                                    val nextExecution = lastExecution + (duration * (1.toDouble / shift))
                                    Dispatcher(nextExecution).safe
                                        .use(_ => f(state).map(Loop.continue(nextExecution, _, nextSchedule)))
                        }
                    }
                }
            }
        }

    private object internal:

        final private class Task(val instant: Instant) extends IOPromise[Nothing, Unit]

        object Dispatcher:
            import AllowUnsafe.embrace.danger
            val resolution    = 1.millis
            private val inbox = AtomicRef.Unsafe.init(List.empty[Task])
            private val queue = new PriorityQueue[Task](using Ordering.by(_.instant.toJava.toEpochMilli()))
            Executors.newSingleThreadExecutor().execute(() =>
                while true do
                    tick()
                    LockSupport.parkNanos(resolution.toNanos)
            )

            def apply(instant: Instant): Promise.Unsafe[Nothing, Unit] =
                val task = Task(instant)
                def loop(): Unit =
                    val i = inbox.get()
                    if !inbox.cas(i, task :: i) then loop()
                loop()
                Promise.Unsafe.fromIOPromise(task)
            end apply

            @tailrec private def tick(): Unit =
                queue.addAll(inbox.getAndSet(Nil))
                if !queue.isEmpty then
                    val task = queue.head
                    if task.instant.isBefore(Clock.live.unsafe.now()) then
                        discard(queue.dequeue())
                        task.completeDiscard(Result.unit)
                        tick()
                    end if
                end if
            end tick
        end Dispatcher

    end internal
end Timer2
