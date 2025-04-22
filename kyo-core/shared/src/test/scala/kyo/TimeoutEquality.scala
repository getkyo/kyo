package kyo

import org.scalactic.Equality

given Equality[Timeout] with
    def areEqual(a: Timeout, b: Any): Boolean =
        b match
            case b: Timeout => a.getMessage == b.getMessage
            case _          => false
end given
