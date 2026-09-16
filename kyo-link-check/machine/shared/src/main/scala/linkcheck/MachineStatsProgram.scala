package linkcheck

// Outside the kyo package, as an application is.
import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.JSServiceLoaderRegistry

/** Whether the machine-stats factory put itself in the registry when the module loaded.
  *
  * kyo-stats-machine registers a factory whose construction starts a sampler, and the sampler reads the machine through Node's own modules.
  * A host with no machine to read gets no factory, so this program prints `false` there and `true` on Node. Nothing in it calls kyo-stats-
  * machine: the registration is a module-load side effect, which is exactly what is being observed.
  */
object MachineStats:
    def main(args: Array[String]): Unit =
        val registered = JSServiceLoaderRegistry.get(classOf[ExporterFactory].getName).nonEmpty
        println(s"machine exporter $registered")
    end main
end MachineStats
