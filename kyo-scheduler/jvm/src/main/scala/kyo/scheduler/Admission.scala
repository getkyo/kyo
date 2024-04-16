package kyo.scheduler

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.util.*
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.hashing.MurmurHash3

private object Admission:
    case class Config(
        enable: Boolean = Flag("admission.enable", true),
        probeConcurrency: Int = Flag("admission.probeConcurrency", 10),
        probeIntervalMs: Int = Flag("admission.probeIntervalMs", 100),
        delayMaxTargetMs: Int = Flag("admission.delayMaxTargetMs", 100),
        refreshIntervalMs: Int = Flag("admission.refreshIntervalMs", 1000),
        executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(Threads("kyo-admission"))
    )
    object Config:
        val default: Config =
            Config(
                enable = Flag("admission.enable", true),
                probeConcurrency = Flag("admission.probeConcurrency", 10),
                probeIntervalMs = Flag("admission.probeIntervalMs", 100),
                delayMaxTargetMs = Flag("admission.delayMaxTargetMs", 100),
                refreshIntervalMs = Flag("admission.refreshIntervalMs", 1000),
                executor = Executors.newSingleThreadScheduledExecutor(Threads("kyo-admission"))
            )
    end Config
end Admission

final private class Admission(
    loadAvg: () => Double,
    schedule: (() => Unit) => Unit,
    config: Admission.Config = Config()
):

    import config.*

    private val pending = new AtomicInteger
    private val ok      = new LongAdder
    private val ko      = new LongAdder

    @volatile private var percent = 1d

    if enable then
        executor.scheduleAtFixedRate(
            () => probe(),
            probeIntervalMs,
            probeIntervalMs,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
        executor.scheduleAtFixedRate(
            () => refresh(),
            refreshIntervalMs,
            refreshIntervalMs,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
        ()
    end if

    def backpressure(keyPath: Seq[String]): Boolean =
        val threshold = percent / keyPath.length
        @tailrec
        def loop(keys: Seq[String], index: Int): Boolean =
            keys match
                case Seq() => false
                case key +: rest =>
                    val hash           = MurmurHash3.stringHash(key)
                    val layerThreshold = threshold * (index + 1)
                    if (hash.abs % 100) >= (layerThreshold * 100) then
                        loop(rest, index + 1)
                    else
                        true
                    end if
        loop(keyPath, 0)
    end backpressure

    private def probe() =
        val toLaunch = probeConcurrency - pending.get()
        if loadAvg() <= 1 then
            ok.add(toLaunch)
        else
            for _ <- 0 until toLaunch do
                pending.incrementAndGet()
                val start = System.currentTimeMillis()
                schedule(() =>
                    if (System.currentTimeMillis() - start) <= delayMaxTargetMs then
                        ok.increment()
                    else
                        ko.increment()
                    end if
                    pending.decrementAndGet()
                    ()
                )
            end for
        end if
    end probe

    private def refresh() =
        percent =
            val ok = this.ok.sumThenReset()
            val ko = this.ko.sumThenReset()
            if ok == 0 && ko == 0 && pending.get() > 0 then 0
            else if ko == 0 then 1
            else if ok == 0 then 0
            else 1 - (ko.toDouble / ok)
            end if
    end refresh

end Admission
