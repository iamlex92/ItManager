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
| GET | `/api/v1/floors` | `[{id, building_name, floor_number, plan_image_url, scale_pixels_per_meter, width_px, height_px, created_at}]` |
| GET | `/static/plans/floor-1.svg` | plan image referenced by `plan_image_url` (no auth) |
| GET | `/api/v1/devices?floor_id=&type=&q=` | `[{id, name, type, vendor, model, ip, mac, floor_id, x, y, is_online, last_seen_at, vlan_id, is_active, nfc_tag_uid, snmp_community, metadata}]` |
| GET | `/api/v1/devices/{id}` | one device |
| GET | `/api/v1/devices/by-nfc/{uid}` | device bound to an NFC tag |
| GET | `/api/v1/search?q=` | devices matching name, IP, MAC or NFC UID |
| GET | `/api/v1/devices/{id}/ports` | `[{id, device_id, number, name, is_uplink, mode, access_vlan, tagged_vlans, is_admin_up, is_oper_up, speed_mbps, poe_enabled, connected_mac, connected_device_name}]` |
| GET | `/api/v1/ports/{id}/trace` | `{hops: [{device_id, device_name, device_type, port_id, port_name}]}`, e.g. PC → socket → patch panel → switch |
| GET | `/api/v1/vlans` | `[{vlan_id, name, subnet_cidr, color_hex, description}]` |
| GET | `/api/v1/connections?device_id=` | `[{id, port_a_id, port_b_id, device_a_id, device_b_id, cable_type, length_meters, label, notes}]` |
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

`POST/PATCH/DELETE` on `/api/v1/floors`, `/api/v1/vlans`, `/api/v1/devices`, `/api/v1/connections`;
`POST /api/v1/ports`, `DELETE /api/v1/ports/{id}`; `POST /api/v1/alerts`, `POST /api/v1/alerts/{id}/resolve`.

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

Finds hosts in the given subnets and the links between them. Read-only towards
equipment: one ICMP echo per address, then SNMP GET/GETNEXT (system group, ifName,
LLDP, CDP, ARP, bridge and Q-bridge MAC tables) to the hosts that answered. A scan
only starts from an explicit request with explicit ranges. With
`MONITOR_MODE=emulator` (default) the scan is answered from the inventory plus a
few devices it does not know yet (a new switch, a printer, a camera, a PC without
SNMP); nothing leaves the server.

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
`finished_at`, `total`, `probed`, `devices[]`, `links[]`, `error`. Devices and
links are empty while the scan runs.

- device: `ip`, `name` (sysName), `mac`, `vendor` (from sysObjectID), `model`,
  `type` (guessed: `SWITCH`, `PRINTER`, `CAMERA`, ... or `UNKNOWN`), `description`
  (sysDescr), `snmp`, `inventory_id` / `inventory_name` when the IP is already in
  the inventory.
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
