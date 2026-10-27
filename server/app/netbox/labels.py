"""Printable QR labels for devices: an A4 page of cut-out labels, printed from the browser (also to PDF).

The QR carries the same JSON as the device's NFC tag, so one scanner reads both.
"""

import html
import io
import json

import segno

from ..models import Device

TAG_APP = "netadmin"
TAG_VERSION = 1


def tag_payload(device: Device) -> str:
    return json.dumps(
        {
            "app": TAG_APP,
            "v": TAG_VERSION,
            "id": str(device.id),
            "type": device.device_type.lower(),
            "name": device.name,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )


def qr_svg(data: str) -> str:
    out = io.BytesIO()
    segno.make(data, error="m", micro=False).save(out, kind="svg", scale=4, border=1, xmldecl=False, svgns=True)
    return out.getvalue().decode("utf-8")


def labels_page(devices: list[Device]) -> str:
    cells = []
    for d in devices:
        ip = html.escape(str(d.ip_address)) if d.ip_address else ""
        cells.append(
            '<div class="label">'
            f"{qr_svg(tag_payload(d))}"
            f'<div class="text"><b>{html.escape(d.name)}</b><br>{html.escape(d.device_type.title())}'
            f"{'<br>' + ip if ip else ''}</div></div>"
        )
    return (
        '<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Наклейки ItManager</title><style>'
        "@page{size:A4;margin:10mm}body{font-family:sans-serif;margin:0}"
        ".grid{display:grid;grid-template-columns:repeat(3,1fr);gap:4mm}"
        ".label{display:flex;align-items:center;gap:3mm;border:1px dashed #888;padding:3mm;"
        "break-inside:avoid;height:28mm}"
        ".label svg{width:22mm;height:22mm;flex:none}.text{font-size:9pt;overflow:hidden;line-height:1.3}"
        "</style></head><body>"
        f'<div class="grid">{"".join(cells)}</div></body></html>'
    )
