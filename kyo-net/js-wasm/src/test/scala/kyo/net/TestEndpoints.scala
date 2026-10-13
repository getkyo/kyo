package kyo.net

/** The JS and Wasm connections no listener produces, for [[Test.eachEndpoint]], beyond the in-memory pair every platform runs. Node's stdio
  * reads `process.stdin` and writes `process.stdout` directly, so it has no cell over test-owned streams here.
  */
object TestEndpoints:

    val all: Seq[Test.EndpointCell] = Seq.empty

end TestEndpoints
