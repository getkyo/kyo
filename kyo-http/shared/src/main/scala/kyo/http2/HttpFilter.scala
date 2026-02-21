package kyo.http2

import kyo.<
import kyo.Frame
import kyo.Record
import kyo.Record.~

sealed abstract class HttpFilter[ReqIn, ReqOut, ResIn, ResOut, -S]:

    def apply[In, Out, S2](
        request: Record[In & ReqIn],
        next: Record[In & ReqIn & ReqOut] => Record[Out & ResIn] < S2
    ): Record[Out & ResIn & ResOut] < (S & S2)

    final def andThen[RI2, RO2, SI2, SO2, S2](
        that: HttpFilter[RI2, RO2, SI2, SO2, S2]
    ): HttpFilter[ReqIn & RI2, ReqOut & RO2, ResIn & SI2, ResOut & SO2, S & S2] =
        val self = this
        new HttpFilter[ReqIn & RI2, ReqOut & RO2, ResIn & SI2, ResOut & SO2, S & S2]:
            def apply[In, Out, S3](
                request: Record[In & ReqIn & RI2],
                next: Record[In & ReqIn & RI2 & ReqOut & RO2] => Record[Out & ResIn & SI2] < S3
            ): Record[Out & ResIn & SI2 & ResOut & SO2] < (S & S2 & S3) =
                self(request, req1 => that(req1, next))
        end new
    end andThen

end HttpFilter

object HttpFilter:

    opaque type Request[ReqIn, ReqOut, ResOut, -S] <: HttpFilter[ReqIn, ReqOut, Any, ResOut, S] =
        HttpFilter[ReqIn, ReqOut, Any, ResOut, S]
    opaque type Response[ResIn, ResOut, -S] <: HttpFilter[Any, Any, ResIn, ResOut, S] =
        HttpFilter[Any, Any, ResIn, ResOut, S]
    opaque type Transparent[ResOut, -S] <: HttpFilter[Any, Any, Any, ResOut, S] =
        HttpFilter[Any, Any, Any, ResOut, S]

    def request[ReqIn, ReqOut](using
        Frame
    )[ResOut, S](
        f: [In, Out, S2] => (
            Record[In & ReqIn],
            Record[In & ReqIn & ReqOut] => Record[Out] < S2
        ) => Record[Out & ResOut] < (S & S2)
    ): Request[ReqIn, ReqOut, ResOut, S] =
        new HttpFilter[ReqIn, ReqOut, Any, ResOut, S]:
            def apply[In, Out, S2](
                request: Record[In & ReqIn],
                next: Record[In & ReqIn & ReqOut] => Record[Out] < S2
            ): Record[Out & ResOut] < (S & S2) =
                f(request, next)

    def response[ResIn](using
        Frame
    )[ResOut, S](
        f: [In, Out, S2] => (
            Record[In],
            Record[In] => Record[Out & ResIn] < S2
        ) => Record[Out & ResIn & ResOut] < (S & S2)
    ): Response[ResIn, ResOut, S] =
        new HttpFilter[Any, Any, ResIn, ResOut, S]:
            def apply[In, Out, S2](
                request: Record[In],
                next: Record[In] => Record[Out & ResIn] < S2
            ): Record[Out & ResIn & ResOut] < (S & S2) =
                f(request, next)

    def transparent(using
        Frame
    )[ResOut, S](
        f: [In, Out, S2] => (
            Record[In],
            Record[In] => Record[Out] < S2
        ) => Record[Out & ResOut] < (S & S2)
    ): Transparent[ResOut, S] =
        new HttpFilter[Any, Any, Any, ResOut, S]:
            def apply[In, Out, S2](
                request: Record[In],
                next: Record[In] => Record[Out] < S2
            ): Record[Out & ResOut] < (S & S2) =
                f(request, next)

    def apply[ReqIn, ReqOut, ResIn](using
        Frame
    )[ResOut, S](
        f: [In, Out, S2] => (
            Record[In & ReqIn],
            Record[In & ReqIn & ReqOut] => Record[Out & ResIn] < S2
        ) => Record[Out & ResIn & ResOut] < (S & S2)
    ): HttpFilter[ReqIn, ReqOut, ResIn, ResOut, S] =
        new HttpFilter[ReqIn, ReqOut, ResIn, ResOut, S]:
            def apply[In, Out, S2](
                request: Record[In & ReqIn],
                next: Record[In & ReqIn & ReqOut] => Record[Out & ResIn] < S2
            ): Record[Out & ResIn & ResOut] < (S & S2) =
                f(request, next)

    val noop: HttpFilter[Any, Any, Any, Any, Any] =
        new HttpFilter[Any, Any, Any, Any, Any]:
            def apply[In, Out, S2](
                request: Record[In],
                next: Record[In] => Record[Out] < S2
            ): Record[Out] < S2 =
                next(request)

end HttpFilter
