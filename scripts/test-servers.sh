#!/usr/bin/env bash
# Starts local FTP, WebDAV, SMB and NFS servers sharing one sample tree, for
# the ignored tests in rust/wplayer-core/tests/live_sources.rs.
#
#   scripts/test-servers.sh start   # prints the env vars to export
#   scripts/test-servers.sh stop
#
# Needs python3, docker (for Samba) and cargo (installs cargo-nfs3-server).
set -euo pipefail

WORK="${WPLAYER_TEST_DIR:-${TMPDIR:-/tmp}/wplayer-test-servers}"
SHARE="$WORK/share"

start() {
    mkdir -p "$SHARE/영화/Season 2"
    python3 -I - "$SHARE/영화/sample #1.mkv" <<'EOF'
import sys
with open(sys.argv[1], "wb") as f:
    f.write(bytes(i % 251 for i in range(5 * 1024 * 1024 + 77)))
EOF

    if [[ ! -x "$WORK/venv/bin/wsgidav" ]]; then
        python3 -m venv "$WORK/venv"
        "$WORK/venv/bin/pip" install -q pyftpdlib wsgidav cheroot
    fi
    [[ -x "$WORK/bin/cargo-nfs3-server" ]] || cargo install -q --root "$WORK" cargo-nfs3-server

    nohup "$WORK/venv/bin/python" -I -m pyftpdlib -p 2121 -d "$SHARE" -u tester -P secret >"$WORK/ftp.log" 2>&1 &
    echo $! >"$WORK/ftp.pid"
    nohup "$WORK/venv/bin/wsgidav" --host 127.0.0.1 --port 8088 --root "$SHARE" --auth anonymous >"$WORK/dav.log" 2>&1 &
    echo $! >"$WORK/dav.pid"
    nohup "$WORK/bin/cargo-nfs3-server" --path "$SHARE" -i 127.0.0.1 -p 11111 -r --export-name /export >"$WORK/nfs.log" 2>&1 &
    echo $! >"$WORK/nfs.pid"
    # `share` needs tester/secret; `public` allows guests (live_sources checks how guest login fails).
    cat >"$WORK/smb.conf" <<'CONF'
[global]
    server min protocol = SMB2
    map to guest = Bad User
    guest account = nobody
[share]
    path = /storage
    valid users = tester
    writable = yes
[public]
    path = /storage
    guest ok = yes
    read only = yes
CONF
    docker run -d --rm --name wplayer-test-smb -p 1445:445 \
        -e USER=tester -e PASS=secret -v "$SHARE:/storage" -v "$WORK/smb.conf:/etc/samba/smb.conf" dockurr/samba >/dev/null

    cat <<EOF
export WPLAYER_TEST_FTP=127.0.0.1:2121
export WPLAYER_TEST_WEBDAV=127.0.0.1:8088
export WPLAYER_TEST_SMB=127.0.0.1:1445
export WPLAYER_TEST_NFS=127.0.0.1:11111
# then: (cd rust && cargo test -p wplayer-core --test live_sources -- --ignored)
EOF
}

stop() {
    local pid
    for pid in ftp dav nfs; do
        [[ -f "$WORK/$pid.pid" ]] && kill "$(cat "$WORK/$pid.pid")" 2>/dev/null || true
        rm -f "$WORK/$pid.pid"
    done
    docker stop wplayer-test-smb >/dev/null 2>&1 || true
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    *) echo "usage: $0 start|stop" >&2; exit 2 ;;
esac
