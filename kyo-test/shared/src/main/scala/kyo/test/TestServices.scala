package kyo.test

import kyo.*
import kyo.Abort
import kyo.Env

object TestServices:
    // Converted from ZIO's TestServices.
    // TODO: Complete conversion with specific service implementations as needed.

    def exampleService: String < Env[Unit] & Abort[Exception] =
        // A simple stub implementation using Kyo semantics.
        "example"
end TestServices
