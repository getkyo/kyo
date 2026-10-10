package kyo.net.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.*

// Imported like the facades in NodeBuiltins, since the Wasm backend links the test suites as an ES module without `require`.
@js.native
@JSImport("node:stream", JSImport.Namespace)
private[net] object NodeStream extends js.Object

/** A test-owned stand-in for the process's stdin and stdout: two `PassThrough` streams handed to [[JsTransport.openStdio]]. The test writes
  * stdin's input and reads stdout's output, and ends stdin to give the connection its EOF.
  */
final private[net] class StdioStreams:
    val stdin: js.Dynamic  = js.Dynamic.newInstance(NodeStream.asInstanceOf[js.Dynamic].PassThrough)()
    val stdout: js.Dynamic = js.Dynamic.newInstance(NodeStream.asInstanceOf[js.Dynamic].PassThrough)()

    /** How many listeners each stream holds per event, keyed `stdin:<event>` / `stdout:<event>`. */
    def listenerCounts(): Map[String, Int] =
        def of(name: String, stream: js.Dynamic): Seq[(String, Int)] =
            stream.eventNames().asInstanceOf[js.Array[js.Any]].toSeq.map { event =>
                s"$name:$event" -> stream.listenerCount(event).asInstanceOf[Int]
            }
        (of("stdin", stdin) ++ of("stdout", stdout)).toMap
    end listenerCounts
end StdioStreams
