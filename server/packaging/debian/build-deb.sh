#!/bin/sh
# Packs the PyInstaller build (build/dist/netadmin-agent) into a .deb:
#   packaging/debian/build-deb.sh 0.1.0.57   ->  build/netadmin-agent_0.1.0.57_amd64.deb
set -eu
cd "$(dirname "$0")/../.."
VERSION="$1"
SRC=build/dist/netadmin-agent
ROOT=build/deb
rm -rf "$ROOT"
mkdir -p "$ROOT/DEBIAN" "$ROOT/opt" "$ROOT/lib/systemd/system" "$ROOT/usr/bin"
cp -a "$SRC" "$ROOT/opt/netadmin"
ln -s /opt/netadmin/netadmin-agent "$ROOT/usr/bin/netadmin-agent"
install -m 644 packaging/debian/netadmin.service "$ROOT/lib/systemd/system/netadmin.service"
for f in postinst prerm postrm; do install -m 755 "packaging/debian/$f" "$ROOT/DEBIAN/$f"; done
SIZE=$(du -sk "$ROOT" | cut -f1)
cat > "$ROOT/DEBIAN/control" <<CONTROL
Package: netadmin-agent
Version: $VERSION
Architecture: amd64
Maintainer: ItManager <noreply@github.com>
Installed-Size: $SIZE
Depends: postgresql (>= 13), adduser, libc6 (>= 2.36), iputils-ping
Recommends: iputils-ping
Section: net
Priority: optional
Homepage: https://github.com/iamlex92/ItManager
Description: NetAdmin DCIM server
 Inventory, topology and switch management API for the NetAdmin Android app.
 Runs as the systemd service "netadmin" with the system PostgreSQL. Safe defaults:
 emulated switches and monitoring, no writes to network equipment.
CONTROL
dpkg-deb --root-owner-group --build "$ROOT" "build/netadmin-agent_${VERSION}_amd64.deb"
