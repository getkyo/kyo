package kyo.stats.internal

import java.util.ServiceLoader
import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import scala.jdk.CollectionConverters.*

trait TraceReceiver:

    def startSpan(
        scope: List[String],
        name: String,
        parent: Maybe[Span] = Maybe.empty,
        attributes: Attributes = Attributes.empty
    ): Span < IO
end TraceReceiver

object TraceReceiver:

    lazy val get: TraceReceiver =
        ServiceLoader.load(classOf[TraceReceiver]).iterator().asScala.toList match
            case Nil =>
                TraceReceiver.noop
            case head :: Nil =>
                head
            case l =>
                TraceReceiver.all(l)

    val noop: TraceReceiver =
        new TraceReceiver:
            def startSpan(
                scope: List[String],
                name: String,
                parent: Maybe[Span] = Maybe.empty,
                attributes: Attributes = Attributes.empty
            ) =
                Span.noop

    def all(receivers: List[TraceReceiver]): TraceReceiver =
        new TraceReceiver:
            def startSpan(
                scope: List[String],
                name: String,
                parent: Maybe[Span] = Maybe.empty,
                a: Attributes = Attributes.empty
            ) =
                Kyo.foreach(receivers)(_.startSpan(scope, name, Maybe.empty, a))
                    .map(Span.all)
end TraceReceiver
