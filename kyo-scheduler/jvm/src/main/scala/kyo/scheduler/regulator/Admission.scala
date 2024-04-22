package kyo.scheduler.regulator

import kyo.scheduler.InternalTimer
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.hashing.MurmurHash3

final class Admission(
    loadAvg: () => Double,
    timer: InternalTimer,
    config: Config =
        Config(
            collectWindow = 40, // 4 regulate intervals
            collectInterval = 100.millis,
            regulateInterval = 1000.millis,
            jitterUpperThreshold = 100,
            jitterLowerThreshold = 80,
            loadAvgTarget = 0.8,
            stepExp = 1.5
        )
) extends Regulator(loadAvg, timer, config):

    @volatile private var admissionPercent = 100

    protected def probe() =
        val start = System.nanoTime()
        Thread.sleep(1)
        measure(System.nanoTime() - start - 1000000)
    end probe

    protected def update(diff: Int): Unit =
        admissionPercent = Math.max(0, Math.min(100, admissionPercent + diff))

    def reject(keyPath: Seq[String]): Boolean =
        val threshold = admissionPercent / keyPath.length
        @tailrec
        def loop(keys: Seq[String], index: Int): Boolean =
            keys match
                case Seq() => false
                case key +: rest =>
                    val hash           = MurmurHash3.stringHash(key)
                    val layerThreshold = threshold * (index + 1)
                    if (hash.abs % 100) <= (layerThreshold * 100) then
                        loop(rest, index + 1)
                    else
                        true
                    end if
        loop(keyPath, 0)
    end reject

    override def toString = s"Admission($admissionPercent)"

end Admission
