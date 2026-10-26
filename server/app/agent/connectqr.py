"""QR code that connects the phone: the app scans it in ⚙ → Сервер instead of typing the address and token.

Payload: netadmin://connect?u=<base url>&u=<another>&t=<API token>. Several u because a workstation
often has more than one address (office network, VPN); the app keeps the first one that answers.
"""

from urllib.parse import quote

import segno

MAX_ADDRESSES = 4


def payload(addresses: list[str], port: int | str, token: str) -> str:
    urls = [f"http://{a}:{port}" for a in addresses[:MAX_ADDRESSES]]
    query = "&".join([f"u={quote(u, safe='')}" for u in urls] + [f"t={quote(token, safe='')}"])
    return f"netadmin://connect?{query}"


def terminal(data: str) -> str:
    """The code drawn with block characters, two rows per line. Light modules are the drawn ones, so
    on the usual dark console (light text) it comes out dark-on-light, the way cameras read QR codes."""
    qr = segno.make(data, error="m", micro=False)
    rows = [[not dark for dark in row] for row in qr.matrix_iter(scale=1, border=2)]
    if len(rows) % 2:
        rows.append([True] * len(rows[0]))
    glyph = {(True, True): "█", (True, False): "▀", (False, True): "▄", (False, False): " "}
    lines = []
    for top, bottom in zip(rows[::2], rows[1::2], strict=True):
        lines.append("".join(glyph[t, b] for t, b in zip(top, bottom, strict=True)))
    return "\n".join(lines)
