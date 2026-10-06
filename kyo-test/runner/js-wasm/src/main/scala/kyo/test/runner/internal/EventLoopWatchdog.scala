package kyo.test.runner.internal

import kyo.Duration
import kyo.Maybe
import kyo.discard
import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Watches the JS event loop from a worker thread, so a leaf that blocks the loop with a synchronous call still gets its STUCK diagnostics
  * and its timeout.
  *
  * A leaf's timeout and heartbeat are timers on the event loop, and a synchronous call that never returns (a busy wait, a blocking
  * native call) leaves no turn for either: the run hangs with no output until the CI job is killed. The main thread bumps a counter in
  * shared memory every few hundred milliseconds; the worker sees the counter stop. Once a running leaf has been blocked past its heartbeat
  * interval it prints `[STUCK]`, and once it has been blocked past its timeout plus a grace period it prints `[TIMEOUT]` and, in the
  * runner's own instance, stops the process: nothing can unwind the blocked call, since terminating the main thread's execution aborts
  * Node.
  *
  * Each report carries samples of the main thread's stack. They come from the inspector, whose IO thread interrupts V8 directly, which is
  * the one channel that reaches a busy main thread. A short-lived `node -e process._debugProcess(pid)` opens it, the way `node inspect -p`
  * does, which works on every platform where SIGUSR1 does not.
  */
private[runner] object EventLoopWatchdog:

    /** The runner's instance, started with the first leaf of this JS environment. */
    private lazy val runner: Instance = Instance.start(stopProcess = true, pollMillis = 250, killGraceMillis = 30000)

    def leafStarted(label: String, timeout: Maybe[Duration], stuckAfter: Duration): Int = runner.leafStarted(label, timeout, stuckAfter)

    def leafFinished(id: Int): Unit = runner.leafFinished(id)

    /** One worker and the leaves it watches. `stopProcess = false` only reports, which is how its tests observe it from the process it
      * watches.
      */
    final class Instance private (worker: js.Dynamic):
        private var nextId                          = 0
        private var listeners: List[String => Unit] = Nil
        private var ready                           = false
        private var readyListeners: List[() => Unit] = Nil

        discard(worker.on(
            "message",
            { (msg: js.Dynamic) =>
                msg.op.asInstanceOf[String] match
                    case "report" =>
                        Heartbeat.closeInspector()
                        val text = msg.text.asInstanceOf[String]
                        listeners.foreach(_(text))
                    case "port" =>
                        ready = true
                        readyListeners.foreach(_())
                        readyListeners = Nil
                    case _ => ()
            }: js.Function1[js.Dynamic, Unit]
        ))

        /** Calls `f` once the main thread's inspector has a port, before which a report carries no stack. */
        def onReady(f: () => Unit): Unit = if ready then f() else readyListeners = f :: readyListeners

        def leafStarted(label: String, timeout: Maybe[Duration], stuckAfter: Duration): Int =
            nextId += 1
            worker.postMessage(js.Dynamic.literal(
                op = "start",
                id = nextId,
                label = label,
                timeoutMs = timeout.fold(-1.0)(millis),
                stuckMs = millis(stuckAfter)
            ))
            nextId
        end leafStarted

        def leafFinished(id: Int): Unit = worker.postMessage(js.Dynamic.literal(op = "end", id = id))

        /** Calls `f` with the text of each report, once the main thread is free to run it. */
        def onReport(f: String => Unit): Unit = listeners = f :: listeners

        def close(): Unit = discard(worker.terminate())
    end Instance

    object Instance:
        def start(stopProcess: Boolean, pollMillis: Int, killGraceMillis: Int): Instance =
            val worker = js.Dynamic.newInstance(NodeWorkerThreads.asInstanceOf[js.Dynamic].Worker)(
                WorkerSource,
                js.Dynamic.literal(
                    eval = true,
                    workerData = js.Dynamic.literal(
                        shared = Heartbeat.shared,
                        stopProcess = stopProcess,
                        pollMs = pollMillis,
                        killGraceMs = killGraceMillis
                    )
                )
            )
            discard(worker.unref())
            Heartbeat.assignPort(worker)
            new Instance(worker)
        end start
    end Instance

    private def millis(d: Duration): Double = if d == Duration.Infinity then -1.0 else d.toMillis.toDouble

    // Slot 0 is the counter the main thread bumps, slot 1 the inspector port every worker reads when it samples. One buffer serves every
    // instance, so they agree on the port the main thread's inspector opens on.
    private object Heartbeat:
        val shared: js.Dynamic = js.Dynamic.newInstance(js.Dynamic.global.SharedArrayBuffer)(8)
        private val slots      = js.Dynamic.newInstance(js.Dynamic.global.Int32Array)(shared)
        private val atomics    = js.Dynamic.global.Atomics

        private val timer = js.Dynamic.global.setInterval((() => discard(atomics.add(slots, 0, 1))): js.Function0[Unit], 100)
        discard(timer.unref())

        // A port the main thread's inspector will listen on when a worker opens it. An inspector already open (`--inspect`) keeps its
        // own port; otherwise the first worker to find a free port decides it for all.
        if !js.isUndefined(NodeInspector.url()) then discard(atomics.store(slots, 1, NodeProcess.debugPort))

        def assignPort(worker: js.Dynamic): Unit =
            discard(worker.on(
                "message",
                { (msg: js.Dynamic) =>
                    if msg.op.asInstanceOf[String] == "port" && atomics.load(slots, 1).asInstanceOf[Int] == 0 then
                        val port = msg.port.asInstanceOf[Int]
                        NodeProcess.debugPort = port
                        discard(atomics.store(slots, 1, port))
                }: js.Function1[js.Dynamic, Unit]
            ))

        /** The inspector a report opened stays open after the main thread resumes; nothing else needs it. */
        def closeInspector(): Unit = if !js.isUndefined(NodeInspector.url()) then NodeInspector.close()
    end Heartbeat

    // Runs in the worker, as CommonJS. `shared` is the heartbeat buffer above. A leaf is blocked when the counter has not moved since
    // before its deadline (or for its heartbeat interval); the grace keeps a slow but live loop from being reported.
    private val WorkerSource: String = """
const { parentPort, workerData } = require("node:worker_threads");
const fs = require("node:fs");
const net = require("node:net");
const childProcess = require("node:child_process");
const slots = new Int32Array(workerData.shared);
const leaves = new Map();
let lastValue = Atomics.load(slots, 0);
let lastChange = Date.now();
let reporting = false;
const stuckReported = new Set();

const probe = net.createServer();
probe.listen(0, "127.0.0.1", () => {
  const port = probe.address().port;
  probe.close(() => parentPort.postMessage({ op: "port", port }));
});

parentPort.on("message", (m) => {
  if (m.op === "start") leaves.set(m.id, { label: m.label, start: Date.now(), timeoutMs: m.timeoutMs, stuckMs: m.stuckMs });
  else if (m.op === "end") { leaves.delete(m.id); stuckReported.delete(m.id); }
});

const seconds = (ms) => (ms / 1000).toFixed(1) + "s";

async function sampleMain() {
  const port = Atomics.load(slots, 1);
  if (port === 0) return ["    (no stack: the inspector port is not assigned yet)"];
  if (typeof WebSocket === "undefined") return ["    (no stack: this Node has no WebSocket client)"];
  childProcess.spawnSync(process.execPath, ["-e", "process._debugProcess(" + process.pid + ")"], { stdio: "ignore", timeout: 5000 });
  let target;
  for (let i = 0; i < 30 && !target; i++) {
    try { target = (await (await fetch("http://127.0.0.1:" + port + "/json/list")).json())[0]; }
    catch { await new Promise((r) => setTimeout(r, 100)); }
  }
  if (!target) return ["    (no stack: the inspector did not open on port " + port + ")"];
  return await new Promise((resolve) => {
    const out = [];
    let id = 0;
    let samples = 0;
    const ws = new WebSocket(target.webSocketDebuggerUrl);
    const send = (method) => ws.send(JSON.stringify({ id: ++id, method, params: {} }));
    const finish = () => {
      clearTimeout(timer);
      try { send("Debugger.disable"); ws.close(); } catch {}
      resolve(out.length > 0 ? out : ["    (no stack: the main thread did not pause)"]);
    };
    const timer = setTimeout(finish, 10000);
    ws.onerror = finish;
    ws.onopen = () => { send("Debugger.enable"); send("Debugger.pause"); };
    ws.onmessage = (event) => {
      const m = JSON.parse(event.data);
      if (m.method !== "Debugger.paused") return;
      samples++;
      out.push("  sample " + samples + ":");
      for (const f of m.params.callFrames.slice(0, 40))
        out.push("    at " + (f.functionName || "<anonymous>") + " (" + f.url + ":" + (f.location.lineNumber + 1) + ")");
      send("Debugger.resume");
      if (samples >= 3) finish();
      else setTimeout(() => send("Debugger.pause"), 200);
    };
  });
}

async function report(kind, leaf, blocked, now) {
  const head = kind === "TIMEOUT"
    ? "[TIMEOUT] " + leaf.label + "  (limit: " + seconds(leaf.timeoutMs) + ") *** FAILED ***"
    : "[STUCK] " + leaf.label + "  (" + seconds(now - leaf.start) + ")";
  const stop = kind === "TIMEOUT" && workerData.stopProcess ? " Stopping the test process, since nothing can unwind the call." : "";
  const lines = [
    head,
    "kyo-test: the event loop has been blocked for " + seconds(blocked) + " by a synchronous call, so no timer can fire, " +
      "neither this leaf's timeout nor its heartbeat." + stop,
    "kyo-test: main thread stack while blocked:"
  ].concat(await sampleMain());
  const text = lines.join("\n") + "\n";
  fs.writeSync(1, text);
  parentPort.postMessage({ op: "report", text });
}

setInterval(async () => {
  const value = Atomics.load(slots, 0);
  const now = Date.now();
  if (value !== lastValue) { lastValue = value; lastChange = now; stuckReported.clear(); return; }
  if (reporting) return;
  const blocked = now - lastChange;
  for (const [id, leaf] of leaves) {
    const deadline = leaf.timeoutMs >= 0 ? leaf.start + leaf.timeoutMs : Infinity;
    if (lastChange <= deadline && now >= deadline + workerData.killGraceMs) {
      reporting = true;
      leaves.delete(id);
      await report("TIMEOUT", leaf, blocked, now);
      if (workerData.stopProcess) process.kill(process.pid, "SIGKILL");
      reporting = false;
      return;
    }
    if (leaf.stuckMs >= 0 && blocked >= leaf.stuckMs && !stuckReported.has(id)) {
      reporting = true;
      stuckReported.add(id);
      await report("STUCK", leaf, blocked, now);
      reporting = false;
      return;
    }
  }
}, workerData.pollMs);
"""

end EventLoopWatchdog

@js.native
@JSImport("node:worker_threads", JSImport.Namespace)
private object NodeWorkerThreads extends js.Object

@js.native
@JSImport("node:inspector", JSImport.Namespace)
private object NodeInspector extends js.Object:
    def url(): js.UndefOr[String] = js.native
    def close(): Unit             = js.native

// The process object itself rather than its namespace: a namespace's members are read-only, and `debugPort` must be set.
@js.native
@JSImport("node:process", JSImport.Default)
private object NodeProcess extends js.Object:
    var debugPort: Int = js.native
