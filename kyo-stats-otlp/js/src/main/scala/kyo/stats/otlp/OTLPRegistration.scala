package kyo.stats.otlp

import kyo.HttpFilterFactory
import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.JSServiceLoaderRegistry
import scala.scalajs.js.annotation.JSExportTopLevel

/** JS platform registration for OTLP factories.
  *
  * Since Scala.js does not support `META-INF/services` service loading, this object explicitly registers the [[OTLPExporterFactory]] and
  * [[OTLPHttpFilterFactory]] with the `JSServiceLoaderRegistry`. The `@JSExportTopLevel` annotation ensures the registration side-effect
  * runs at module load time.
  */
object OTLPRegistration:
    @JSExportTopLevel("__kyo_otel_init")
    val init: Boolean =
        JSServiceLoaderRegistry.register(classOf[ExporterFactory], new OTLPExporterFactory())
        JSServiceLoaderRegistry.register(classOf[HttpFilterFactory], new OTLPHttpFilterFactory())
        true
    end init
end OTLPRegistration
