const assert = require('node:assert/strict');
const { Socket } = require('node:net');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const koffi = require('koffi');
koffi.config({ resident_async_pools: 1, max_async_calls: 1 });
const library = koffi.load(process.env.KYO_PROCESS_LIBRARY);
const init = library.func('void *kyo_process_launch_init(const char *, _Out_ int *)');
const executable = library.func('const char *kyo_process_launch_executable(void *)');
const endpoint = library.func('const char *kyo_process_launch_endpoint(void *)');
const acquire = library.func('int kyo_process_launch_await(void *, int)');
const cancel = library.func('int kyo_process_launch_cancel(void *)');
const controlFd = library.func('int kyo_process_launch_take_control(void *)');
const streamFd = library.func('int kyo_process_launch_take_stream(void *, int)');
const release = library.func('int kyo_process_launch_release(void *)');
const asyncCall = (fn, ...args) => new Promise((resolve, reject) => {
    fn.async(...args, (error, result) => error ? reject(error) : resolve(result));
});

function events(socket) {
    let buffer = Buffer.alloc(0);
    const queue = [], readers = [];
    let failure;
    function fail(error) {
        failure = error;
        for (const reader of readers.splice(0)) reader.reject(error);
    }
    socket.on('error', fail);
    socket.on('end', () => fail(new Error('Unexpected supervisor EOF')));
    socket.on('data', bytes => {
        buffer = Buffer.concat([buffer, bytes]);
        while (buffer.length >= 8) {
            const event = [buffer.readInt32LE(0), buffer.readInt32LE(4)];
            buffer = buffer.subarray(8);
            if (readers.length) readers.shift().resolve(event);
            else queue.push(event);
        }
    });
    return () => queue.length ? Promise.resolve(queue.shift()) : failure ? Promise.reject(failure) :
        new Promise((resolve, reject) => readers.push({ resolve, reject }));
}

async function start() {
    const error = [0];
    const owner = await asyncCall(init, '', error);
    assert(owner);
    let ownsLaunch = true;
    async function releaseLaunch() {
        if (ownsLaunch) {
            ownsLaunch = false;
            assert.equal(await asyncCall(release, owner), 0);
        }
    }
    const child = spawn(executable(owner), [endpoint(owner), '', '7', 'sleep', '100000'], { stdio: 'ignore' });
    const childExit = new Promise(resolve => child.once('close', (code, signal) => resolve([code, signal])));
    let acquiring = true;
    let spawnError;
    child.on('error', error => {
        spawnError = error;
        if (acquiring) cancel(owner);
    });
    const streams = [];
    let control;
    try {
        const pid = await asyncCall(acquire, owner, 7);
        acquiring = false;
        if (spawnError) throw spawnError;
        assert(pid > 0);
        assert.notEqual(pid, child.pid);
        control = new Socket({ fd: controlFd(owner), readable: true, writable: true });
        const next = events(control);
        for (let index = 0; index < 3; index++) {
            const socket = new Socket({
                fd: streamFd(owner, index), readable: index !== 0, writable: index === 0,
                ...(index === 0 ? {} : { onread: {
                    buffer: Buffer.alloc(8192),
                    callback() { throw new Error('An idle command produced output'); },
                } }),
            });
            streams.push({ socket, closed: once(socket, 'close') });
        }
        await releaseLaunch();
        return async () => {
            try {
                control.write(Buffer.from([3]));
                let event;
                do {
                    event = await next();
                    assert.notEqual(event[0], 5);
                } while (event[0] !== 6);
                assert.deepEqual(event, [6, 0]);
            } finally {
                control.destroy();
                for (const stream of streams) stream.socket.destroy();
                await Promise.all(streams.map(stream => stream.closed));
                assert.deepEqual(await childExit, [0, null]);
            }
        };
    } catch (failure) {
        acquiring = false;
        if (control) control.destroy();
        for (const stream of streams) stream.socket.destroy();
        await releaseLaunch();
        await childExit;
        throw failure;
    }
}

async function main() {
    const cleanup = [];
    try {
        for (let index = 0; index < 8; index++) cleanup.push(await start());
        const error = [0];
        const owner = await asyncCall(init, '', error);
        try {
            const pending = asyncCall(acquire, owner, 7);
            assert.equal(cancel(owner), 0);
            // Join the producer before its owner is freed.
            assert.equal(await pending, process.platform === 'darwin' ? -89 : -125);
        } finally {
            assert.equal(await asyncCall(release, owner), 0);
        }
        const ordinary = spawn('true', [], { stdio: 'ignore' });
        assert.deepEqual(await once(ordinary, 'exit'), [0, null]);
    } finally {
        const failures = [];
        for (const close of cleanup.reverse()) {
            try { await close(); } catch (error) { failures.push(error); }
        }
        if (failures.length) throw new AggregateError(failures, 'Supervisor cleanup failed');
    }
    console.log('8 owned process groups and 16 idle output pipes use libuv; one foreign worker acquires and cancels; all helpers are reaped');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
