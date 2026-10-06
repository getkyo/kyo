// Usage: node cdp-stack.mjs <port>; pauses the inspected process three times and prints each call stack.
const port = process.argv[2] || "9229";
const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const ws = new WebSocket(list[0].webSocketDebuggerUrl);
let id = 0;
let samples = 0;
const send = (method, params = {}) => ws.send(JSON.stringify({ id: ++id, method, params }));
ws.onopen = () => {
    send("Debugger.enable");
    send("Debugger.pause");
};
ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    if (msg.method === "Debugger.paused") {
        samples++;
        console.log(`=== sample ${samples} reason=${msg.params.reason}`);
        for (const f of msg.params.callFrames)
            console.log(`  ${f.functionName || "<anon>"}  ${f.url}:${f.location.lineNumber}:${f.location.columnNumber}`);
        if (samples >= 3) process.exit(0);
        send("Debugger.resume");
        setTimeout(() => send("Debugger.pause"), 1500);
    }
};
setTimeout(() => {
    console.log("cdp-stack: timed out waiting for a pause");
    process.exit(1);
}, 60000);
