package kyo

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** SPI for contributing HTTP filters that apply globally to all requests or responses, discovered via `java.util.ServiceLoader`.
  *
  * Implementing this trait and registering it in `META-INF/services/kyo.HttpFilterFactory` is the extension point for cross-cutting
  * concerns such as distributed tracing, metrics collection, authentication, or structured logging. All discovered factories are loaded
  * once at startup and their filters composed in discovery order — the first factory's filter wraps the second, and so on.
  *
  * Override `serverFilter` to install a filter on the server side (applied to every incoming request before the route handler runs).
  * Override `clientFilter` to install a filter on the client side (applied to every outgoing request before it reaches the connection
  * pool). Return `Absent` from either method to skip installation, which lets factories be configured at runtime via system properties or
  * environment variables.
  *
  * IMPORTANT: Filter factories are loaded eagerly at first server or client use. Avoid side effects in the factory constructor itself; put
  * any initialization logic inside `serverFilter` or `clientFilter`.
  *
  * @see
  *   [[kyo.HttpFilter]] The filter type returned by each factory method
  * @see
  *   [[kyo.HttpHandler]] Applies the composed server filter on each request
  * @see
  *   [[kyo.HttpClient]] Applies the composed client filter on each outgoing request
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
