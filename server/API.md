# NetAdmin server API

Interactive docs: `http://<server>:8000/docs` (Swagger UI) and `/openapi.json`.

All `/api/v1` routes except `GET /api/v1/health` need `Authorization: Bearer <API_TOKEN>`.
The WebSocket takes the token as `?token=<API_TOKEN>` (or the same header).
JSON is snake_case. Coordinates `x`/`y` are pixels on the floor plan image.
Clients should ignore unknown fields.

## Read

| Method | Path | Returns |
|---|---|---|
| GET | `/api/v1/health` | `{status, version, hal_mode: "emulator"\|"real", device_writes_enabled}` (no auth) |
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
| GET | `/api/v1/printers` | `[{id, name, ip, model, is_online, total_pages, consumables: [{name, color_hex, level_percent}]}]` |
| GET | `/api/v1/alerts?is_resolved=&severity=&device_id=` | `[{id, device_id, port_id, severity, alert_type, message, is_resolved, created_at, resolved_at}]` |
| GET | `/api/v1/devices/{id}/configs` | stored config snapshots (metadata) |
| GET | `/api/v1/devices/{id}/configs/diff?base_id=` | `{base_id, base_hash, current_hash, changed, diff}`: stored vs live running-config (reads the switch) |

`type` is one of `SWITCH, WORKSTATION, SERVER, PRINTER, SOCKET, PATCH_PANEL, CAMERA, ACCESS_POINT`.
`vlan_id` of an end device is the access VLAN of the switch port it is cabled to; for a switch it is the management VLAN.

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
{"type": "alert", "alert": {/* alert object */}}
{"type": "error", "message": "..."}
```

A bad token closes the socket with code 1008.
