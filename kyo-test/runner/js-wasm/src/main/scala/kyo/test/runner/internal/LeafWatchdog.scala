package kyo.test.runner.internal

import kyo.Absent
import kyo.Chunk
import kyo.Duration
import kyo.Maybe
import kyo.Present
import kyo.test.runner.watchdogMarginMs
import scala.scalajs.js

/** Ends the Node process when a leaf holds the event loop past its timeout.
  *
  * A leaf's timeout is a timer on the same event loop as the leaf, so a body that spins in a synchronous loop without reaching a kyo
  * safepoint keeps the timer from ever firing, and the run hangs until whatever runs the build gives up. The watchdog is a Node
  * `worker_thread`, whose own event loop keeps running while the main one is held. The main thread bumps a shared counter every 100 ms and
  * posts each timed leaf's start and end to the worker. Once a leaf is past its timeout plus the margin and the counter has not moved for
  * longer than the margin, the timeout cannot fire: the worker pauses the main thread through `node:inspector` to take its stack, writes
  * every such leaf's name and that stack straight to file descriptor 2, and kills the process. Leaves run concurrently by default, so more
  * than one can be past its timeout; the stack shows which one holds the thread.
  *
  * It kills rather than recovers because nothing resumes the main thread safely: V8's `TerminateExecution` unwinds Node's callback frames
  * without their `finally` blocks, and Node aborts on the corrupted async-hooks stack when the callback returns. The sbt test adapter then
  * reports the Node process as gone, which fails the module's test task instead of letting it hang.
  *
  * The margin is [[kyo.test.runner.watchdogMarginMs]]; zero disables the watchdog. It is also off on a host without `worker_threads` and
  * when the inspector is open, because a debugger paused at a breakpoint holds the event loop the same way.
  */
private[runner] object LeafWatchdog:

    final private class Watch(worker: js.Dynamic):
        private var lastToken = 0

        def arm(leaf: String, timeoutMs: Double): Int =
            lastToken += 1
            kyo.discard(worker.postMessage(js.Dynamic.literal(op = "arm", id = lastToken, leaf = leaf, timeoutMs = timeoutMs)))
            lastToken
        end arm

        def disarm(token: Int): Unit =
            kyo.discard(worker.postMessage(js.Dynamic.literal(op = "disarm", id = token)))
    end Watch

    private lazy val watch: Maybe[Watch] = start()

    /** Watches `path` of `suite` from now until [[disarm]] with the returned token. */
    def arm(suite: String, path: Chunk[String], timeout: Duration): Int =
        watch match
            case Present(w) if timeout.isFinite => w.arm((suite +: path).mkString(" > "), timeout.toMillis.toDouble)
            case _                              => 0

    def disarm(token: Int): Unit =
        if token != 0 then watch.foreach(_.disarm(token))

    private def start(): Maybe[Watch] =
        val margin = watchdogMarginMs()
        // Must stay inline on the global selection: only then does Scala.js emit `typeof process`, safe on an undeclared identifier.
        if margin <= 0 || js.typeOf(js.Dynamic.global.process) == "undefined" then Absent
        else if js.typeOf(js.Dynamic.global.process.getBuiltinModule) != "function" then Absent
        else
            val process   = js.Dynamic.global.process
            val threads   = process.getBuiltinModule("node:worker_threads")
            val inspector = process.getBuiltinModule("node:inspector")
            if missing(threads) then Absent
            else if !missing(inspector) && !js.isUndefined(inspector.url()) then Absent
            else
                try
                    val beats   = js.Dynamic.newInstance(js.Dynamic.global.SharedArrayBuffer)(4)
                    val counter = js.Dynamic.newInstance(js.Dynamic.global.Int32Array)(beats)
                    val worker  = js.Dynamic.newInstance(threads.Worker)(
                        workerSource,
                        js.Dynamic.literal(eval = true, workerData = js.Dynamic.literal(beats = beats, marginMs = margin))
                    )
                    kyo.discard(worker.unref())
                    val atomics = js.Dynamic.global.Atomics
                    val beat    = js.timers.setInterval(100.0)(kyo.discard(atomics.add(counter, 0, 1)))
                    kyo.discard(beat.asInstanceOf[js.Dynamic].unref())
                    Present(new Watch(worker))
                catch
                    case scala.util.control.NonFatal(e) =>
                        java.lang.System.err.println(
                            s"[kyo-test] watchdog: not started ($e); a leaf that holds the event loop will hang the run"
                        )
                        Absent
            end if
        end if
    end start

    private def missing(module: js.Dynamic): Boolean = js.isUndefined(module) || module == null

    // The report goes out through fs.writeSync because a worker's console is relayed by the main thread, which is the thread being held.
    // Modules come from process.getBuiltinModule: the worker inherits the process's Node flags, and under `--input-type=module` an eval
    // worker is an ES module with no `require`.
    private val workerSource: String =
        """
        |const { parentPort, workerData } = process.getBuiltinModule('node:worker_threads');
        |const fs = process.getBuiltinModule('node:fs');
        |const beats = new Int32Array(workerData.beats);
        |const margin = workerData.marginMs;
        |const armed = new Map();
        |let beat = Atomics.load(beats, 0);
        |let beatAt = performance.now();
        |parentPort.on('message', (m) => {
        |  if (m.op === 'arm') armed.set(m.id, { leaf: m.leaf, timeoutMs: m.timeoutMs, at: performance.now() });
        |  else armed.delete(m.id);
        |});
        |const poll = setInterval(() => {
        |  const now = performance.now();
        |  const current = Atomics.load(beats, 0);
        |  if (current !== beat) { beat = current; beatAt = now; return; }
        |  if (now - beatAt <= margin) return;
        |  const overdue = [...armed.values()].filter((leaf) => now - leaf.at > leaf.timeoutMs + margin);
        |  if (overdue.length === 0) return;
        |  clearInterval(poll);
        |  trip(overdue, now - beatAt);
        |}, 250);
        |// Error.stack on the paused frame names each method by its receiver and goes through the source maps Node applies; the raw
        |// inspector frames carry neither, since the linker emits methods as anonymous function expressions.
        |const stackExpression =
        |  '(() => { const limit = Error.stackTraceLimit; Error.stackTraceLimit = 64; ' +
        |  'try { return new Error().stack; } finally { Error.stackTraceLimit = limit; } })()';
        |function trip(overdue, heldMs) {
        |  let reported = false;
        |  const report = (stack) => {
        |    if (reported) return;
        |    reported = true;
        |    const leaves = overdue.map((leaf) => '  ' + leaf.leaf + ' (timeout ' + leaf.timeoutMs + ' ms)').join('\n');
        |    fs.writeSync(2,
        |      '\n[kyo-test] watchdog: the event loop has been held for ' + Math.round(heldMs) + ' ms, so these leaves are past their ' +
        |      'timeout and it cannot fire:\n' + leaves + '\n[kyo-test] watchdog: stack of the main thread:\n' + stack + '\n' +
        |      '[kyo-test] watchdog: killing the test process (pid ' + process.pid + '); the margin is KYO_TEST_RUNNER_WATCHDOGMARGINMS=' +
        |      margin + '\n');
        |    process.kill(process.pid, 'SIGKILL');
        |  };
        |  setTimeout(() => report('  (the main thread did not pause within 5000 ms: it is blocked outside JavaScript)'), 5000);
        |  try {
        |    const session = new (process.getBuiltinModule('node:inspector').Session)();
        |    const urls = new Map();
        |    session.on('Debugger.scriptParsed', (m) => urls.set(m.params.scriptId, m.params.url));
        |    session.on('Debugger.paused', (m) => {
        |      const frames = m.params.callFrames.map((f) =>
        |        '    at ' + (f.functionName || '<anonymous>') + ' (' + (urls.get(f.location.scriptId) || f.url) + ':' +
        |        (f.location.lineNumber + 1) + ':' + (f.location.columnNumber + 1) + ')'
        |      ).join('\n');
        |      session.post('Debugger.evaluateOnCallFrame',
        |        { callFrameId: m.params.callFrames[0].callFrameId, expression: stackExpression, returnByValue: true },
        |        (e, r) => {
        |          const text = !e && r && !r.exceptionDetails && typeof r.result.value === 'string' ? r.result.value : undefined;
        |          report(text === undefined ? frames : text.split('\n').slice(1).filter((l) => !l.includes('eval at ')).join('\n'));
        |        });
        |    });
        |    session.connectToMainThread();
        |    session.post('Debugger.enable', (e) => e ? report('  (no stack: ' + e.message + ')') : session.post('Debugger.pause'));
        |  } catch (e) {
        |    report('  (no stack: ' + e + ')');
        |  }
        |}
        |""".stripMargin

end LeafWatchdog
