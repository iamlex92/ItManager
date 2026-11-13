"use strict";
// «DHCP и DNS»: what the plant's DHCP server (ISC dhcpd) and DNS zones say, read from the servers and compared with the
// inventory. Nothing is changed on the servers; «Внести в план адресов» only adds to the address plan of this program.
(() => {
  const { $, api, toast, fail, tabs, openCard } = window.NetAdmin;

  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  const table = (head, rows) => {
    const t = el("table", "grid");
    const thead = el("thead"), tr = el("tr");
    for (const h of head) tr.append(el("th", null, h));
    thead.append(tr);
    const body = el("tbody");
    for (const r of rows) {
      const row = el("tr");
      for (const c of r) { const td = el("td"); if (c instanceof Node) td.append(c); else td.textContent = c ?? ""; row.append(td); }
      body.append(row);
    }
    t.append(thead, body);
    const wrap = el("div", "table-wrap");
    wrap.append(t);
    return wrap;
  };
  const SEV = { critical: "bad", warn: "warn" };
  const sim = (d) => (d.simulated ? el("p", "muted small", "Эмуляция: данные примерные, собраны из учёта. Настоящие серверы подключаются в «Настройки сервера» → «DHCP и DNS».") : null);

  async function status() {
    const st = await api("GET", "netsrv/status");
    const note = $("netsrv-status");
    const parts = [];
    if (!st.dhcp) parts.push("Сервер DHCP не задан");
    if (!st.dns) parts.push("Сервер DNS или зоны не заданы");
    note.textContent = parts.length ? `${parts.join(". ")}: «Настройки сервера» → «DHCP и DNS».` : "";
    $("dhcp-run").disabled = !st.dhcp;
    $("dns-srv-run").disabled = !st.dns;
  }

  async function runDhcp() {
    const box = $("dhcp-result");
    box.replaceChildren(el("p", "muted", "Читаю сервер DHCP…"));
    const d = await api("GET", "netsrv/dhcp");
    const out = [];
    const s = sim(d);
    if (s) out.push(s);
    out.push(el("p", "muted small", `Сервер ${d.host}: активных аренд ${d.leases}, закреплённых адресов ${d.reservations}.`));
    out.push(d.scopes.length ? table(["Область", "Диапазоны", "Выдано", "Свободно", ""], d.scopes.map((sc) => {
      const m = el("div", "meter"), fill = el("i");
      fill.style.width = `${Math.min(100, sc.percent)}%`;
      if (sc.level !== "ok") fill.className = sc.level === "critical" ? "bad" : "warn";
      m.append(fill);
      return [sc.network, sc.ranges.map((r) => `${r.start}–${r.end}`).join(", ") || "без диапазона", `${sc.leased} из ${sc.total} (${sc.percent} %)`, String(sc.free), m];
    })) : el("p", "muted", "В настройках dhcpd нет областей (subnet)."));
    out.push(el("h3", null, d.findings.length ? `Что не сходится с учётом · ${d.findings.length}` : "С учётом всё сходится"));
    if (d.findings.length) out.push(table(["Адрес", "Что"], d.findings.map((f) => [f.ip, el("span", SEV[f.severity] || "", f.text)])));
    if (d.new_ranges.length || d.new_reservations.length) {
      const row = el("div", "row");
      row.append(el("span", null, `В плане адресов нет: диапазонов ${d.new_ranges.length}, закреплённых адресов ${d.new_reservations.length}.`), el("div", "spacer"));
      const b = el("button", "primary", "Внести в план адресов"); b.type = "button";
      b.addEventListener("click", async () => {
        b.disabled = true;
        try {
          const r = await api("POST", "netsrv/dhcp/import", {});
          toast(`Внесено: диапазонов ${r.ranges}, адресов ${r.reservations}`);
          await runDhcp();
        } catch (e) { b.disabled = false; fail(e); }
      });
      row.append(b);
      out.push(row);
    }
    if (d.unknown_count) {
      out.push(el("h3", null, `Клиенты DHCP, которых нет в учёте · ${d.unknown_count}`));
      out.push(table(["Адрес", "MAC", "Имя"], d.unknown_leases.map((u) => [u.ip, u.mac || "", u.hostname || ""])));
    }
    box.replaceChildren(...out);
  }

  async function runDns() {
    const box = $("dns-srv-result");
    box.replaceChildren(el("p", "muted", "Читаю зоны DNS…"));
    const d = await api("GET", "netsrv/dns");
    const out = [];
    const s = sim(d);
    if (s) out.push(s);
    out.push(el("p", "muted small", `Сервер ${d.host}, зоны ${d.zones.join(", ")}: записей A ${d.records}.`));
    out.push(el("h3", null, `Есть в учёте, нет записи в DNS · ${d.missing.length}`));
    if (d.missing.length) out.push(table(["Устройство", "Адрес в учёте"], d.missing.map((m) => [m.name, m.ip])));
    out.push(el("h3", null, `Запись указывает на другой адрес · ${d.wrong.length}`));
    if (d.wrong.length) out.push(table(["Устройство", "В учёте", "В DNS"], d.wrong.map((m) => [m.name, m.inventory, m.dns.join(", ")])));
    out.push(el("h3", null, `Записи без устройства в учёте · ${d.stale_count}`));
    if (d.stale.length) out.push(table(["Имя", "Адрес"], d.stale.map((m) => [m.name, m.ip])));
    out.push(el("p", "muted small", "Записи без устройства бывают от DHCP-клиентов (ноутбуки, телефоны) и старых компьютеров: решайте по каждой."));
    box.replaceChildren(...out);
  }

  tabs.netsrv = () => status().catch(fail);
  $("dhcp-run").addEventListener("click", () => runDhcp().catch((e) => { $("dhcp-result").replaceChildren(); fail(e); }));
  $("dns-srv-run").addEventListener("click", () => runDns().catch((e) => { $("dns-srv-result").replaceChildren(); fail(e); }));
})();
