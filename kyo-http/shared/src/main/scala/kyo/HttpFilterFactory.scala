package kyo

import scala.jdk.CollectionConverters.*

/** SPI for contributing HTTP filters discovered via `java.util.ServiceLoader`.
  *
  * Implementations are loaded at startup and their filters are composed in discovery order. Both server-side and client-side filters are
  * supported. Return `None` to skip filter installation (e.g. when the feature is disabled by configuration).
  */
trait HttpFilterFactory:
    def serverFilter(using Frame, AllowUnsafe): Option[HttpFilter.Passthrough[Nothing]] = None
    def clientFilter(using Frame, AllowUnsafe): Option[HttpFilter.Passthrough[Nothing]] = None

object HttpFilterFactory:
    private[kyo] lazy val composedServer: HttpFilter.Passthrough[Nothing] =
        given Frame       = Frame.internal
        given AllowUnsafe = AllowUnsafe.embrace.danger
        java.util.ServiceLoader.load(classOf[HttpFilterFactory])
            .iterator().asScala
            .flatMap(_.serverFilter)
            .foldLeft(HttpFilter.noop.asInstanceOf[HttpFilter.Passthrough[Nothing]]) { (acc, f) =>
                acc.andThen(f).asInstanceOf[HttpFilter.Passthrough[Nothing]]
            }
    end composedServer

    private[kyo] lazy val composedClient: HttpFilter.Passthrough[Nothing] =
        given Frame       = Frame.internal
        given AllowUnsafe = AllowUnsafe.embrace.danger
        java.util.ServiceLoader.load(classOf[HttpFilterFactory])
            .iterator().asScala
            .flatMap(_.clientFilter)
            .foldLeft(HttpFilter.noop.asInstanceOf[HttpFilter.Passthrough[Nothing]]) { (acc, f) =>
                acc.andThen(f).asInstanceOf[HttpFilter.Passthrough[Nothing]]
            }
    end composedClient
end HttpFilterFactory
