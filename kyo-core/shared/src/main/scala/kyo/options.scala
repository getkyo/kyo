package kyo

type Options = Aborts[None.type]

object Options:

    private val options = Aborts[None.type]

    val empty: Nothing < Options =
        options.fail(None)

    def apply[T](v: T): T < Options =
        if v == null then
            empty
        else
            v

    def get[T, S](v: Option[T] < S): T < (Options & S) =
        v.map {
            case Some(v) => v
            case None    => empty
        }

    def getOrElse[T, S1, S2](v: Option[T] < S1, default: => T < S2): T < (S1 & S2) =
        v.map {
            case None    => default
            case Some(v) => v
        }

    def run[T: Flat, S](v: T < (Options & S)): Option[T] < S =
        options.run(v).map {
            case Left(e)  => None
            case Right(v) => Some(v)
        }

    def orElse[T: Flat, S](l: (T < (Options & S))*): T < (Options & S) =
        l.toList match
            case Nil => Options.empty
            case h :: t =>
                run[T, S](h).map {
                    case None => orElse[T, S](t*)
                    case v    => get(v)
                }

end Options
