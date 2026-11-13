"use strict";
// «PoE»: the power budget of each managed switch and what its ports draw, read from the switch (`show power inline`).
// Also a block in the card of a switch. Read-only.
(() => {
  const { $, api, fail, openCard, openTab, tabs } = window.NetAdmin;

  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  const watts = (w) => (w == null ? "—" : `${Number(w).toFixed(1).replace(/\.0$/, "")} Вт`);
  const LEVEL = { ok: "", warn: "warn", critical: "bad", unknown: "muted" };

  /** A thin bar: how much of the budget is used. */
  function meter(r) {
    const wrap = el("div", "meter");
    const fill = el("i");
    fill.style.width = `${Math.min(100, r.percent || 0)}%`;
    if (r.level === "critical") fill.className = "bad"; else if (r.level === "warn") fill.className = "warn";
    wrap.append(fill);
    return wrap;
  }

  function summary(r) {
    if (!r.ok) return el("span", "muted", r.error || "нет данных");
    const t = el("span", LEVEL[r.level] || "", `${watts(r.used_w)} из ${watts(r.budget_w)}${r.percent != null ? ` · ${r.percent} %` : ""}`);
    return t;
  }

  function portTable(r) {
    const rows = r.ports.filter((p) => p.state !== "off" || p.admin !== "auto").concat(r.ports.filter((p) => p.state === "off" && p.admin === "auto"));
    if (!rows.length) return el("p", "muted small", "Портов с PoE коммутатор не показал.");
    const table = el("table", "grid");
    const head = el("tr");
    for (const h of ["Порт", "Состояние", "Мощность", "Что подключено"]) head.append(el("th", null, h));
    const thead = el("thead");
    thead.append(head);
    table.append(thead);
    const body = el("tbody");
    for (const p of rows) {
      const tr = el("tr");
      const state = p.state === "on" ? "питает" : p.state === "fault" ? "неисправность" : p.admin === "off" || p.admin === "never" || p.admin === "disable" ? "выключено" : "нет нагрузки";
      tr.append(el("td", "mono", p.port), el("td", p.state === "fault" ? "bad" : p.state === "on" ? "" : "muted", state),
        el("td", "mono", p.state === "on" ? watts(p.power_w) : ""), el("td", null, p.device || ""));
      body.append(tr);
    }
    table.append(body);
    const wrap = el("div", "table-wrap");
    wrap.append(table);
    return wrap;
  }

  // ------------------------------------------------------------------ the section

  async function load() {
    const box = $("poe-list");
    box.replaceChildren(el("p", "muted", "Читаю коммутаторы…"));
    const data = await api("GET", "poe");
    $("poe-note").textContent = data.without_poe ? `Ещё ${data.without_poe} коммутаторов без PoE не показаны.` : "";
    if (!data.rows.length) { box.replaceChildren(el("p", "muted", "Нет управляемых коммутаторов с PoE. Нужен IP-адрес и логин SSH в карточке коммутатора.")); return; }
    box.replaceChildren(...data.rows.map((r) => {
      const card = el("div", "card");
      const head = el("div", "row");
      const name = el("button", "link", r.name); name.type = "button";
      name.addEventListener("click", () => openCard(r.device_id).catch(fail));
      head.append(name, el("div", "spacer"), summary(r));
      card.append(head);
      if (r.ok) {
        card.append(meter(r), el("p", "muted small", `Питается портов: ${r.powered}${r.faults ? ` · неисправностей: ${r.faults}` : ""}`));
        const more = el("button", null, "Порты"); more.type = "button";
        const slot = el("div");
        more.addEventListener("click", async () => {
          if (slot.firstChild) { slot.replaceChildren(); return; }
          try { slot.replaceChildren(portTable(await api("GET", `poe/${r.device_id}`))); } catch (e) { fail(e); }
        });
        card.append(more, slot);
      }
      return card;
    }));
  }
  tabs.poe = load;
  $("poe-refresh").addEventListener("click", () => load().catch(fail));

  // ------------------------------------------------------------------ in the card of a switch

  window.NetAdmin.poeBlock = async (d) => {
    if (d.type !== "SWITCH" || !d.ip) return null;
    const r = await api("GET", `poe/${d.id}`).catch(() => null);
    if (!r || !r.supported) return null;
    const card = el("div", "card");
    card.append(el("h3", null, "PoE"));
    const line = el("div", "row");
    line.append(summary(r));
    card.append(line);
    if (r.ok) {
      card.append(meter(r));
      const more = el("button", null, "Порты с питанием"); more.type = "button";
      const slot = el("div");
      more.addEventListener("click", () => { if (slot.firstChild) slot.replaceChildren(); else slot.replaceChildren(portTable(r)); });
      card.append(more, slot);
    }
    return card;
  };
})();
