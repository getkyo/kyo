package kyo

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** SPI for contributing HTTP filters discovered via `java.util.ServiceLoader`.
  *
  * Implementations are loaded at startup and their filters are composed in discovery order. Both server-side and client-side filters are
  * supported. Return `Absent` to skip filter installation (e.g. when the feature is disabled by configuration).
  */
trait HttpFilterFactory:
    def serverFilter(using Frame, AllowUnsafe): Maybe[HttpFilter.Passthrough[Nothing]] = Absent
    def clientFilter(using Frame, AllowUnsafe): Maybe[HttpFilter.Passthrough[Nothing]] = Absent

object HttpFilterFactory:
    private[kyo] lazy val composedServer: HttpFilter.Passthrough[Nothing] =
        given Frame = Frame.internal
        import AllowUnsafe.embrace.danger
        ServiceLoader.load(classOf[HttpFilterFactory])
            .iterator().asScala
            .flatMap(_.serverFilter.toOption)
            .foldLeft(HttpFilter.noop)(_.andThen(_))
    end composedServer

    private[kyo] lazy val composedClient: HttpFilter.Passthrough[Nothing] =
        given Frame = Frame.internal
        import AllowUnsafe.embrace.danger
        ServiceLoader.load(classOf[HttpFilterFactory])
            .iterator().asScala
            .flatMap(_.clientFilter.toOption)
            .foldLeft(HttpFilter.noop)(_.andThen(_))
    end composedClient
end HttpFilterFactory
