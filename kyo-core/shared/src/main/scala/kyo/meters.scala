package kyo

import scala.concurrent.duration.Duration

abstract class Meter:
    self =>

    def available: Int < IOs

    def isAvailable: Boolean < IOs = available.map(_ > 0)

    def run[T, S](v: => T < S): T < (S & Fibers)

    def tryRun[T, S](v: => T < S): Option[T] < (IOs & S)
end Meter

object Meters:

    def initNoop: Meter =
        new Meter:
            def available                 = Int.MaxValue
            def run[T, S](v: => T < S)    = v
            def tryRun[T, S](v: => T < S) = v.map(Some(_))

    def initMutex: Meter < IOs =
        initSemaphore(1)

    def initSemaphore(concurrency: Int): Meter < IOs =
        Channels.init[Unit](concurrency).map { chan =>
            offer(concurrency, chan, ()).map { _ =>
                new Meter:
                    def available = chan.size
                    val release   = chan.offerUnit(())

                    def run[T, S](v: => T < S) =
                        IOs.ensure(release) {
                            chan.take.andThen(v)
                        }

                    def tryRun[T, S](v: => T < S) =
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

    def initRateLimiter(rate: Int, period: Duration): Meter < IOs =
        Channels.init[Unit](rate).map { chan =>
            Timers.scheduleAtFixedRate(period)(offer(rate, chan, ())).map { _ =>
                new Meter:

                    def available              = chan.size
                    def run[T, S](v: => T < S) = chan.take.map(_ => v)

                    def tryRun[T, S](v: => T < S) =
                        chan.poll.map {
                            case None =>
                                None
                            case _ =>
                                v.map(Some(_))
                        }
            }
        }

    def pipeline[S1, S2](m1: Meter < S1, m2: Meter < S2): Meter < (IOs & S1 & S2) =
        pipeline[S1 & S2](List(m1, m2))

    def pipeline[S1, S2, S3](
        m1: Meter < S1,
        m2: Meter < S2,
        m3: Meter < S3
    ): Meter < (IOs & S1 & S2 & S3) =
        pipeline[S1 & S2 & S3](List(m1, m2, m3))

    def pipeline[S1, S2, S3, S4](
        m1: Meter < S1,
        m2: Meter < S2,
        m3: Meter < S3,
        m4: Meter < S4
    ): Meter < (IOs & S1 & S2 & S3 & S4) =
        pipeline[S1 & S2 & S3 & S4](List(m1, m2, m3, m4))

    def pipeline[S](l: Seq[Meter < (IOs & S)]): Meter < (IOs & S) =
        Seqs.collect(l).map { meters =>
            new Meter:

                val available =
                    def loop(l: Seq[Meter], acc: Int): Int < IOs =
                        l match
                            case Seq() => acc
                            case h +: t =>
                                h.available.map(v => loop(t, acc + v))
                    loop(meters, 0)
                end available

                def run[T, S](v: => T < S) =
                    def loop(l: Seq[Meter]): T < (S & Fibers) =
                        l match
                            case Seq() => v
                            case h +: t =>
                                h.run(loop(t))
                    loop(meters)
                end run

                def tryRun[T, S](v: => T < S) =
                    def loop(l: Seq[Meter]): Option[T] < (IOs & S) =
                        l match
                            case Seq() => v.map(Some(_))
                            case h +: t =>
                                h.tryRun(loop(t)).map {
                                    case None => None
                                    case r    => r.flatten: Option[T]
                                }
                    loop(meters)
                end tryRun
        }

    private def offer[T](n: Int, chan: Channel[T], v: T): Unit < IOs =
        if n > 0 then
            chan.offer(v).map {
                case true => offer(n - 1, chan, v)
                case _    => ()
            }
        else
            IOs.unit
end Meters
