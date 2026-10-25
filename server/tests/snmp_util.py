"""Tiny SNMP v2c agent and trap builders for tests (pysnmp protocol API)."""

import asyncio

from pyasn1.codec.ber import decoder, encoder
from pysnmp.proto import api

V1 = api.PROTOCOL_MODULES[api.SNMP_VERSION_1]
V2 = api.PROTOCOL_MODULES[api.SNMP_VERSION_2C]


def _key(oid: str) -> tuple[int, ...]:
    return tuple(int(x) for x in oid.split("."))


class FakeAgent(asyncio.DatagramProtocol):
    """Answers GET / GETNEXT / GETBULK from a dict of OID -> pysnmp value."""

    def __init__(self, table: dict[str, object], community: str = "public") -> None:
        self.table = table
        self.community = community
        self.order = sorted(table, key=_key)
        self.requests: list[str] = []

    def connection_made(self, transport) -> None:
        self.transport = transport

    def _next(self, oid: str) -> str | None:
        k = _key(oid)
        return next((o for o in self.order if _key(o) > k), None)

    def datagram_received(self, data: bytes, addr) -> None:
        msg, _ = decoder.decode(data, asn1Spec=V2.Message())
        if str(V2.apiMessage.get_community(msg)) != self.community:
            return
        req = V2.apiMessage.get_pdu(msg)
        rsp_msg = V2.apiMessage.get_response(msg)
        rsp = V2.apiMessage.get_pdu(rsp_msg)
        out = []
        if req.isSameTypeWith(V2.GetRequestPDU()):
            self.requests.append("get")
            for oid, _ in V2.apiPDU.get_varbinds(req):
                o = str(oid)
                out.append((oid, self.table.get(o, V2.NoSuchObject())))
        else:
            reps = 1
            if req.isSameTypeWith(V2.GetBulkRequestPDU()):
                self.requests.append("bulk")
                reps = int(V2.apiBulkPDU.get_max_repetitions(req)) or 1
                vbs = V2.apiBulkPDU.get_varbinds(req)
            else:
                self.requests.append("next")
                vbs = V2.apiPDU.get_varbinds(req)
            for oid, _ in vbs:
                cur = str(oid)
                for _ in range(reps):
                    nxt = self._next(cur)
                    if nxt is None:
                        out.append((V2.ObjectIdentifier(cur), V2.EndOfMibView()))
                        break
                    out.append((V2.ObjectIdentifier(nxt), self.table[nxt]))
                    cur = nxt
        V2.apiPDU.set_varbinds(rsp, out)
        self.transport.sendto(encoder.encode(rsp_msg), addr)


async def start_agent(table, community="public"):
    loop = asyncio.get_running_loop()
    transport, proto = await loop.create_datagram_endpoint(
        lambda: FakeAgent(table, community), local_addr=("127.0.0.1", 0)
    )
    return transport, proto, transport.get_extra_info("sockname")[1]


def v2c_link_trap(up: bool, if_index: int, if_name: str | None, community: str = "public") -> bytes:
    pdu = V2.SNMPv2TrapPDU()
    V2.apiTrapPDU.set_defaults(pdu)
    vbs = [
        (V2.ObjectIdentifier("1.3.6.1.2.1.1.3.0"), V2.TimeTicks(12345)),
        (V2.ObjectIdentifier("1.3.6.1.6.3.1.1.4.1.0"), V2.ObjectIdentifier(f"1.3.6.1.6.3.1.1.5.{4 if up else 3}")),
        (V2.ObjectIdentifier(f"1.3.6.1.2.1.2.2.1.1.{if_index}"), V2.Integer(if_index)),
        (V2.ObjectIdentifier(f"1.3.6.1.2.1.2.2.1.7.{if_index}"), V2.Integer(1)),
        (V2.ObjectIdentifier(f"1.3.6.1.2.1.2.2.1.8.{if_index}"), V2.Integer(1 if up else 2)),
    ]
    if if_name:
        vbs.append((V2.ObjectIdentifier(f"1.3.6.1.2.1.2.2.1.2.{if_index}"), V2.OctetString(if_name)))
    V2.apiTrapPDU.set_varbinds(pdu, vbs)
    msg = V2.Message()
    V2.apiMessage.set_defaults(msg)
    V2.apiMessage.set_community(msg, community)
    V2.apiMessage.set_pdu(msg, pdu)
    return encoder.encode(msg)


def v1_link_trap(up: bool, agent: str, if_index: int, community: str = "public") -> bytes:
    pdu = V1.TrapPDU()
    V1.apiTrapPDU.set_defaults(pdu)
    V1.apiTrapPDU.set_enterprise(pdu, (1, 3, 6, 1, 4, 1, 35265))
    V1.apiTrapPDU.set_agent_address(pdu, agent)
    V1.apiTrapPDU.set_generic_trap(pdu, 3 if up else 2)
    V1.apiTrapPDU.set_varbinds(pdu, [(V1.ObjectIdentifier(f"1.3.6.1.2.1.2.2.1.1.{if_index}"), V1.Integer(if_index))])
    msg = V1.Message()
    V1.apiMessage.set_defaults(msg)
    V1.apiMessage.set_community(msg, community)
    V1.apiMessage.set_pdu(msg, pdu)
    return encoder.encode(msg)
