"use strict";
// The rest of the web console: alerts, audit log, runbooks, printers, credentials, and switch details
// (VLAN view, history, traffic, config import). Builds on admin.js through window.NetAdmin.
// Inventory and read-only views: nothing here writes to equipment.
(() => {
  const { $, api, toast, fail, OPERATOR, tabs } = window.NetAdmin;

  const el = (tag, props = {}, ...kids) => {
    const e = Object.assign(document.createElement(tag), props);
    e.append(...kids);
    return e;
  };
  const td = (tr, ...kids) => { const c = el("td", {}, ...kids); tr.append(c); return c; };
  const when = (iso) => new Date(iso).toLocaleString("ru-RU", { dateStyle: "short", timeStyle: "medium" });
  const empty = (tbody, cols, text) => tbody.replaceChildren(el("tr", {}, Object.assign(el("td", { colSpan: cols, className: "muted" }, text))));
  const bps = (v) => {
    if (v == null) return "—";
    if (v >= 1e9) return `${(v / 1e9).toFixed(1)} Гбит/с`;
    if (v >= 1e6) return `${(v / 1e6).toFixed(1)} Мбит/с`;
    if (v >= 1e3) return `${(v / 1e3).toFixed(0)} кбит/с`;
    return `${v} бит/с`;
  };

  // ---------------------------------------------------------------- alerts

  async function loadAlerts() {
    const q = new URLSearchParams();
    if ($("alert-state").value) q.set("is_resolved", $("alert-state").value);
    if ($("alert-severity").value) q.set("severity", $("alert-severity").value);
    const [alerts, devices] = await Promise.all([api("GET", `alerts?${q}`), api("GET", "devices")]);
    const deviceName = (id) => (devices.find((d) => d.id === id) || {}).name || "—";
    const rows = $("alert-rows");
    if (!alerts.length) return empty(rows, 5, "Оповещений нет.");
    rows.replaceChildren(...alerts.map((a) => {
      const tr = el("tr");
      td(tr, when(a.created_at));
      td(tr, el("span", { className: `sev-${a.severity}`, textContent: { CRITICAL: "Критично", WARNING: "Внимание", INFO: "Инфо" }[a.severity] || a.severity }));
      td(tr, deviceName(a.device_id));
      td(tr, a.message);
      const act = td(tr);
      if (!a.is_resolved) {
        act.append(el("button", {
          textContent: "Закрыть",
          onclick: async () => { try { await api("POST", `alerts/${a.id}/resolve`); await loadAlerts(); } catch (e) { fail(e); } },
        }));
      } else act.textContent = `закрыто ${when(a.resolved_at)}`;
      return tr;
    }));
  }
  for (const id of ["alert-state", "alert-severity"]) $(id).addEventListener("change", () => loadAlerts().catch(fail));
  $("alert-reload").addEventListener("click", () => loadAlerts().catch(fail));
  tabs.alerts = loadAlerts;

  // ---------------------------------------------------------------- audit log

  const KIND = { runbook: "Runbook", switch: "Коммутатор", network: "Сеть", security: "Безопасность" };

  async function loadAudit() {
    const rows = await api("GET", `audit?kind=${$("audit-kind").value}&limit=200`);
    const body = $("audit-rows");
    if (!rows.length) return empty(body, 5, "Записей нет.");
    body.replaceChildren(...rows.map((r) => {
      const tr = el("tr");
      td(tr, when(r.executed_at));
      td(tr, KIND[r.kind] || r.kind);
      const details = el("details", { className: "row-details" }, el("summary", { textContent: r.title }));
      const dump = [r.parameters ? JSON.stringify(r.parameters, null, 2) : "", r.stdout || "", r.stderr || ""].filter(Boolean).join("\n\n");
      details.append(el("pre", { className: "output", textContent: dump || "Без подробностей" }));
      td(tr, details);
      td(tr, r.triggered_by);
      td(tr, r.exit_code == null ? "—" : r.exit_code === 0 ? "успешно" : r.exit_code === 403 ? "заблокировано" : `код ${r.exit_code}`);
      return tr;
    }));
  }
  $("audit-kind").addEventListener("change", () => loadAudit().catch(fail));
  $("audit-reload").addEventListener("click", () => loadAudit().catch(fail));
  tabs.audit = loadAudit;

  // ---------------------------------------------------------------- runbooks

  let runbook = null;

  async function loadRunbooks() {
    const [list, health] = await Promise.all([api("GET", "runbooks"), fetch("/api/v1/health").then((r) => r.json()).catch(() => ({}))]);
    $("runbook-mode").textContent = health.runbook_mode === "real"
      ? `Режим: боевой${health.runbook_writes_enabled ? ", изменения в AD включены" : ", изменения в AD выключены"}`
      : "Режим: эмуляция. Сервер показывает результат и ничего не выполняет.";
    const box = $("runbook-list");
    box.replaceChildren();
    let cat = null;
    for (const rb of list) {
      if (rb.category !== cat) { cat = rb.category; box.append(el("div", { className: "rb-cat", textContent: cat })); }
      box.append(el("button", {
        type: "button", className: `rb-item${runbook && runbook.id === rb.id ? " active" : ""}`,
        textContent: `${rb.critical ? "⚠ " : ""}${rb.title}`, title: rb.description,
        onclick: () => { runbook = rb; renderRunbook(); loadRunbooks().catch(fail); },
      }));
    }
  }

  function renderRunbook() {
    $("runbook-form").hidden = !runbook;
    if (!runbook) return;
    $("runbook-title").textContent = runbook.title;
    $("runbook-desc").textContent = runbook.description + (runbook.critical ? " Это важное действие: потребуется подтверждение." : "");
    $("runbook-output").hidden = true;
    $("runbook-msg").textContent = "";
    $("runbook-params").replaceChildren(...runbook.params.map((p) => {
      const input = p.kind === "choice"
        ? el("select", { name: p.name }, ...p.choices.map((c) => new Option(c.label, c.value)))
        : el("input", { name: p.name, placeholder: p.placeholder || "", required: p.required, autocomplete: "off" });
      return el("label", {}, `${p.label}${p.required ? "" : " (необязательно)"}`, input);
    }));
  }

  $("runbook-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const params = {};
    for (const input of $("runbook-params").querySelectorAll("[name]")) if (input.value.trim()) params[input.name] = input.value.trim();
    if (runbook.critical && !confirm(`«${runbook.title}» меняет данные. Запустить? Запуск попадёт в журнал.`)) return;
    $("runbook-run").disabled = true;
    try {
      const r = await api("POST", `runbooks/${runbook.id}/run`, {
        params, confirm: runbook.critical, operator: OPERATOR, auth_method: runbook.critical ? "web_confirm" : undefined,
      });
      const out = $("runbook-output");
      out.hidden = false;
      out.textContent = [r.simulated ? "[эмуляция]" : "", r.stdout, r.stderr, r.secret ? `Временный пароль (показан один раз): ${r.secret}` : ""].filter(Boolean).join("\n");
      $("runbook-msg").textContent = `код ${r.exit_code}, ${r.duration_ms} мс`;
    } catch (e) { fail(e); } finally { $("runbook-run").disabled = false; }
  });
  tabs.runbooks = loadRunbooks;

  // ---------------------------------------------------------------- printers

  async function loadPrinters() {
    const printers = await api("GET", "printers");
    const box = $("printer-cards");
    if (!printers.length) { box.replaceChildren(el("p", { className: "muted", textContent: "Принтеров пока нет." })); return; }
    box.replaceChildren(...printers.map((p) => el("div", { className: "card" },
      el("strong", { textContent: p.name }),
      el("span", { className: "muted small", textContent: [p.ip, p.model, p.is_online ? "в сети" : "не отвечает", p.total_pages != null ? `${p.total_pages} стр.` : ""].filter(Boolean).join(" · ") }),
      ...p.consumables.map((c) => el("div", {},
        el("div", { className: "small", textContent: `${c.name}: ${c.level_percent}%` }),
        el("div", { className: "bar" }, (() => { const i = el("i"); i.style.width = `${c.level_percent}%`; i.style.background = c.color_hex; return i; })()))),
      p.low_supply ? el("span", { className: "sev-WARNING small", textContent: "Заканчивается расходник" }) : "",
      el("button", { textContent: "Опросить сейчас", onclick: async () => { try { await api("POST", `printers/${p.id}/refresh`); await loadPrinters(); } catch (e) { fail(e); } } }))));
  }
  $("printer-reload").addEventListener("click", () => loadPrinters().catch(fail));
  tabs.printers = loadPrinters;

  // ---------------------------------------------------------------- credentials

  async function loadCreds() {
    const rows = await api("GET", "credentials");
    const body = $("cred-rows");
    if (!rows.length) return empty(body, 6, "Записей пока нет.");
    body.replaceChildren(...rows.map((c) => {
      const tr = el("tr");
      td(tr, c.name); td(tr, c.kind); td(tr, c.username || "—"); td(tr, c.has_secret ? "сохранён" : "нет"); td(tr, String(c.devices));
      td(tr, el("button", {
        className: "danger", textContent: "Удалить",
        onclick: async () => {
          if (!confirm(`Удалить «${c.name}»?`)) return;
          try { await api("DELETE", `credentials/${c.id}`); await loadCreds(); } catch (e) { fail(e); }
        },
      }));
      return tr;
    }));
  }
  $("cred-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    try {
      $("cred-error").hidden = true;
      await api("POST", "credentials", {
        name: $("cred-name").value.trim(), kind: $("cred-kind").value, username: $("cred-user").value.trim() || null,
        secret: $("cred-secret").value || null, notes: $("cred-notes").value.trim() || null, operator: OPERATOR,
      });
      $("cred-form").reset();
      await loadCreds();
    } catch (e) { $("cred-error").hidden = false; $("cred-error").textContent = e.message; }
  });
  tabs.creds = loadCreds;

  // ---------------------------------------------------------------- switch details

  const detailError = (e) => { $("detail-error").hidden = !e; $("detail-error").textContent = e ? e.message : ""; };
  let part = "vlan";

  async function openDetails() {
    const d = window.NetAdmin.portsDevice();
    $("detail-heading").textContent = d.name;
    detailError(null);
    $("ports-dialog").close();
    showPart("vlan");
    $("detail-dialog").showModal();
  }

  function showPart(name) {
    part = name;
    for (const b of $("detail-tabs").children) b.classList.toggle("active", b.dataset.part === name);
    for (const p of ["vlan", "history", "traffic", "configs"]) $(`detail-${p}`).hidden = p !== name;
    ({ vlan: renderVlans, history: renderHistory, traffic: renderTraffic, configs: renderConfigs })[name]().catch((e) => detailError(e));
  }
  $("detail-tabs").addEventListener("click", (ev) => { const b = ev.target.closest("button[data-part]"); if (b) showPart(b.dataset.part); });
  $("detail-close").addEventListener("click", () => $("detail-dialog").close());
  $("port-details").addEventListener("click", () => openDetails().catch(fail));

  /** The app's VLAN view: each VLAN with the ports that carry it. */
  async function renderVlans() {
    const d = window.NetAdmin.portsDevice();
    const [ports, vlans] = await Promise.all([api("GET", `devices/${d.id}/ports`), api("GET", "vlans")]);
    const names = new Map(vlans.map((v) => [v.vlan_id, v]));
    const by = new Map();
    const add = (vid, p, tagged) => { if (vid != null) (by.get(vid) || by.set(vid, []).get(vid)).push(`${p.name}${tagged ? " (tagged)" : ""}`); };
    for (const p of ports) { add(p.access_vlan, p, false); for (const v of p.tagged_vlans || []) add(v, p, true); }
    const box = $("detail-vlan");
    if (!by.size) { box.replaceChildren(el("p", { className: "muted", textContent: "VLAN на портах не назначены." })); return; }
    box.replaceChildren(...[...by.keys()].sort((a, b) => a - b).map((vid) => {
      const v = names.get(vid);
      const head = el("strong", { textContent: `VLAN ${vid}${v ? ` · ${v.name}` : " · не заведён"}` });
      if (v) head.style.color = v.color_hex;
      return el("div", { className: "card" }, head, el("span", { className: "small", textContent: by.get(vid).join(", ") }));
    }));
  }

  async function renderHistory() {
    const d = window.NetAdmin.portsDevice();
    const rows = await api("GET", `devices/${d.id}/history`);
    const box = $("detail-history");
    if (!rows.length) { box.replaceChildren(el("p", { className: "muted", textContent: "Изменений пока не было." })); return; }
    const label = { change: "изменение", blocked: "заблокировано", failed: "ошибка", config_backup: "копия конфигурации" };
    box.replaceChildren(el("table", { className: "grid" },
      el("thead", {}, el("tr", {}, ...["Время", "Что", "Итог", "Кто"].map((t) => el("th", { textContent: t })))),
      el("tbody", {}, ...rows.map((r) => {
        const tr = el("tr");
        td(tr, when(r.at));
        td(tr, el("div", {}, el("strong", { textContent: r.title }), el("div", { className: "small", textContent: r.summary })));
        td(tr, label[r.kind] || r.kind);
        td(tr, r.actor);
        return tr;
      }))));
  }

  async function renderTraffic() {
    const d = window.NetAdmin.portsDevice();
    const t = await api("GET", `devices/${d.id}/traffic?hours=${$("traffic-hours").value}`);
    $("traffic-chart").hidden = true;
    const body = $("traffic-rows");
    if (!t.ports.length) return empty(body, 4, "Замеров пока нет: сервер собирает трафик по расписанию.");
    body.replaceChildren(...t.ports.map((p) => {
      const tr = el("tr", { style: "cursor:pointer", title: "Показать график" });
      td(tr, `${p.port_name}${p.is_uplink ? " ↑" : ""}`);
      td(tr, `${bps(p.last_in_bps)} / ${bps(p.last_out_bps)}`);
      td(tr, `${bps(p.avg_in_bps)} / ${bps(p.avg_out_bps)}`);
      td(tr, `${p.peak_utilization}%`);
      tr.addEventListener("click", () => chart(p).catch(detailError));
      return tr;
    }));
  }
  $("traffic-hours").addEventListener("change", () => renderTraffic().catch(detailError));

  async function chart(p) {
    const t = await api("GET", `ports/${p.port_id}/traffic?hours=${$("traffic-hours").value}`);
    const cv = $("traffic-chart");
    cv.hidden = false;
    const c = cv.getContext("2d");
    const W = cv.width, H = cv.height, pad = 36;
    c.clearRect(0, 0, W, H);
    const max = Math.max(1, ...t.points.flatMap((x) => [x.in_bps, x.out_bps]));
    const n = t.points.length;
    c.font = "12px system-ui, sans-serif";
    c.fillStyle = "#8b95a5";
    c.fillText(`${p.port_name} · максимум ${bps(max)}`, pad, 16);
    const line = (key, color) => {
      c.beginPath();
      t.points.forEach((x, i) => {
        const px = pad + (n > 1 ? (i / (n - 1)) * (W - pad * 2) : 0), py = H - pad - (x[key] / max) * (H - pad * 2);
        if (i) c.lineTo(px, py); else c.moveTo(px, py);
      });
      c.strokeStyle = color; c.lineWidth = 2; c.stroke();
    };
    line("in_bps", "#4c9eff");
    line("out_bps", "#30d158");
    c.fillStyle = "#4c9eff"; c.fillText("входящий", W - 170, 16);
    c.fillStyle = "#30d158"; c.fillText("исходящий", W - 90, 16);
    if (!n) { c.fillStyle = "#8b95a5"; c.fillText("Замеров за период нет", pad, H / 2); }
  }

  // Stored running-configs: take a copy, read it, compare with the live one or with another copy. Read-only.
  let picked = null, versus = null;

  async function renderConfigs() {
    const d = window.NetAdmin.portsDevice();
    const list = await api("GET", `devices/${d.id}/configs`);
    $("config-output").hidden = true;
    if (!list.some((c) => c.id === picked)) picked = list[0] ? list[0].id : null;
    if (!list.some((c) => c.id === versus)) versus = null;
    const body = $("config-rows");
    if (!list.length) return empty(body, 6, "Копий пока нет: нажмите «Снять копию сейчас».");
    body.replaceChildren(...list.map((c) => {
      const tr = el("tr");
      td(tr, el("input", { type: "radio", name: "cfg-base", checked: c.id === picked, onchange: () => { picked = c.id; } }));
      td(tr, when(c.captured_at));
      td(tr, c.triggered_by);
      td(tr, c.config_hash.slice(0, 10));
      td(tr, el("input", {
        type: "checkbox", checked: c.id === versus, title: "Сравнить выбранную копию с этой",
        onchange: (ev) => { versus = ev.target.checked ? c.id : null; for (const b of body.querySelectorAll("input[type=checkbox]")) b.checked = b === ev.target && ev.target.checked; if (versus) showDiff(); },
      }));
      td(tr, el("button", { textContent: "Показать", onclick: async () => {
        const full = await api("GET", `devices/${d.id}/configs/${c.id}`).catch((e) => { detailError(e); return null; });
        if (full) output(full.content);
      } }));
      return tr;
    }));
  }

  function output(text) { const o = $("config-output"); o.hidden = false; o.textContent = text; }

  async function showDiff() {
    const d = window.NetAdmin.portsDevice();
    if (!picked) return;
    detailError(null);
    try {
      const q = new URLSearchParams({ base_id: picked });
      if (versus) q.set("other_id", versus);
      const r = await api("GET", `devices/${d.id}/configs/diff?${q}`);
      output(r.changed ? r.diff : "Различий нет.");
    } catch (e) { detailError(e); }
  }

  $("config-backup").addEventListener("click", async () => {
    const d = window.NetAdmin.portsDevice();
    try { detailError(null); await api("POST", `devices/${d.id}/configs/backup`); toast("Копия сохранена"); await renderConfigs(); } catch (e) { detailError(e); }
  });
  $("config-live").addEventListener("click", () => { versus = null; showDiff(); });

  $("port-refresh").addEventListener("click", async () => {
    const d = window.NetAdmin.portsDevice();
    try {
      await api("POST", `devices/${d.id}/refresh`);
      toast("Состояние портов прочитано");
      await window.NetAdmin.renderPorts();
    } catch (e) { fail(e); }
  });

  // ---------------------------------------------------------------- config import

  let previewed = false;
  const importError = (e) => { $("import-error").hidden = !e; $("import-error").textContent = e ? e.message : ""; };

  $("port-import").addEventListener("click", () => {
    $("ports-dialog").close();
    $("import-text").value = "";
    $("import-result").hidden = true;
    $("import-apply").disabled = true;
    previewed = false;
    importError(null);
    $("import-dialog").showModal();
  });
  $("import-text").addEventListener("input", () => { $("import-apply").disabled = true; previewed = false; });
  $("import-cancel").addEventListener("click", () => $("import-dialog").close());

  async function runImport(apply) {
    const d = window.NetAdmin.portsDevice();
    importError(null);
    try {
      const r = await api("POST", `devices/${d.id}/config-import`, { text: $("import-text").value, apply });
      const lines = [`${r.kind}${r.hostname ? ` · ${r.hostname}` : ""}`, `Портов с изменениями: ${r.changed_ports}`];
      for (const p of r.ports.filter((x) => x.matched && Object.keys(x.changes).length)) {
        lines.push(`${p.port}: ${Object.entries(p.changes).map(([k, [a, b]]) => `${k} ${a ?? "—"} → ${b ?? "—"}`).join(", ")}`);
      }
      if (r.unmatched_interfaces.length) lines.push(`Нет такого порта в инвентаре: ${r.unmatched_interfaces.join(", ")}`);
      const unknown = Object.keys(r.unknown_vlans);
      if (unknown.length) lines.push(`VLAN не заведены (вкладка VLAN): ${unknown.join(", ")}`);
      $("import-result").hidden = false;
      $("import-result").textContent = (apply ? "Применено к инвентарю.\n" : "") + lines.join("\n");
      previewed = !apply;
      $("import-apply").disabled = apply || !r.changed_ports;
    } catch (e) { importError(e); }
  }
  $("import-preview").addEventListener("click", () => runImport(false));
  $("import-apply").addEventListener("click", () => { if (previewed) runImport(true); });

  // ---------------------------------------------------------------- exports on the device list

  async function download(path, name) {
    const blob = await api("GET", path);
    const url = URL.createObjectURL(blob);
    el("a", name ? { href: url, download: name } : { href: url, target: "_blank", rel: "noopener" }).click();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  }

  {
    const bar = document.querySelector("#tab-devices .toolbar");
    const spacer = bar.querySelector(".spacer");
    spacer.after(
      el("button", { textContent: "Этикетки QR", title: "Страница этикеток для печати (выбранные или все)", onclick: () => {
        const ids = [...window.NetAdmin.picked].map((id) => `ids=${id}`).join("&");
        download(`export/labels.html${ids ? `?${ids}` : ""}`).catch(fail);
      } }),
      el("button", { textContent: "NetBox CSV", title: "Выгрузка инвентаря для NetBox", onclick: () => download("export/netbox.zip", "itmanager-netbox.zip").catch(fail) }),
    );
  }
})();
