package kyo

import kyo.internal.Trace

type Options = Aborts[None.type]

object Options:

    def empty(using Trace): Nothing < Options =
        Aborts.fail(None)

    def apply[T](v: T)(using Trace): T < Options =
        if isNull(v) then
            empty
        else
            v

    def get[T, S](v: Option[T] < S)(using Trace): T < (Options & S) =
        v.map {
            case Some(v) => v
            case None    => empty
        }

    def getOrElse[T, S1, S2](v: Option[T] < S1, default: => T < S2)(using Trace): T < (S1 & S2) =
        v.map {
            case None    => default
            case Some(v) => v
        }

    def run[T: Flat, S](v: T < (Options & S))(using Trace): Option[T] < S =
        Aborts.run(v).map {
            case Left(e)  => None
            case Right(v) => Some(v)
        }

    def orElse[T: Flat, S](l: (T < (Options & S))*)(using Trace): T < (Options & S) =
        l.toList match
            case Nil => Options.empty
            case h :: t =>
                run[T, S](h).map {
                    case None => orElse[T, S](t*)
                    case v    => get(v)
                }

end Options
