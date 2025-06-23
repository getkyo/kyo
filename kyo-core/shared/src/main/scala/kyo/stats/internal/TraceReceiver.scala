package kyo.stats.internal

import java.util.ServiceLoader
import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import scala.jdk.CollectionConverters.*

trait TraceReceiver extends Serializable:

    def startSpan(
        scope: List[String],
        name: String,
        parent: Maybe[Span] = Maybe.empty,
        attributes: Attributes = Attributes.empty
    )(using Frame): Span < Sync
end TraceReceiver

object TraceReceiver:

    val noop: TraceReceiver =
        new TraceReceiver:
            def startSpan(
                scope: List[String],
                name: String,
                parent: Maybe[Span] = Maybe.empty,
                attributes: Attributes = Attributes.empty
            )(using Frame) =
                Span.noop

    val get: TraceReceiver =
        ServiceLoader.load(classOf[TraceReceiver]).iterator().asScala.toList match
            case Nil =>
                TraceReceiver.noop
            case head :: Nil =>
                head
            case l =>
                TraceReceiver.all(l)

    def all(receivers: List[TraceReceiver]): TraceReceiver =
        new TraceReceiver:
            def startSpan(
                scope: List[String],
                name: String,
                parent: Maybe[Span] = Maybe.empty,
                a: Attributes = Attributes.empty
            )(using Frame) =
                Kyo.foreach(receivers)(_.startSpan(scope, name, Maybe.empty, a))
                    .map(Span.all)
end TraceReceiver
