// Preloaded into every Node process through NODE_OPTIONS=--require. The interval runs on the event loop, so a slice that never returns
// stops the writes, and the watcher can tell which process froze: the scheduler's own status file is one path shared by every fork.
'use strict';
const fs = require('fs');
const path = require('path');
const dir = process.env.KYO_HANG_DIR;
if (dir) {
    const file = path.join(dir, `hb.${process.pid}`);
    const args = process.argv.slice(1).join(' ').slice(0, 300);
    const write = () => {
        try {
            fs.writeFileSync(file, `${Math.floor(Date.now() / 1000)} ${args}\n`);
        } catch (_) {}
    };
    write();
    setInterval(write, 5000).unref();
}
