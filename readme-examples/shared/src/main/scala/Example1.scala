import kyo.*

// Use the direct syntax
val a: String < (Aborts[Exception] & Options) =
    defer {
        val b: String =
            await(Options.get(Some("hello")))
        val c: String =
            await(Aborts.get(Right("world")))
        b + " " + c
    }

// Equivalent desugared
val b: String < (Aborts[Exception] & Options) =
    Options.get(Some("hello")).map { b =>
        Aborts.get(Right("world")).map { c =>
            b + " " + c
        }
    }
