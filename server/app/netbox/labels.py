"""Printable QR labels for devices: an A4 page of cut-out labels, printed from the browser (also to PDF).

The QR is a link the phone's own camera hands to the app: itmanager://device/<id>, which opens the device's card.
Labels printed before carry the NFC tag's JSON instead; the app's scanner still reads those.
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


LINK_SCHEME = "itmanager"


def label_link(device: Device) -> str:
    """What the QR carries: the camera offers to open it in ItManager, which shows the device's card."""
    return f"{LINK_SCHEME}://device/{device.id}"


def qr_svg(data: str) -> str:
    out = io.BytesIO()
    segno.make(data, error="m", micro=False).save(out, kind="svg", scale=4, border=1, xmldecl=False, svgns=True)
    return out.getvalue().decode("utf-8")


# Sheet layouts: label size in mm and columns; «single» is one label per page for a thermal printer.
LAYOUTS = {
    "normal": {"cols": 3, "h": 28, "qr": 22, "page": "A4", "margin": 10, "font": 9},
    "small": {"cols": 4, "h": 20, "qr": 15, "page": "A4", "margin": 8, "font": 7},
    "single": {"cols": 1, "h": 30, "qr": 24, "page": "50mm 30mm", "margin": 0, "font": 8},
}


def short_id(device: Device) -> str:
    """The first characters of the id, printed under the name: a way to find the device without the app (search in the
    web console takes it)."""
    return str(device.id)[:8]


def labels_page(devices: list[Device], layout: str = "normal") -> str:
    lay = LAYOUTS.get(layout, LAYOUTS["normal"])
    cells = []
    for d in devices:
        ip = html.escape(str(d.ip_address)) if d.ip_address else ""
        cells.append(
            '<div class="label">'
            f"{qr_svg(label_link(d))}"
            f'<div class="text"><b>{html.escape(d.name)}</b><br>{html.escape(d.device_type.title())}'
            f'{"<br>" + ip if ip else ""}<br><span class="sid">№ {short_id(d)}</span></div></div>'
        )
    return (
        '<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>Наклейки ItManager</title><style>'
        f"@page{{size:{lay['page']};margin:{lay['margin']}mm}}body{{font-family:sans-serif;margin:0}}"
        f".grid{{display:grid;grid-template-columns:repeat({lay['cols']},1fr);gap:{4 if lay['cols'] > 1 else 0}mm}}"
        f".label{{display:flex;align-items:center;gap:3mm;border:{'1px dashed #888' if lay['cols'] > 1 else 'none'};"
        f"padding:{3 if lay['cols'] > 1 else 2}mm;break-inside:avoid;height:{lay['h']}mm;"
        f"{'page-break-after:always;' if lay['cols'] == 1 else ''}}}"
        f".label svg{{width:{lay['qr']}mm;height:{lay['qr']}mm;flex:none}}"
        f".text{{font-size:{lay['font']}pt;overflow:hidden;line-height:1.3}}.sid{{color:#555;font-family:monospace}}"
        "</style></head><body>"
        f'<div class="grid">{"".join(cells)}</div></body></html>'
    )
