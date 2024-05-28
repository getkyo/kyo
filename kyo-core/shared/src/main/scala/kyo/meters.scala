package kyo

import kyo.internal.Trace

abstract class Meter:
    self =>

    def available(using Trace): Int < IOs

    def isAvailable(using Trace): Boolean < IOs = available.map(_ > 0)

    def run[T, S](v: => T < S)(using Trace): T < (S & Fibers)

    def tryRun[T, S](v: => T < S)(using Trace): Option[T] < (IOs & S)
end Meter

object Meters:

    def initNoop: Meter =
        new Meter:
            def available(using Trace)                 = Int.MaxValue
            def run[T, S](v: => T < S)(using Trace)    = v
            def tryRun[T, S](v: => T < S)(using Trace) = v.map(Some(_))

    def initMutex(using Trace): Meter < IOs =
        initSemaphore(1)

    def initSemaphore(concurrency: Int)(using Trace): Meter < IOs =
        Channels.init[Unit](concurrency).map { chan =>
            offer(concurrency, chan, ()).map { _ =>
                new Meter:
                    def available(using Trace) = chan.size
                    def release(using Trace)   = chan.offerUnit(())

                    def run[T, S](v: => T < S)(using Trace) =
                        IOs.ensure(release) {
                            chan.take.andThen(v)
                        }

                    def tryRun[T, S](v: => T < S)(using Trace) =
                        IOs {
                            IOs.run(chan.poll) match
                                case None =>
                                    None
                                case _ =>
                                    IOs.ensure(release) {
                                        v.map(Some(_))
                                    }
                        }
            }
        }

    def initRateLimiter(rate: Int, period: Duration)(using Trace): Meter < IOs =
        Channels.init[Unit](rate).map { chan =>
            Timers.scheduleAtFixedRate(period)(offer(rate, chan, ())).map { _ =>
                new Meter:

                    def available(using Trace)              = chan.size
                    def run[T, S](v: => T < S)(using Trace) = chan.take.map(_ => v)

                    def tryRun[T, S](v: => T < S)(using Trace) =
                        chan.poll.map {
                            case None =>
                                None
                            case _ =>
                                v.map(Some(_))
                        }
            }
        }

    def pipeline[S1, S2](m1: Meter < S1, m2: Meter < S2)(using Trace): Meter < (IOs & S1 & S2) =
        pipeline[S1 & S2](List(m1, m2))

    def pipeline[S1, S2, S3](
        m1: Meter < S1,
        m2: Meter < S2,
        m3: Meter < S3
    )(using Trace): Meter < (IOs & S1 & S2 & S3) =
        pipeline[S1 & S2 & S3](List(m1, m2, m3))

    def pipeline[S1, S2, S3, S4](
        m1: Meter < S1,
        m2: Meter < S2,
        m3: Meter < S3,
        m4: Meter < S4
    )(using Trace): Meter < (IOs & S1 & S2 & S3 & S4) =
        pipeline[S1 & S2 & S3 & S4](List(m1, m2, m3, m4))

    def pipeline[S](meters: Seq[Meter < (IOs & S)])(using Trace): Meter < (IOs & S) =
        Seqs.collect(meters).map { seq =>
            val meters = seq.toIndexedSeq
            new Meter:

                def available(using Trace) =
                    Loops.indexed(0) { (idx, acc) =>
                        if idx == meters.length then Loops.done(acc)
                        else meters(idx).available.map(v => Loops.continue(acc + v))
                    }

                def run[T, S](v: => T < S)(using Trace) =
                    def loop(idx: Int = 0): T < (S & Fibers) =
                        if idx == meters.length then v
                        else meters(idx).run(loop(idx + 1))
                    loop()
                end run

                def tryRun[T, S](v: => T < S)(using Trace) =
                    def loop(idx: Int = 0): Option[T] < (S & IOs) =
                        if idx == meters.length then v.map(Some(_))
                        else
                            meters(idx).tryRun(loop(idx + 1)).map {
                                case None => None
                                case r    => r.flatten
                            }
                    loop()
                end tryRun

            end new
        }

    private def offer[T](n: Int, chan: Channel[T], v: T)(using Trace): Unit < IOs =
        Loops.indexed { idx =>
            if idx == n then Loops.done
            else
                chan.offer(v).map {
                    case true  => Loops.continue
                    case false => Loops.done
                }
        }
end Meters
