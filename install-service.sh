#!/usr/bin/env bash
# dingsbums service installer — the install.sh in a `bb dist` folder (the
# repo's install.sh is the per-user one). Copies the bb and dingsbums.jar
# beside it into /opt/dingsbums and runs dingsbums as the systemd service
# dingsbums.service, started at boot. Needs no network; running it again
# redeploys.
#
#   sudo ./install.sh [port]   # default: the installed service's port, else 8080
#
# dingsbums listens on 127.0.0.1 only: put a reverse proxy in front of it.
#
# Env overrides (for testing): DINGSBUMS_PREFIX   (default /opt/dingsbums),
#                              DINGSBUMS_UNIT_DIR (default /etc/systemd/system),
#                              SYSTEMCTL          (default systemctl)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
PREFIX="${DINGSBUMS_PREFIX:-/opt/dingsbums}"
UNIT_DIR="${DINGSBUMS_UNIT_DIR:-/etc/systemd/system}"
SYSTEMCTL="${SYSTEMCTL:-systemctl}"
UNIT="$UNIT_DIR/dingsbums.service"

die() { echo "install: $*" >&2; exit 1; }

installed_port() {
  sed -nE 's/^ExecStart=.* -m dingsbums\.cli ([0-9]+)$/\1/p' "$UNIT" 2>/dev/null | head -n1
}

# copy, then rename: the running service keeps its open files
put() { # src dest mode
  cp "$1" "$2.new"
  chmod "$3" "$2.new"
  mv -f "$2.new" "$2"
}

main() {
  local port version
  [ $# -le 1 ] || die "usage: install.sh [port]"
  port="${1:-$(installed_port)}"
  port="${port:-8080}"
  [[ "$port" =~ ^[0-9]{1,5}$ ]] && [ "$((10#$port))" -ge 1 ] && [ "$((10#$port))" -le 65535 ] \
    || die "not a port: $port"
  port=$((10#$port))
  [ -x "$HERE/bb" ] && [ -f "$HERE/dingsbums.jar" ] \
    || die "bb and dingsbums.jar must be beside install.sh (build the folder with bb dist)"
  [ "$(id -u)" -eq 0 ] || [ -n "${DINGSBUMS_PREFIX:-}" ] || die "run it as root: sudo $0 $*"
  version=$("$HERE/bb" -cp "$HERE/dingsbums.jar" -m dingsbums.cli --version) \
    || die "$HERE/bb could not run $HERE/dingsbums.jar"

  mkdir -p "$PREFIX" "$UNIT_DIR"
  put "$HERE/bb" "$PREFIX/bb" 755
  put "$HERE/dingsbums.jar" "$PREFIX/dingsbums.jar" 644
  cat >"$UNIT.new" <<UNIT
# written by dingsbums' install.sh; running it again overwrites this
[Unit]
Description=dingsbums whiteboard
After=network.target

[Service]
ExecStart=$PREFIX/bb -cp $PREFIX/dingsbums.jar -m dingsbums.cli $port
DynamicUser=yes
Restart=always

[Install]
WantedBy=multi-user.target
UNIT
  mv -f "$UNIT.new" "$UNIT"

  "$SYSTEMCTL" daemon-reload
  "$SYSTEMCTL" enable dingsbums.service
  "$SYSTEMCTL" restart dingsbums.service
  echo "installed $version as dingsbums.service on http://127.0.0.1:$port"
  echo "  files: $PREFIX"
  echo "  unit:  $UNIT"
}

main "$@"
