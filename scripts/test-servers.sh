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
    # Older servers: one without MLSD (like vsftpd), one naming files in CP949.
    local here
    here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    nohup "$WORK/venv/bin/python" -I "$here/ftp-test-server.py" 2122 "$SHARE" --no-mlsd >"$WORK/ftp-list.log" 2>&1 &
    echo $! >"$WORK/ftp-list.pid"
    nohup "$WORK/venv/bin/python" -I "$here/ftp-test-server.py" 2123 "$SHARE" --no-mlsd --encoding cp949 >"$WORK/ftp-cp949.log" 2>&1 &
    echo $! >"$WORK/ftp-cp949.pid"
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
    # A server that takes reads of at most 64 KiB, like SMB 2.0.2 NAS and routers.
    { printf '[global]\n    smb2 max read = 65536\n'; sed 1d "$WORK/smb.conf"; } >"$WORK/smb-small.conf"
    docker run -d --rm --name wplayer-test-smb-small -p 1446:445 \
        -e USER=tester -e PASS=secret -v "$SHARE:/storage" -v "$WORK/smb-small.conf:/etc/samba/smb.conf" dockurr/samba >/dev/null

    cat <<EOF
export WPLAYER_TEST_FTP=127.0.0.1:2121
export WPLAYER_TEST_FTP_LIST=127.0.0.1:2122
export WPLAYER_TEST_FTP_CP949=127.0.0.1:2123
export WPLAYER_TEST_WEBDAV=127.0.0.1:8088
export WPLAYER_TEST_SMB=127.0.0.1:1445
export WPLAYER_TEST_SMB_SMALL_READS=127.0.0.1:1446
export WPLAYER_TEST_NFS=127.0.0.1:11111
# then: (cd rust && cargo test -p wplayer-core --test live_sources -- --ignored)
EOF
}

stop() {
    local pid
    for pid in ftp ftp-list ftp-cp949 dav nfs; do
        [[ -f "$WORK/$pid.pid" ]] && kill "$(cat "$WORK/$pid.pid")" 2>/dev/null || true
        rm -f "$WORK/$pid.pid"
    done
    docker stop wplayer-test-smb wplayer-test-smb-small >/dev/null 2>&1 || true
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    *) echo "usage: $0 start|stop" >&2; exit 2 ;;
esac
