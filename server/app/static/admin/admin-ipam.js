"use strict";
// Under «IP-адреса»: addresses held for someone, DHCP and other ranges, and a DNS check of the inventory.
(() => {
  const { $, api, toast, fail, ask, openCard, tabs, formDialog, field, input, select, el } = window.NetAdmin;
  const button = (text, onClick, cls) => { const b = el("button", cls, text); b.type = "button"; b.addEventListener("click", onClick); return b; };
  const KIND = { dhcp: "DHCP", static: "статические", reserved: "зарезервированные" };
  let devices = [];

  const table = (heads) => {
    const t = el("table", "grid"), tr = el("tr");
    for (const h of heads) tr.append(el("th", null, h));
    const head = el("thead"); head.append(tr);
    t.append(head, el("tbody"));
    return t;
  };
  const cell = (tr, v, cls) => { const c = el("td", cls); if (v instanceof Node) c.append(v); else c.textContent = v ?? "—"; tr.append(c); return c; };
  const wrap = (t) => { const w = el("div", "table-wrap"); w.append(t); return w; };
  const empty = (t, n, text) => { const r = el("tr"), c = el("td", "muted", text); c.colSpan = n; r.append(c); t.tBodies[0].append(r); };

  async function load() {
    const [res, ranges] = await Promise.all([api("GET", "ipam/reservations"), api("GET", "ipam/ranges")]);
    devices = await api("GET", "devices");

    const rt = table(["Адрес", "Для кого", "Устройство", "MAC", "Заметка", ""]);
    for (const r of res) {
      const tr = el("tr");
      cell(tr, r.ip, "mono"); cell(tr, r.name);
      cell(tr, r.device_id ? button(r.device_name || "устройство", () => openCard(r.device_id), "link") : "—");
      cell(tr, r.mac, "mono"); cell(tr, r.note);
      const act = cell(tr, "");
      act.append(button("Изменить", () => editReservation(r), "ghost small"), " ", button("Снять", async () => {
        if (!(await ask(`Снять резерв ${r.ip} (${r.name})?`, { action: "Снять", danger: true }))) return;
        try { await api("DELETE", `ipam/reservations/${r.id}`); await load(); } catch (e) { fail(e); }
      }, "ghost small"));
      rt.tBodies[0].append(tr);
    }
    if (!res.length) empty(rt, 6, "Резервов нет.");
    $("ipam-reservations").replaceChildren(wrap(rt));

    const gt = table(["Диапазон", "Вид", "От", "До", "Адресов", ""]);
    for (const r of ranges) {
      const tr = el("tr");
      cell(tr, r.name); cell(tr, KIND[r.kind] || r.kind); cell(tr, r.start_ip, "mono"); cell(tr, r.end_ip, "mono"); cell(tr, String(r.size));
      const act = cell(tr, "");
      act.append(button("Изменить", () => editRange(r), "ghost small"), " ", button("Удалить", async () => {
        if (!(await ask(`Удалить диапазон «${r.name}»?`, { action: "Удалить", danger: true }))) return;
        try { await api("DELETE", `ipam/ranges/${r.id}`); await load(); } catch (e) { fail(e); }
      }, "ghost small"));
      gt.tBodies[0].append(tr);
    }
    if (!ranges.length) empty(gt, 6, "Диапазонов нет. Внесите пул DHCP, чтобы программа не предлагала его адреса и ловила устройства, настроенные в нём вручную.");
    $("ipam-ranges").replaceChildren(wrap(gt));
  }

  function editReservation(r) {
    const isNew = !r;
    return formDialog(isNew ? "Резерв адреса" : "Резерв", (form) => {
      const f = {};
      f.ip = field(form, "Адрес", input("text", r?.ip, { required: true, maxLength: 45, placeholder: "10.10.20.50" }));
      f.name = field(form, "Для кого", input("text", r?.name, { required: true, maxLength: 100, placeholder: "Принтер бухгалтерии" }));
      f.device = select([["", "— без устройства —"], ...devices.filter((d) => d.type !== "SOCKET" && d.type !== "RACK").sort((a, b) => a.name.localeCompare(b.name)).map((d) => [d.id, d.name])], r?.device_id || "");
      field(form, "Устройство (если оно уже в учёте)", f.device);
      f.mac = field(form, "MAC", input("text", r?.mac, { maxLength: 17, placeholder: "aa:bb:cc:dd:ee:ff" }));
      f.note = field(form, "Заметка", el("textarea")); f.note.rows = 2; f.note.value = r?.note || "";
      return f;
    }, async (f) => {
      const body = { ip: f.ip.value.trim(), name: f.name.value.trim(), device_id: f.device.value || null, mac: f.mac.value.trim() || null, note: f.note.value.trim() || null };
      if (isNew) await api("POST", "ipam/reservations", body); else await api("PATCH", `ipam/reservations/${r.id}`, body);
      await tabs.ipam();
    });
  }

  function editRange(r) {
    const isNew = !r;
    return formDialog(isNew ? "Диапазон адресов" : "Диапазон", (form) => {
      const f = {};
      f.name = field(form, "Название", input("text", r?.name, { required: true, maxLength: 100, placeholder: "Пул DHCP для клиентов" }));
      f.kind = select(Object.entries(KIND), r?.kind || "dhcp");
      field(form, "Вид", f.kind);
      const row = el("div", "row"); form.append(row);
      f.start = input("text", r?.start_ip, { required: true, maxLength: 45, placeholder: "10.10.20.100" });
      f.end = input("text", r?.end_ip, { required: true, maxLength: 45, placeholder: "10.10.20.199" });
      const a = el("label", "grow"); a.append("От", f.start);
      const b = el("label", "grow"); b.append("До", f.end);
      row.append(a, b);
      f.note = field(form, "Заметка", el("textarea")); f.note.rows = 2; f.note.value = r?.note || "";
      return f;
    }, async (f) => {
      const body = { name: f.name.value.trim(), kind: f.kind.value, start_ip: f.start.value.trim(), end_ip: f.end.value.trim(), note: f.note.value.trim() || null };
      if (isNew) await api("POST", "ipam/ranges", body); else await api("PATCH", `ipam/ranges/${r.id}`, body);
      await tabs.ipam();
    });
  }

  $("ipam-res-new").addEventListener("click", () => editReservation(null));
  $("ipam-range-new").addEventListener("click", () => editRange(null));

  // ------------------------------------------------------------------ DNS

  const STATE = { ok: "✓", mismatch: "не тот", missing: "нет" };
  try { $("dns-suffix").value = localStorage.getItem("netadmin.dnsSuffix") || ""; } catch { /* storage blocked */ }
  $("dns-run").addEventListener("click", async () => {
    const suffix = $("dns-suffix").value.trim();
    try { localStorage.setItem("netadmin.dnsSuffix", suffix); } catch { /* storage blocked */ }
    $("dns-run").disabled = true;
    $("dns-result").replaceChildren(el("p", "muted", "Проверяю…"));
    try {
      const out = await api("POST", "ipam/dns-check", { suffix });
      const s = out.summary;
      const head = el("p", s.ok === s.checked ? "online" : "", `Проверено ${s.checked}, без замечаний ${s.ok}. Имя не найдено: ${s.forward_missing}, имя указывает на другой адрес: ${s.forward_mismatch}, обратной записи нет: ${s.reverse_missing}, обратная запись с другим именем: ${s.reverse_mismatch}.`);
      const t = table(["Устройство", "Адрес в учёте", "Имя → адрес", "Адрес → имя"]);
      for (const r of out.rows) {
        const tr = el("tr", "clickable");
        tr.addEventListener("click", () => openCard(r.device_id));
        cell(tr, r.name); cell(tr, r.ip, "mono");
        cell(tr, `${STATE[r.forward]}${r.forward === "mismatch" ? ` (DNS: ${r.forward_ips.join(", ")})` : ""}`, r.forward === "ok" ? "online" : "bad");
        cell(tr, `${STATE[r.reverse]}${r.reverse === "mismatch" ? ` (DNS: ${r.reverse_name})` : ""}`, r.reverse === "ok" ? "online" : "bad");
        t.tBodies[0].append(tr);
      }
      $("dns-result").replaceChildren(head, wrap(t));
    } catch (e) { $("dns-result").replaceChildren(); fail(e); } finally { $("dns-run").disabled = false; }
  });

  const prev = tabs.ipam;
  tabs.ipam = async () => { if (prev) await prev(); await load(); };
})();
