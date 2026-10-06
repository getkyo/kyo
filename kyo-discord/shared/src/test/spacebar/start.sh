set -e
export PGDATA=/tmp/pgdata
mkdir -p "$PGDATA" && chown postgres "$PGDATA"
gosu postgres initdb -D "$PGDATA" --auth=trust -U spacebar >/dev/null
gosu postgres pg_ctl -D "$PGDATA" -o "-c listen_addresses=127.0.0.1 -c fsync=off" -w start >/dev/null
gosu postgres createdb -h 127.0.0.1 -U spacebar spacebar
printf '%s' "$SPACEBAR_CONFIG_JSON" > /tmp/config.json
printf '%s' "$TLS_CERT" > /tmp/cert.pem
printf '%s' "$TLS_KEY" > /tmp/key.pem
node /opt/tls-proxy.js &
export DATABASE=postgres://spacebar@127.0.0.1:5432/spacebar CONFIG_PATH=/tmp/config.json PORT=3001
exec node --enable-source-maps dist/bundle/start.js
