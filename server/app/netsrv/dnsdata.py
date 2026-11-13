"""DNS zone contents: A records, from `samba-tool dns query` or from a zone transfer."""

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class ARecord:
    name: str  # as in the zone: «pc-buh-01», «@» for the zone itself
    ip: str


def parse_samba_query(text: str) -> list[ARecord]:
    """`samba-tool dns query SERVER ZONE @ A`: «Name=pc-buh-01, Records=1, Children=0», then «A: 10.10.10.11 (…)»."""
    out: list[ARecord] = []
    name: str | None = None
    for line in text.splitlines():
        if m := re.match(r"\s*Name=([^,]*),", line):
            name = m.group(1).strip() or "@"
        elif (m := re.match(r"\s*A:\s*(\d{1,3}(?:\.\d{1,3}){3})\b", line)) and name is not None:
            out.append(ARecord(name.lower(), m.group(1)))
    return out


def zone_transfer(server: str, zone: str, timeout: float = 15.0) -> list[ARecord]:
    """AXFR of [zone] from [server]. Blocking: called from a worker thread."""
    import dns.query
    import dns.rdatatype
    import dns.zone

    z = dns.zone.from_xfr(dns.query.xfr(server, zone, lifetime=timeout))
    out: list[ARecord] = []
    for name, node in z.nodes.items():
        for rdataset in node.rdatasets:
            if rdataset.rdtype != dns.rdatatype.A:
                continue
            for r in rdataset:
                out.append(ARecord(str(name).lower(), r.address))
    return out
