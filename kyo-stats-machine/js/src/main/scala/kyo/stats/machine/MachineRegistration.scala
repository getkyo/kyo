package kyo.stats.machine

import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.JSServiceLoaderRegistry
import scala.scalajs.js.annotation.JSExportTopLevel

/** JS/Wasm registration for the machine-stats factory.
  *
  * Scala.js and Wasm do no `META-INF/services` classpath discovery, so this object registers the factory
  * with `JSServiceLoaderRegistry` at module load. The `@JSExportTopLevel` annotation forces the
  * registration side-effect to run, mirroring `kyo-stats-otlp`'s `OTLPRegistration`.
  */
object MachineRegistration:
    @JSExportTopLevel("__kyo_machine_init")
    val init: Boolean =
        // Registering is not a passive act here: `kyo.Stat`'s class init constructs every factory the registry holds, and
        // this factory's constructor starts the sampler. The sampler reads the machine through Node's `os` and `fs`, so on
        // a host that has neither, a browser page, registering would start a fiber that fails on its first read inside a
        // program that never asked for machine statistics. Where there is no machine to read, there is no factory.
        if kyo.internal.Platform.isNodeLike then
            JSServiceLoaderRegistry.register(classOf[ExporterFactory], new MachineStatFactory())
        true
    end init
end MachineRegistration
