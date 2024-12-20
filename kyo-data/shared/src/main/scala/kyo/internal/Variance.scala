package kyo.internal

opaque type Variance = Char
export Variance.*
private[kyo] object Variance:
    given CanEqual[Variance, Variance] = CanEqual.derived

    val Invariant: Variance     = '='
    val Covariant: Variance     = '+'
    val Contravariant: Variance = '-'

    extension (self: Variance)
        def flip: Variance = self match
            case Covariant     => Contravariant
            case Contravariant => Covariant
            case Invariant     => Invariant

        def &(other: Variance): Variance =
            self match
                case Invariant => Invariant
                case Covariant =>
                    other match
                        case Invariant     => Invariant
                        case Covariant     => Covariant
                        case Contravariant => Contravariant
                case Contravariant =>
                    other match
                        case Invariant     => Invariant
                        case Covariant     => Contravariant
                        case Contravariant => Covariant

        def show: String = self match
            case Covariant     => "+"
            case Contravariant => "-"
            case Invariant     => ""
    end extension

    def fromChar(c: Char): Variance = c match
        case '+' => Covariant
        case '-' => Contravariant
        case '=' => Invariant
        case _   => throw IllegalArgumentException(s"Invalid variance: $c")
end Variance
