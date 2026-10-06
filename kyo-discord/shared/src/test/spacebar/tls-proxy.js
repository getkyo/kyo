const tls = require("node:tls");
const net = require("node:net");
const fs = require("node:fs");
tls.createServer({ key: fs.readFileSync("/tmp/key.pem"), cert: fs.readFileSync("/tmp/cert.pem") }, (client) => {
    const upstream = net.connect(3001, "127.0.0.1");
    client.pipe(upstream);
    upstream.pipe(client);
    client.on("error", () => upstream.destroy());
    upstream.on("error", () => client.destroy());
}).listen(8443, "0.0.0.0");
