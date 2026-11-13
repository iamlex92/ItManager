"""The infrastructure report: sections and presets, what is hidden by default (keys, passwords), the Excel workbook,
the page opened by its address, and that nothing from the inventory can break the page."""

import io

import openpyxl
from fastapi.testclient import TestClient

R = "/api/v1/report"
KEY = "AAAAA-BBBBB-CCCCC-DDDDD-EEEEE"


def _seed(client):
    client.post("/api/v1/licenses", json={"software": "Windows 10 Pro", "seats": 1, "key": KEY})
    client.post(
        "/api/v1/credentials", json={"name": "ssh-sw", "kind": "ssh", "username": "admin", "secret": "S3cret-pw!"}
    )
    client.post("/api/v1/devices", json={"name": "<script>alert(1)</script>", "type": "SERVER", "ip": "10.10.55.5"})
    client.post("/api/v1/devices", json={"name": "=1+1", "type": "WORKSTATION", "ip": "10.10.55.6"})


def test_options_and_default_report(client):
    o = client.get(f"{R}/options").json()
    assert [s["id"] for s in o["sections"]][:3] == ["summary", "equipment", "network"]
    assert {p["id"] for p in o["presets"]} == {"full", "brief", "external"}
    _seed(client)
    r = client.post(f"{R}/download", json={"preset": "full"})
    assert (
        r.status_code == 200
        and "attachment" in r.headers["content-disposition"]
        and ".html" in r.headers["content-disposition"]
    )
    page = r.text
    for part in (
        "Резюме",
        "Оборудование",
        "Сеть",
        "Коммутаторы",
        "Программы и лицензии",
        "О самом отчёте",
        "Схема сети",
    ):
        assert part in page
    # Names from the inventory are text, never markup.
    assert "<script>alert(1)</script>" not in page and "&lt;script&gt;alert(1)&lt;/script&gt;" in page
    # No password, key hidden down to its last four characters.
    assert "S3cret-pw" not in page and KEY not in page and "••••EEEE" in page
    assert "ssh-sw" in page  # the login's name is listed, its secret never
    assert "режиме эмуляции" in page  # the emulated sources are said so


def test_keys_need_confirmation_and_are_journaled(client):
    _seed(client)
    no = client.post(f"{R}/download", json={"with_keys": True})
    assert no.status_code == 400 and no.json()["detail"]["code"] == "confirmation_required"
    ok = client.post(f"{R}/download", json={"with_keys": True, "confirm_keys": True, "sections": ["licenses"]})
    assert ok.status_code == 200 and KEY in ok.text
    log = client.get("/api/v1/audit", params={"kind": "security"}).json()
    assert any("полными ключами" in row["title"] for row in log)
    # Handing the report outside never carries a full key, whatever was asked.
    out = client.post(f"{R}/download", json={"preset": "external", "with_keys": True, "confirm_keys": True})
    assert KEY not in out.text


def test_external_preset_masks_addresses(client):
    _seed(client)
    page = client.post(f"{R}/download", json={"preset": "external"}).text
    assert "10.10.55.5" not in page and "10.10.•.•" in page
    assert "ssh-sw" not in page  # the section of stored logins is left out


def test_sections_and_brief(client):
    only = client.post(f"{R}/download", json={"sections": ["summary"]}).text
    assert "Резюме" in only and "Коммутаторы" not in only.split("</nav>")[0] and "4. Коммутаторы" not in only
    assert client.post(f"{R}/download", json={"sections": ["nope"]}).status_code == 422
    brief = client.post(f"{R}/download", json={"preset": "brief"}).text
    full = client.post(f"{R}/download", json={"preset": "full"}).text
    assert len(brief) < len(full) / 2


def test_excel_workbook(client):
    _seed(client)
    r = client.post(f"{R}/download", json={"format": "xlsx", "preset": "full"})
    assert r.status_code == 200 and ".xlsx" in r.headers["content-disposition"]
    wb = openpyxl.load_workbook(io.BytesIO(r.content))
    assert {"Сводка", "Устройства", "Порты", "VLAN", "Лицензии"} <= set(wb.sheetnames)
    ws = wb["Устройства"]
    assert ws.auto_filter.ref and ws.freeze_panes == "A2"
    names = [row[0].value for row in ws.iter_rows(min_row=2)]
    cell = next(row[0] for row in ws.iter_rows(min_row=2) if row[0].value == "=1+1")
    assert "=1+1" in names and cell.data_type == "s"  # a name that looks like a formula stays text


def test_page_opened_by_its_address(client):
    _seed(client)
    made = client.post(f"{R}/preview", json={"preset": "full"}).json()
    assert made["url"].startswith("/report/view/")
    # The address is the key: no login header is sent here.
    anon = TestClient(client.app)
    page = anon.get(made["url"])
    assert page.status_code == 200 and "Отчёт по IT-инфраструктуре" in page.text
    csp = page.headers["content-security-policy"]
    assert "script-src" not in csp and "default-src 'none'" in csp and "'unsafe-inline'" in csp
    assert "<script" not in page.text and page.headers["cache-control"] == "no-store"
    assert anon.get("/report/view/unknown").status_code == 404


def test_report_is_journaled_without_content(client):
    client.post(f"{R}/download", json={"sections": ["summary"]})
    rows = client.get("/api/v1/audit", params={"kind": "security"}).json()
    assert any(row["title"] == "Отчёт: сформирован" for row in rows)
