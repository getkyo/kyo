package kyo.test

import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kyo.*
import scala.collection.immutable.SortedSet

/** TestClock provides deterministic testing of time-based effects with Kyo. Instead of waiting for real time to pass, it allows simulation
  * of clock adjustments and scheduled effects.
  */
type TestClock = Clock with Restorable

object TestClock:
    def sleeps(using Trace): List[Instant] < IO
    def timeZone(using Trace): ZoneId < IO

    final case class Test(
        clockState: AtomicRef[TestClock.Data],
        live: Live,
        annotations: Annotations,
        warningState: Var[TestClock.WarningData],
        suspendedWarningState: Var[TestClock.SuspendedWarningData]
    ) extends TestClock with TestClockPlatformSpecific:

        // Create a semaphore as a freeze lock (using unsafe creation for brevity)
        private val freezeLock = Semaphore.unsafe.make(1)(Unsafe)

        def adjust(duration: Duration)(using trace: Trace): Unit < IO =
            warningDone *> run(_.plus(duration))

        def adjustWith[R, E, A](duration: Duration)(effect: A < (Env[R] & Abort[E]))(using trace: Trace): A < (Env[R] & Abort[E]) =
            effect <& adjust(duration)

        def currentDateTime(using trace: Trace): OffsetDateTime < IO =
            warningStart *> unsafe.currentDateTime()

        def currentTime(unit: => TimeUnit)(using trace: Trace): Long < IO =
            warningStart *> unsafe.currentTime(unit)

        def currentTime(unit: => ChronoUnit)(using trace: Trace): Long < IO =
            warningStart *> unsafe.currentTime(unit)

        def nanoTime(using trace: Trace): Long < IO =
            warningStart *> unsafe.nanoTime()

        def instant(using trace: Trace): Instant < IO =
            warningStart *> unsafe.instant()

        def javaClock(using trace: Trace): java.time.Clock < IO =
            // Define a simple JavaClock backed by the TestClock state
            case class JavaClock(clockState: AtomicRef[TestClock.Data], zoneId: ZoneId) extends java.time.Clock:
                def getZone(): ZoneId                             = zoneId
                def instant(): Instant                            = clockState.getUnsafe.instant
                override def withZone(newZone: ZoneId): JavaClock = copy(zoneId = newZone)
            end JavaClock
            clockState.get.map(data => JavaClock(clockState, data.timeZone))
        end javaClock

        def localDateTime(using trace: Trace): LocalDateTime < IO =
            warningStart *> unsafe.localDateTime()

        def save(using trace: Trace): Unit < IO =
            clockState.get.map(data => clockState.set(data))

        def setTime(instant: Instant)(using trace: Trace): Unit < IO =
            warningDone *> run(_ => instant)

        def setTimeZone(zone: ZoneId)(using trace: Trace): Unit < IO =
            clockState.update(_.copy(timeZone = zone))

        def sleep(duration: => Duration)(using trace: Trace): Unit < IO =
            for
                promise <- Promise.make[Nothing, Unit]
                shouldAwait <- clockState.modify { data =>
                    val end = data.instant.plus(duration)
                    if end.isAfter(data.instant) then
                        (true, data.copy(sleeps = (end, promise) :: data.sleeps))
                    else
                        (false, data)
                    end if
                    end
                }
                _ <- if shouldAwait then warningStart *> promise.await else promise.succeed(())
            yield ()

        def sleeps(using trace: Trace): List[Instant] < IO =
            clockState.get.map(_.sleeps.map(_._1))

        def timeZone(using trace: Trace): ZoneId < IO =
            clockState.get.map(_.timeZone)

        override val unsafe: UnsafeAPI =
            new UnsafeAPI:
                def currentTime(unit: TimeUnit)(using unsafe: Unsafe): Long =
                    unit.convert(clockState.getUnsafe.instant.toEpochMilli, TimeUnit.MILLISECONDS)
                def currentTime(unit: ChronoUnit)(using unsafe: Unsafe): Long =
                    unit.between(Instant.EPOCH, clockState.getUnsafe.instant)
                def currentDateTime()(using unsafe: Unsafe): OffsetDateTime =
                    OffsetDateTime.ofInstant(clockState.getUnsafe.instant, clockState.getUnsafe.timeZone)
                def instant()(using unsafe: Unsafe): Instant =
                    clockState.getUnsafe.instant
                def localDateTime()(using unsafe: Unsafe): LocalDateTime =
                    LocalDateTime.ofInstant(clockState.getUnsafe.instant, clockState.getUnsafe.timeZone)
                def nanoTime()(using unsafe: Unsafe): Long =
                    currentTime(ChronoUnit.NANOS)

        private def suspendedWarningDone(using trace: Trace): Unit < IO =
            suspendedWarningState.updateSomeKyo { case SuspendedWarningData.Pending(fiber) =>
                fiber.interrupt.as(SuspendedWarningData.start)
            }

        private def warningDone(using trace: Trace): Unit < IO =
            warningState.updateSomeKyo {
                case WarningData.Start          => succeed(WarningData.done)
                case WarningData.Pending(fiber) => fiber.interrupt.as(WarningData.done)
            }

        private def awaitSuspended(using trace: Trace): Unit < IO =
            suspendedWarningStart *>
                suspended.zipWith(live.provide(sleep(10.milliseconds)) *> suspended)(_ == _)
                    .filterOrFail(identity)(())
                    .eventually *>
                suspendedWarningDone

        private def delay(using trace: Trace): Unit < IO =
            live.provide(sleep(5.milliseconds))

        private def freeze(using trace: Trace): Map[FiberId, Fiber.Status] < (IO & Abort[Unit]) =
            freezeLock.withPermit {
                supervisedFibers.flatMap { fibers =>
                    fibers.foldLeft(succeed(Map.empty[FiberId, Fiber.Status])) { (acc, fiber) =>
                        for
                            map    <- acc
                            status <- fiber.status
                        yield map.updated(fiber.id, status)
                    }
                }
            }
    end Test
end TestClock
