# NetAdmin server API

Interactive docs: `http://<server>:8000/docs` (Swagger UI) and `/openapi.json`.

All `/api/v1` routes except `GET /api/v1/health` need `Authorization: Bearer <API_TOKEN>`.
The WebSocket takes the token as `?token=<API_TOKEN>` (or the same header).
JSON is snake_case. Coordinates `x`/`y` are pixels on the floor plan image.
Clients should ignore unknown fields.

## Read

| Method | Path | Returns |
|---|---|---|
| GET | `/api/v1/health` | `{status, version, hal_mode: "emulator"\|"real", device_writes_enabled, monitor_mode: "emulator"\|"real"}` (no auth) |
| GET | `/api/v1/floors` | `[{id, building_name, floor_number, name, plan_image_url, scale_pixels_per_meter, width_px, height_px, created_at}]` |
| GET | `/api/v1/floors/{id}/plan-image` | plan image uploaded for the floor (`plan_image_url` points here) |
| GET | `/api/v1/devices?floor_id=&type=&q=` | `[{id, name, type, vendor, model, ip, mac, floor_id, x, y, is_online, last_seen_at, vlan_id, is_active, nfc_tag_uid, snmp_community, metadata}]` |
| GET | `/api/v1/devices/{id}` | one device |
| GET | `/api/v1/devices/by-nfc/{uid}` | device bound to an NFC tag |
| GET | `/api/v1/search?q=` | devices matching name, IP, MAC or NFC UID |
| GET | `/api/v1/devices/{id}/ports` | `[{id, device_id, number, name, is_uplink, mode, access_vlan, tagged_vlans, is_admin_up, is_oper_up, speed_mbps, poe_enabled, connected_mac, connected_device_name}]` |
| GET | `/api/v1/ports/{id}/trace` | `{hops: [{device_id, device_name, device_type, port_id, port_name}]}`, e.g. PC → socket → patch panel → switch |
| GET | `/api/v1/vlans` | `[{vlan_id, name, subnet_cidr, color_hex, description}]` |
| GET | `/api/v1/connections?device_id=` | `[{id, port_a_id, port_b_id, device_a_id, device_b_id, cable_type, length_meters, label, notes, waypoints}]` |
| GET | `/api/v1/printers` | `[{id, name, ip, model, is_online, total_pages, consumables: [{name, color_hex, kind, level_percent}], low_supply, polled_at}]` |
| GET | `/api/v1/printers/{id}` | one printer |
| GET | `/api/v1/alerts?is_resolved=&severity=&device_id=` | `[{id, device_id, port_id, severity, alert_type, message, is_resolved, created_at, resolved_at}]` |
| GET | `/api/v1/devices/{id}/configs` | stored config snapshots (metadata) |
| GET | `/api/v1/devices/{id}/configs/diff?base_id=` | `{base_id, base_hash, current_hash, changed, diff}`: stored vs live running-config (reads the switch) |

`type` is one of `SWITCH, WORKSTATION, SERVER, PRINTER, SOCKET, PATCH_PANEL, CAMERA, ACCESS_POINT`.
`vlan_id` of an end device is the access VLAN of the switch port it is cabled to; for a switch it is the management VLAN.

`kind` is `TONER | WASTE | DRUM | OTHER`. `level_percent` always means "remaining":
toner left, or free space left in a waste box, so below 10 is always a warning
(`low_supply`). `POST /api/v1/printers/{id}/refresh` polls now (SNMP GET only) and
returns the printer; 504 `printer_unreachable` if it does not answer.

Alert types: `LINK_DOWN` (poller or SNMP trap), `HOST_DOWN` (switches, servers,
printers, cameras, access points; not PCs), `TONER_LOW`. They resolve themselves
when the condition clears.

## NFC

UIDs are stored as uppercase hex bytes joined by colons (`04:A2:3B:1C:5D:80:01`);
any case and `:`/`-`/no separators are accepted and normalized.

| Method | Path | |
|---|---|---|
| POST | `/api/v1/nfc/resolve` | `{"payload": {app, v, id, type, name}}` and/or `{"uid": "..."}` → device. Payload `id` wins; 422 `foreign_tag` if `app` is not `netadmin`; 404 if nothing matches |
| GET | `/api/v1/devices/{id}/nfc-payload` | `{"app": "netadmin", "v": 1, id, type, name}` to write to the tag |
| PUT | `/api/v1/devices/{id}/nfc` | `{"uid": "...", "force": false}` → device; 409 `tag_bound_elsewhere` (with `device_id`, `device_name`) unless `force`, which moves the tag |
| DELETE | `/api/v1/devices/{id}/nfc` | 204 |

## Inventory (database only, never touches equipment)

Device types: `SWITCH`, `ROUTER`, `FIREWALL`, `ACCESS_POINT`, `SERVER`, `VIRTUAL`, `WORKSTATION`, `PRINTER`, `CAMERA`, `PHONE`,
`UPS`, `PATCH_PANEL`, `SOCKET`, `UNKNOWN`. `VIRTUAL` is a virtual machine or other virtual device: it has no cables,
so apps leave it off the physical layer (L1); `host_id` names the server it runs on (deleting the host clears it).
The scanner proposes `VIRTUAL` from hypervisor MAC prefixes (VMware, Hyper-V, KVM/QEMU, VirtualBox, Xen, Proxmox).

`POST/PATCH/DELETE` on `/api/v1/floors`, `/api/v1/vlans`, `/api/v1/devices`, `/api/v1/connections`;
`POST /api/v1/ports`, `DELETE /api/v1/ports/{id}`; `POST /api/v1/alerts`, `POST /api/v1/alerts/{id}/resolve`.

Map editor (the app's "Редактор карты"), also inventory only:

| Method | Path | Notes |
|---|---|---|
| PUT | `/api/v1/floors/{id}/plan-image?width=&height=` | Raw PNG, JPEG or SVG body, up to 15 MB (`plan_type`, `plan_too_big`, `empty_plan`). Sets `plan_image_url`; the first upload places the plan at the floor's top-left corner. |
| GET | `/api/v1/floors/{id}/plan-image` | The uploaded plan, with the token. |
| DELETE | `/api/v1/floors/{id}/plan-image` | Removes the plan and its placement. |
| PATCH | `/api/v1/floors/{id}` | `plan_x`, `plan_y`, `plan_w`, `plan_h`: where the plan lies, in map pixels. `drawings`: `[{color: "#RRGGBB", width, points: [x, y, ...]}]`, up to 500 lines. `zones`: `[{id, name, is_room, color, points: [x, y, ...]}]`, up to 200 outlines; a device whose `x, y` lies inside a room zone belongs to that room (the app works this out). |
| POST/PATCH | `/api/v1/floors` | `name` (optional, "" clears it) names a floor; otherwise apps show "Этаж N". `floor_number` sets the order, bottom to top, which is also the order `GET /floors` returns. |
| DELETE | `/api/v1/floors/{id}` | Only an empty floor: with devices on it the answer is 409 `floor_not_empty`. |
| PATCH | `/api/v1/connections/{id}` | `waypoints`: the cable's bends from end A to end B, `[{x, y, floor_id?}]`, up to 200. A bend without `floor_id` lies on end A's floor; a cable between floors lists its bends on each floor and the apps draw a riser where it leaves one. `[]` makes it straight again. |
| POST | `/api/v1/devices/bulk` | `{ids: [...], set: {type?, floor_id?, vendor?, model?, is_active?, host_id?}}`: one change on up to 1000 devices; fields left out stay. `{changed, missing}`; 422 `nothing_to_change`, 404 for an unknown floor or host. |
| POST | `/api/v1/devices/bulk-delete` | `{ids: [...]}`: removes devices with their ports and cables. `{changed, missing}` |
| POST | `/api/v1/connections/link` | `{device_a_id, device_b_id, cable_type}`: a cable between two devices; the server picks a free port on each or adds one (`same_device`, `already_linked`, `no_free_port`). |

## Talking to switches

Read-only on the device:

- `POST /api/v1/devices/{id}/refresh` re-reads port state now, returns ports.
- `POST /api/v1/devices/{id}/configs/backup` reads running-config and stores it.
- `POST /api/v1/ports/{id}/config/preview` with `{mode?, access_vlan?, tagged_vlans?, is_admin_up?}` returns `{commands}` without contacting the device.

Diagnostics run from the switch (exec mode, not write-guarded, audited):

- `POST /api/v1/devices/{id}/ping` `{"target": "10.10.10.22", "count": 5}` → `{command, output, success}`
  (`count` 1..20, default 5)
- `POST /api/v1/devices/{id}/traceroute` `{"target": "10.10.10.22"}` → `{command, output, success}`

`target` must be an IP address (hostnames are rejected with 422). In emulator
mode the result follows the target's `is_online` in the database; unknown IPs fail.

State-changing (guarded):

- `POST /api/v1/ports/{id}/config` same body plus `"confirm": true` → `{port, commands}`
- `POST /api/v1/ports/{id}/bounce` `{"confirm": true}` → shutdown, 2 s, no shutdown
- `POST /api/v1/devices/{id}/write-memory` `{"confirm": true}`

Uplink ports also need `"confirm_uplink": true`.

| Status | `detail.code` | Meaning |
|---|---|---|
| 400 | `confirmation_required` | `confirm` missing or false |
| 409 | `uplink_port_protected` | uplink port without `confirm_uplink` |
| 403 | `device_writes_disabled` | `HAL_MODE=real` and `ALLOW_DEVICE_WRITES` is not `true` |
| 422 | `unknown_vlan`, `empty_change`, `not_a_managed_switch` | bad request |
| 501 | `not_implemented` | real SSH driver not written yet |

Every write attempt, including blocked ones, is recorded in `script_audit_logs`.

## Custom scripts (web console)

`GET/POST /api/v1/scripts`, `GET/PATCH/DELETE /api/v1/scripts/{id}`:
`{title, description, shell: powershell|bash|cmd, body, params: [{name, label, required, placeholder}], critical}`.
`{{name}}` in the body is replaced by the parameter's value (`unknown_placeholder` if no such parameter).
Each script is listed by `GET /runbooks` as `custom-<id>` in the category «Свои скрипты» and run with
`POST /runbooks/custom-<id>/run`. Runs are always emulated: the output is the script with parameters filled in.

Writes to floors, devices, ports, cables, VLANs, scripts and scan imports send
`{"type": "inventory_changed"}` to WebSocket clients subscribed to `*`, so open apps reload.

The web console itself is `GET /admin` (no token for the page; every call it makes needs the token).

## Runbooks and audit

Runbooks run against a built-in emulated environment (AD accounts, NTFS shares,
databases, VMs, services). Nothing reaches a real directory or server yet;
`/health` reports `"runbook_mode": "emulator"`.

| Method | Path | Notes |
|---|---|---|
| GET | `/runbooks` | catalog: `id`, `category`, `title`, `description`, `critical`, `params[]` (`name`, `label`, `kind` `text`/`choice`, `required`, `choices[]`, `placeholder`) |
| POST | `/runbooks/{id}/run` | body `{"params": {...}, "confirm": true, "auth_method": "biometric", "operator": "Pixel 10a"}` |
| GET | `/audit?kind=all\|runbook\|switch\|network\|security&limit=100&offset=0` | runbook runs, switch write attempts and network scans, newest first |

Run response: `audit_id`, `runbook_id`, `exit_code`, `stdout`, `stderr`, `secret`,
`started_at`, `duration_ms`, `simulated`. `secret` carries a temporary password
(password reset only): it is returned once and never written to the audit log.

| Status | `detail.code` | Meaning |
|---|---|---|
| 400 | `confirmation_required` | critical runbook without `"confirm": true` (the app confirms with biometrics first) |
| 404 | `not_found` | unknown runbook |
| 422 | `invalid_params` | missing, unknown or out-of-list argument; `message` is in Russian for the app |

A failing run (unknown account, existing snapshot name) is still `200` with a
non-zero `exit_code` and `stderr`. Every run is audited with initiator
(`operator · client IP`), time, arguments, auth method, exit code, stdout,
stderr and duration.

## Network discovery

Finds hosts in the given subnets, tells what they are and finds the links between
them. Read-only towards equipment, and SNMP is not required:

1. Every address: one ICMP echo and a TCP connect to 22, 23, 80, 135, 443, 445, 554,
   3389, 9100. A host that drops ICMP (Windows firewall) is still found by an open
   or refused port; one the server's ARP cache resolved is found too.
2. Hosts that are up: more ports (21, 53, 88, 139, 389, 515, 631, 902, 1433, 3306,
   5432, 5900, 5985, 8000, 8080, 8291, 8443, 37777), reverse DNS, NetBIOS name, the
   SSH and Telnet banners, `GET /` over HTTP(S) for the page title, `Server` header
   and the device certificate, and the MAC vendor from the IEEE OUI list.
3. SNMP GET/GETNEXT when the community fits (system group, ifName, LLDP, CDP, ARP,
   bridge and Q-bridge MAC tables): exact names and models, and the links.

The type (`SWITCH`, `PRINTER`, `CAMERA`, `ACCESS_POINT`, `SERVER`, `WORKSTATION`)
comes from SNMP when it answers, otherwise from the ports, vendor and banners. No
logins, no writes, no SNMP SET. A scan only starts from an explicit request with
explicit ranges. `DISCOVERY_MODE=real` is the default for that reason; with
`DISCOVERY_MODE=emulator` the scan is answered from the inventory plus a few devices
it does not know yet (a new switch, a printer, a camera, a PC without SNMP or ping)
and nothing leaves the server.

| Method | Path | Notes |
|---|---|---|
| POST | `/discovery/scans` | `{"subnets": ["10.99.0.0/24"], "community": "public", "operator": "Pixel 10a"}` → `202`, scan in `running` state |
| GET | `/discovery/scans/{id}` | poll until `status` is `done` or `failed` |
| GET | `/discovery/scans/latest` | the last scan since the server started (scans live in memory, the last 5 are kept) |
| POST | `/discovery/scans/{id}/import` | `{"ips": [...], "links": true, "operator": "..."}`: add devices and their links to the inventory |

Limits: private IPv4 only, each range at most a /22, up to 8 ranges and 2048
addresses per scan, one scan at a time. The community is used for that scan only
and is never stored or logged (`SNMP_VERSION=3` uses the `SNMP_V3_*` user instead).

Scan: `id`, `status`, `mode` (`emulator`/`real`), `subnets`, `started_at`,
`finished_at`, `total`, `probed`, `devices[]`, `links[]`, `error`, `warnings[]`
(what limited the scan, in plain Russian: no `ping` on the server, the server is
not in a scanned subnet so MACs are unknown, nobody answered SNMP, nobody answered
at all, a firewall refuses for every address). Devices, links and warnings are
empty while the scan runs. `probed` reaches `total` when every address has been
checked; identifying the hosts that are up takes a few seconds more.

- device: `ip`, `name` (sysName, else NetBIOS or DNS name), `mac`, `vendor` (from
  sysObjectID or the MAC), `model`, `type` (`SWITCH`, `PRINTER`, `CAMERA`, ... or
  `UNKNOWN`), `description` (sysDescr, or the evidence: ports, banners, page title),
  `snmp`, `seen_by` (`ping`, `tcp`: ICMP blocked but a port answered, `arp`),
  `open_ports`, `hostname` (reverse DNS), `inventory_id` / `inventory_name` when the
  IP is already in the inventory.
- link: `a` / `b` (`name`, `port`, `ip`, `inventory_id`), `source` (`lldp`, `cdp`,
  or `fdb` for a host whose MAC was learned on a switch edge port; the host's `port`
  is empty), `known` when the inventory already has a cable between the two devices.

Import writes only to the database: new devices go on the first floor next to a
linked device that has a position (or in a row along the bottom of the plan),
ports are created by name, and each link becomes a cable with
`notes: "auto: lldp, ..."`. Links to devices that are already cabled (also through
sockets and patch panels) are left alone; links to busy ports are reported in
`skipped`. Response: `created[]` (`id`, `name`, `ip`), `links[]`, `skipped[]`.

| Status | `detail.code` | Meaning |
|---|---|---|
| 409 | `scan_running` | another scan is running |
| 409 | `scan_not_done` | import before the scan finished |
| 422 | `invalid_subnets` | not a subnet, public, too large or too many; `message` in Russian |

Scans and imports are audited as `network_scan` / `discovery_import`
(`GET /audit?kind=network`).

## Credentials

Logins for equipment and services (`kind`: `ssh`, `snmp_v3`, `winrm`, `other`). The secret
is write-only: stored encrypted with `SECRET_KEY`, never returned. Nothing uses them to log in
yet; a device points at one with `ssh_credential_id` (PATCH `/devices/{id}`) for the real
switch driver.

| Method | Path | Notes |
|---|---|---|
| GET | `/credentials` | `id`, `name`, `kind`, `username`, `has_secret`, `notes`, `devices` (how many point at it), `created_at`, `updated_at` |
| POST | `/credentials` | `{"name", "kind", "username", "secret", "notes", "operator"}` → 201; 409 `name_taken` |
| PATCH | `/credentials/{id}` | any of `name`, `username`, `secret`, `notes` |
| DELETE | `/credentials/{id}?operator=...` | devices pointing at it get `ssh_credential_id: null` |

Each change is audited as `credential_create` / `credential_update` / `credential_delete`
(`GET /audit?kind=security`) with name, kind and username only.

Devices: `snmp_community` is accepted on create/update but no longer returned; `has_snmp_community`
says whether one is set. `/health` reports `secrets_encrypted`.

## History

| Method | Path | |
|---|---|---|
| GET | `/devices/{id}/history?limit=100` | Port changes (`port_config`, `port_bounce`, `write_memory`) of this switch with `kind` `change`, `blocked` (refused by `ALLOW_DEVICE_WRITES`) or `failed`, plus stored config copies (`config_backup`, with whether the config changed since the previous copy). Each entry has `at`, `title`, a readable `summary` such as `gi1/0/2: VLAN 10 → 20`, `actor` and the `commands` sent. Newest first. |

Port writes record the port's previous mode, VLANs and admin state in the audit parameters (`before`) and
the device id, so the history survives a rename. VLAN dictionary changes (`POST/PATCH/DELETE /vlans`) are
audited as `vlan_create`, `vlan_update` (with `changed: {field: [old, new]}`) and `vlan_delete`.

## Traffic (port utilization)

| Method | Path | |
|---|---|---|
| GET | `/devices/{id}/traffic?hours=24` | Per port: `avg_in_bps`, `avg_out_bps`, `peak_in_bps`, `peak_out_bps`, `peak_utilization` (percent of port speed, busiest direction), `last_in_bps`, `last_out_bps`. Ports without samples are left out. |
| GET | `/ports/{id}/traffic?hours=24&points=144` | Chart series: samples averaged into at most `points` buckets, `[{"at", "in_bps", "out_bps"}]`, plus `peak_utilization`. |

Samples are taken every `TRAFFIC_INTERVAL_SECONDS` (300) and kept `TRAFFIC_KEEP_DAYS` (7). With
`MONITOR_MODE=real` the server reads `ifHCInOctets`/`ifHCOutOctets` over SNMP (GET/GETNEXT only) with the
device's community; ports are matched by ifIndex, or by ifName when the port has none. The emulator
generates a working day (Moscow time) and fills the last 24 hours on first start.

## Notifications

| Method | Path | |
|---|---|---|
| POST | `/notifications/telegram/test` | Sends a test message to `TELEGRAM_CHAT_ID`. 409 `telegram_disabled` when the token or chat id is missing, 502 `telegram_failed` with the reason (bad token, bot not in the chat, no network). |

Alerts (every source: port poller, traps, heartbeat, printers, `POST /alerts`) are sent to the chat
when they are at least `TELEGRAM_MIN_SEVERITY` and still open after `TELEGRAM_DELAY_SECONDS`; a "resolved"
message follows for those that were sent. At most `TELEGRAM_MAX_PER_MINUTE` messages, the rest are counted
in the next one. `/health` reports `telegram_alerts`. The bot token is never logged.

## WebSocket `/api/v1/ws?token=...`

Client sends:

```json
{"action": "subscribe", "device_id": "<uuid>"}      // or "*" for everything
{"action": "unsubscribe", "device_id": "<uuid>"}
```

Server sends:

```json
{"type": "snapshot", "device_id": "...", "is_online": true, "ports": [/* port objects */]}
{"type": "port_status", "device_id": "...", "port": {/* port object */}}
{"type": "device_status", "device_id": "...", "is_online": false}
{"type": "alert", "alert": {/* alert object, sent when created and when resolved */}}
{"type": "printer_status", "device_id": "...", "printer": {/* printer object */}}
{"type": "error", "message": "..."}
```

A bad token closes the socket with code 1008.

## Comments and racks

Devices have `comment` (free text, up to 4000 characters). A `RACK` device is a cabinet with `rack_units` (default 42).
A device is mounted by `rack_id` (the rack), `rack_u` (lowest unit, 1 is the bottom) and `rack_h` (height in units, default 1).
The server answers 422 when the rack is not a `RACK` device or the device does not fit, and 409 when the units are taken
or a rack would become lower than its highest mounted device. Deleting a rack leaves its devices unmounted.

## Peripherals

Equipment without a network port that belongs to a workplace. Inventory only.

| Method | Path | Body / returns |
|---|---|---|
| GET | `/api/v1/peripherals?device_id=&unassigned=true&q=` | `[{id, kind, vendor, model, specs, serial_number, inventory_number, comment, device_id, device_name, created_at, updated_at}]` |
| POST | `/api/v1/peripherals` | `{kind, vendor?, model?, specs?, serial_number?, inventory_number?, comment?, device_id?}` → 201 |
| GET | `/api/v1/peripherals/{id}` | one peripheral |
| PATCH | `/api/v1/peripherals/{id}` | any of the fields; `device_id: null` puts it back in stock |
| DELETE | `/api/v1/peripherals/{id}` | 204 |

`kind`: `MONITOR, KEYBOARD, MOUSE, DOCK, HEADSET, WEBCAM, SPEAKERS, SCANNER, PRINTER, PROJECTOR, UPS, OTHER`.
Blank strings are stored as null. A peripheral cannot go to a rack, a socket or a patch panel (422 `bad_device`).
Deleting the device keeps its peripherals, in stock.

## Topology

`GET /api/v1/topology` → `{nodes: [{id, name, type, vendor, model, ip, is_online, floor_id, host_id, degree}],
links: [{a_id, b_id, kind: "CABLE"|"VIRTUAL", a_port, b_port, via: [names], cable_types, is_up, speed_mbps}]}`.
Nodes are active devices (no sockets, patch panels or racks). A `CABLE` link is a chain of cables traced through
sockets and patch panels; `via` names what it passes. `is_up` and `speed_mbps` come from the switch side
(null when neither end is a switch). `VIRTUAL` links a virtual machine (`b_id`) to its host (`a_id`).

## Faceplates

A drawing of a device's front panel (SVG) and where its ports are on it, as fractions of the drawing
(`x`, `y` top left, `w`, `h`, all 0..1).

| Method | Path | Body / returns |
|---|---|---|
| GET | `/api/v1/devices/{id}/faceplate` | `{device_id, width, height, ports: [{port_id, port_name, port_number, x, y, w, h}], updated_at}`; 404 when none |
| GET | `/api/v1/devices/{id}/faceplate/svg` | the SVG, served with a sandbox CSP |
| PUT | `/api/v1/devices/{id}/faceplate` | raw SVG body (UTF-8, up to 2 MB, viewBox or width/height required, no DOCTYPE); marks are kept |
| PUT | `/api/v1/devices/{id}/faceplate/ports` | `{ports: [{port_id, x, y, w, h}]}` replaces the marks; ports of this device only, each once |
| POST | `/api/v1/devices/{id}/faceplate/copy` | `{from_device_id}`: takes the drawing and marks of another device, ports matched by name, then number |
| DELETE | `/api/v1/devices/{id}/faceplate` | 204 |

Errors: 415 `bad_svg`, 413 `svg_too_big`, 422 `bad_port` / `duplicate_port`.

## Config import

`POST /api/v1/devices/{id}/config-import` `{text, apply}` reads a pasted running-config (Cisco IOS, Eltex MES) or
`show vlan brief` into the inventory. Response: `{kind, hostname, applied, ports: [{port, matched, changes}],
new_ports, new_vlans, changed_ports}`. `matched: false` is a port that will be (or was) added. On apply, VLANs from
the text the server does not have are added (name, subnet from the VLAN interface's address), then the ports.
A placeholder port `portN` drawn on the map takes the real name of the N-th interface. Nothing is sent to the switch.
`GET /api/v1/devices` also returns `switch_id`, `switch_port_id`, `switch_port_name`: the switch port a device is
cabled to. A VLAN's `subnet_cidr` may be null.

## Map marks

Floors carry `annotations: [{id, kind: "BADGE"|"TEXT"|"ARROW", x, y, x2, y2, text, color, size}]`, set with
`PATCH /api/v1/floors/{id}` like `zones`. BADGE is a room number in a circle, TEXT a caption, ARROW points from
x, y to x2, y2 (both required). Map pixels; `size` 0.3–5, 1 is normal. Up to 500 per floor.


## Domain computers (the old Connect-DomainPC.ps1)

Everything the admin script did, run by the server over WinRM with the login from `RUNBOOK_CREDENTIAL`.
`RUNBOOK_MODE` switches it like runbooks: `real` talks to the computers, `emulator` answers with made-up data
and marks it `"simulated": true`.

| Method | Path | |
|---|---|---|
| GET | `/pcs/status` | `mode`, `writes_enabled`, `login_configured`, `ad_configured` |
| POST | `/pcs/sweep` | `{"prefix": "192.168.82"}`: ping a private subnet (at most /22) and reverse-DNS what answers. No login is sent. |
| POST | `/pcs/{host}/{action}` | `info`, `disks`, `folders`, `processes`, `cleanup`, `gpupdate`, `reboot`, `kill` (`arg`: process name), `command` (`arg`: PowerShell) |
| POST | `/pcs/keys` | `{"source": "ad"}` or `{"source": "subnet", "prefix": "192.168.82"}`: key inventory job, 202 |
| GET | `/pcs/keys/{id}` | progress and rows: Windows keys (BIOS, registry), Office (last five characters), 1C `.lic` files |
| GET | `/pcs/keys/{id}/csv` | the same as CSV for Excel: `;`, UTF-8 with BOM |

Safety:
- The domain login goes only to computers the plant knows: in the inventory (by name or IP, a computer kind)
  or in AD (`AD_LDAP_URL`). Anything else is `403 unknown_host` and nothing is sent.
- `cleanup`, `gpupdate`, `reboot`, `kill`, `command` change the computer: in real mode they need
  `ALLOW_RUNBOOK_WRITES=true` (`403 writes_disabled`). `cleanup`, `reboot`, `kill` and `command` also need
  `"confirm": true` (`400 confirmation_required`); the app asks for a fingerprint first.
- Every action is in the audit log as `pc:<action>` under «Сценарии». Reads are logged without what they returned,
  so licence keys stay out of the log; changes keep their output, the console its command.
- The console runs one command per request (no session between commands); arguments reach PowerShell base64-encoded.

## Server settings («Настройки сервера»)

Settings saved here override `netadmin.env` from the next start of the service.

- `GET /api/v1/server-settings` → `{groups: [{title, note, fields: [{key, label, help, kind, choices, credential_kind,
  value, is_set, source, restart_required}]}], restart_pending, can_restart}`. `kind`: bool, int, float, text, choice,
  secret, credential. Secrets come back as `value: null` with `is_set`. `source`: ui, file, default.
- `PUT /api/v1/server-settings` `{values: {key: value}, reset: [key], operator}` → the same payload. An empty secret
  keeps the stored one; `reset` returns a setting to the file. Unknown or bootstrap keys (`api_token`,
  `database_url`, ...) and values that do not fit → 422 `bad_setting` with `key`. Audited as `server_settings`.
- `POST /api/v1/server-settings/restart` → 202; the service stops cleanly and its manager starts it again
  (the agent exits with code 3; in Docker the container restarts). Not run as a service → 409 `cannot_restart`.
