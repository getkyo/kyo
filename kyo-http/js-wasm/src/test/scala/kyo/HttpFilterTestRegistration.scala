package kyo

import kyo.stats.internal.JSServiceLoaderRegistry
import scala.scalajs.js.annotation.JSExportTopLevel

object HttpFilterTestRegistration:

    @JSExportTopLevel("__kyo_http_filter_test_init")
    val init: Boolean =
        JSServiceLoaderRegistry.register(classOf[HttpFilter.Factory], new HttpFilterTestFactory())
        true
    end init

end HttpFilterTestRegistration
