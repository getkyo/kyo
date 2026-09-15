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
        JSServiceLoaderRegistry.register(classOf[ExporterFactory], new MachineStatFactory())
        true
    end init
end MachineRegistration
