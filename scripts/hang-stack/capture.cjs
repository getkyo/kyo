// Captures where a Node process whose event loop is stuck is running, over the inspector the caller opened with SIGUSR1.
// The inspector's messages are dispatched through a V8 interrupt, which JS and Wasm loop back-edges check, so a busy loop still answers.
// Usage: node capture.cjs <port> <label>
'use strict';
const port = Number(process.argv[2] || 9229);
const label = process.argv[3] || '';
const log = (...a) => console.log(`[hang-stack${label ? ' ' + label : ''}]`, ...a);
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function target() {
    for (let i = 0; i < 30; i++) {
        try {
            const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
            if (list.length > 0) return list[0].webSocketDebuggerUrl;
        } catch (_) {}
        await sleep(1000);
    }
    throw new Error(`no inspector on port ${port} after 30 s`);
}

function connect(url) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(url);
        let id = 0;
        const pending = new Map();
        const listeners = [];
        ws.onmessage = ev => {
            const msg = JSON.parse(ev.data);
            if (msg.id !== undefined && pending.has(msg.id)) {
                const p = pending.get(msg.id);
                pending.delete(msg.id);
                if (msg.error) p.reject(new Error(JSON.stringify(msg.error)));
                else p.resolve(msg.result);
            } else if (msg.method) {
                for (const l of listeners.slice()) l(msg);
            }
        };
        ws.onerror = e => reject(new Error(`websocket error: ${e.message || e}`));
        ws.onopen = () =>
            resolve({
                send(method, params = {}, timeoutMs = 60000) {
                    const myId = ++id;
                    ws.send(JSON.stringify({ id: myId, method, params }));
                    return new Promise((res, rej) => {
                        pending.set(myId, { resolve: res, reject: rej });
                        setTimeout(() => {
                            if (pending.delete(myId)) rej(new Error(`${method} timed out after ${timeoutMs} ms`));
                        }, timeoutMs);
                    });
                },
                next(method, timeoutMs = 60000) {
                    return new Promise((res, rej) => {
                        const l = msg => {
                            if (msg.method === method) {
                                listeners.splice(listeners.indexOf(l), 1);
                                res(msg.params);
                            }
                        };
                        listeners.push(l);
                        setTimeout(() => {
                            const i = listeners.indexOf(l);
                            if (i >= 0) {
                                listeners.splice(i, 1);
                                rej(new Error(`${method} not received after ${timeoutMs} ms`));
                            }
                        }, timeoutMs);
                    });
                },
                on(l) {
                    listeners.push(l);
                },
                close() {
                    ws.close();
                }
            });
    });
}

// A paused frame carries only a scriptId; its URL comes from the Debugger.scriptParsed event for that script.
const scriptUrls = new Map();

const frameText = f => {
    const scriptId = f.scriptId ?? f.location?.scriptId;
    const url = f.url || scriptUrls.get(scriptId) || scriptId;
    return `${f.functionName || '<anonymous>'}  ${url}:${(f.lineNumber ?? f.location?.lineNumber) + 1}:${
        (f.columnNumber ?? f.location?.columnNumber) + 1
    }`;
};

async function profile(c) {
    await c.send('Profiler.enable');
    await c.send('Profiler.setSamplingInterval', { interval: 500 });
    await c.send('Profiler.start');
    await sleep(5000);
    const { profile } = await c.send('Profiler.stop');
    const byId = new Map(profile.nodes.map(n => [n.id, n]));
    const parent = new Map();
    for (const n of profile.nodes) for (const ch of n.children || []) parent.set(ch, n.id);
    const self = new Map();
    for (const s of profile.samples) self.set(s, (self.get(s) || 0) + 1);
    const total = profile.samples.length;
    log(`cpu profile: ${total} samples over 5 s; top self frames with their callers:`);
    [...self.entries()]
        .sort((a, b) => b[1] - a[1])
        .slice(0, 15)
        .forEach(([nodeId, count]) => {
            const chain = [];
            for (let id = nodeId, d = 0; id !== undefined && d < 25; id = parent.get(id), d++) chain.push(frameText(byId.get(id).callFrame));
            log(`  ${((100 * count) / total).toFixed(1)}%  ${chain[0]}`);
            chain.slice(1).forEach(t => log(`        <- ${t}`));
        });
}

async function pauses(c) {
    c.on(msg => {
        if (msg.method === 'Debugger.scriptParsed') scriptUrls.set(msg.params.scriptId, msg.params.url);
    });
    await c.send('Debugger.enable', {}, 300000);
    for (let i = 1; i <= 5; i++) {
        const paused = c.next('Debugger.paused', 120000);
        await c.send('Debugger.pause');
        const p = await paused;
        log(`pause ${i}: ${p.callFrames.length} frames (reason ${p.reason})`);
        p.callFrames.slice(0, 80).forEach((f, k) => log(`  #${k} ${frameText(f)}`));
        if (i === 1) {
            try {
                const r = await c.send('Debugger.evaluateOnCallFrame', {
                    callFrameId: p.callFrames[0].callFrameId,
                    expression: 'globalThis.process ? process.pid : -1'
                });
                log(`paused process pid: ${r.result.value}`);
            } catch (e) {
                log(`pid evaluation failed: ${e.message}`);
            }
        }
        await c.send('Debugger.resume');
        await sleep(1000);
    }
}

(async () => {
    const c = await connect(await target());
    try {
        await profile(c);
    } catch (e) {
        log(`profile failed: ${e.message}`);
    }
    try {
        await pauses(c);
    } catch (e) {
        log(`pause failed: ${e.message}`);
    }
    c.close();
    process.exit(0);
})().catch(e => {
    log(`capture failed: ${e.message}`);
    process.exit(1);
});
