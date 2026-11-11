"use strict";
// The network scanner tab: scan subnets, review what was found, add it to the inventory.
// Split out of admin.js; builds on it through window.NetAdmin.
(() => {
  const { $, api, toast, fail, OPERATOR, typeOf, tabs } = window.NetAdmin;

  function cell(tr, text) {
    const td = document.createElement("td");
    td.textContent = text ?? "—";
    tr.appendChild(td);
    return td;
  }

  let scan = null, scanTimer = 0;

  async function loadLatestScan() {
    if (scan) { renderScan(); return; }
    try { scan = await api("GET", "discovery/scans/latest"); renderScan(); if (scan.status === "running") pollScan(); }
    catch (e) { if (e.status !== 404) throw e; }
  }

  $("scan-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const subnets = $("scan-subnets").value.split(/[\s,;]+/).map((s) => s.trim()).filter(Boolean);
    if (!subnets.length) { toast("Укажите хотя бы одну подсеть, например 10.99.0.0/24", true); return; }
    try {
      scan = await api("POST", "discovery/scans", {
        subnets, community: $("scan-community").value || "public", operator: OPERATOR,
      });
      $("scan-result").textContent = "";
      renderScan();
      pollScan();
    } catch (e) { fail(e); }
  });

  function pollScan() {
    clearTimeout(scanTimer);
    scanTimer = setTimeout(async () => {
      try { scan = await api("GET", `discovery/scans/${scan.id}`); renderScan(); } catch (e) { fail(e); return; }
      if (scan.status === "running") pollScan();
    }, 1500);
  }

  function renderScan() {
    const status = $("scan-status");
    $("scan-start").disabled = scan && scan.status === "running";
    if (!scan) { status.textContent = ""; return; }
    const mode = scan.mode === "emulator" ? "эмулятор" : "реальная сеть";
    status.textContent = scan.status === "running"
      ? scan.total && scan.probed >= scan.total
        ? `Адреса проверены, определяю устройства: порты, имена, SNMP… (${mode})`
        : `Идёт сканирование: ${scan.probed} из ${scan.total} адресов (${mode})`
      : scan.status === "failed"
        ? `Не удалось: ${scan.error || "ошибка"}`
        : `Готово: ${scan.devices.length} устройств, ${scan.links.length} связей (${mode}, ${scan.subnets.join(", ")})`;
    const warnings = $("scan-warnings");
    warnings.replaceChildren(...(scan.warnings || []).map((w) => {
      const li = document.createElement("li");
      li.textContent = w;
      return li;
    }));
    warnings.hidden = !warnings.children.length;
    const rows = $("scan-rows");
    rows.replaceChildren();
    for (const d of scan.devices) {
      const tr = document.createElement("tr");
      const box = document.createElement("input");
      box.type = "checkbox";
      box.value = d.ip;
      box.checked = !d.inventory_id;
      box.disabled = !!d.inventory_id;
      box.addEventListener("change", updateImport);
      const td = document.createElement("td");
      td.appendChild(box);
      tr.appendChild(td);
      cell(tr, d.ip);
      cell(tr, d.name);
      cell(tr, typeOf(d).label);
      cell(tr, [d.vendor, d.model].filter(Boolean).join(" ") || null);
      const how = cell(tr, foundHow(d));
      if (d.description) how.title = d.description;
      cell(tr, d.inventory_name);
      rows.appendChild(tr);
    }
    updateImport();
  }

  function foundHow(d) {
    const how = d.snmp ? "SNMP" : d.seen_by === "tcp" ? "TCP (ping закрыт)" : d.seen_by === "arp" ? "только ARP" : "ping";
    const ports = (d.open_ports || []).length ? `, порты ${d.open_ports.join(", ")}` : "";
    return how + ports;
  }

  const scanBoxes = () => [...$("scan-rows").querySelectorAll("input[type=checkbox]:not(:disabled)")];
  function updateImport() {
    const picked = scanBoxes().filter((b) => b.checked).length;
    $("scan-import").disabled = !scan || scan.status !== "done" || (!picked && !$("scan-links").checked);
    $("scan-import").textContent = picked ? `Добавить выбранные (${picked})` : "Добавить связи";
  }
  $("scan-all").addEventListener("change", (ev) => { for (const b of scanBoxes()) b.checked = ev.target.checked; updateImport(); });
  $("scan-links").addEventListener("change", updateImport);
  $("scan-import").addEventListener("click", async () => {
    const ips = scanBoxes().filter((b) => b.checked).map((b) => b.value);
    // Links only: name the devices already in the inventory, so nothing new is created.
    const all = ips.length ? ips : scan.devices.filter((d) => d.inventory_id).map((d) => d.ip);
    if (!all.length) { toast("Отметьте устройства, которые нужно добавить.", true); return; }
    try {
      const r = await api("POST", `discovery/scans/${scan.id}/import`, {
        ips: all, links: $("scan-links").checked, operator: OPERATOR,
      });
      $("scan-result").textContent =
        `Добавлено устройств: ${r.created.length}, связей: ${r.links.length}` +
        (r.skipped.length ? `. Пропущено: ${r.skipped.join("; ")}` : "") + ". Они уже видны на карте и в приложении.";
      scan = await api("GET", `discovery/scans/${scan.id}`);
      renderScan();
    } catch (e) { fail(e); }
  });

  // A missing scan is not an error: the tab just starts empty.
  tabs.scan = () => loadLatestScan().catch(() => {});
})();
