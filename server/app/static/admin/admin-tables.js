"use strict";
// Tables of the console: a click on a header sorts by that column, a table can be exported to CSV, and the choices that
// are worth keeping (how the device list is grouped) are remembered in this browser. Works on every table with the class
// "grid", whenever it is drawn: the handlers sit on the document, nothing is wired per table.
(() => {
  const { toast } = window.NetAdmin;
  const store = {
    get(key) { try { return localStorage.getItem(key); } catch { return null; } },
    set(key, value) { try { localStorage.setItem(key, value); } catch { /* private mode */ } },
  };

  const textOf = (cell) => (cell ? (cell.dataset.sort ?? cell.textContent).trim() : "");
  // "10.10.20.9" before "10.10.20.11": addresses are compared by their numbers; so are plain numbers and «12 %».
  const key = (s) => {
    const ip = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(s);
    if (ip) return { n: ip.slice(1).reduce((a, b) => a * 256 + Number(b), 0) };
    const n = /^-?\d+([.,]\d+)?/.exec(s);
    if (n && /^-?[\d.,]+\s*(%|ГБ|МБ|Гбит\/с|Мбит\/с|мс|с|мин|ч|дн\.?)?$/.test(s)) return { n: parseFloat(s.replace(",", ".")) };
    return { s: s.toLowerCase() };
  };
  const compare = (a, b) => {
    if (a.n !== undefined && b.n !== undefined) return a.n - b.n;
    if (a.n !== undefined) return -1;
    if (b.n !== undefined) return 1;
    return a.s.localeCompare(b.s, "ru", { numeric: true });
  };

  function sortTable(th) {
    const table = th.closest("table");
    const body = table.tBodies[0];
    if (!body) return;
    const col = [...th.parentElement.children].indexOf(th);
    const dir = th.getAttribute("aria-sort") === "ascending" ? "descending" : "ascending";
    for (const other of th.parentElement.children) other.removeAttribute("aria-sort");
    th.setAttribute("aria-sort", dir);
    // Rows are sorted inside their section: a group header («Коммутаторы · 4») stays where it is.
    const rows = [...body.rows];
    const sections = [];
    for (const r of rows) {
      if (r.classList.contains("group") || !sections.length) sections.push({ head: r.classList.contains("group") ? r : null, rows: [] });
      if (!r.classList.contains("group")) sections.at(-1).rows.push(r);
    }
    const order = [];
    for (const s of sections) {
      // A one-cell row is a message («Ничего нет»), not data: it keeps to the end of its section.
      const data = s.rows.filter((r) => r.cells.length > 1);
      const rest = s.rows.filter((r) => r.cells.length <= 1);
      const decorated = data.map((r) => ({ r, k: key(textOf(r.cells[col])) }));
      decorated.sort((x, y) => (dir === "ascending" ? 1 : -1) * compare(x.k, y.k));
      order.push(...(s.head ? [s.head] : []), ...decorated.map((d) => d.r), ...rest);
    }
    body.append(...order);
    if (table.id) store.set(`netadmin.sort.${table.id}`, `${col}:${dir}`);
  }

  document.addEventListener("click", (ev) => {
    const th = ev.target.closest("table.grid thead th");
    if (!th || ev.target.closest("input, select, button, a")) return;
    if (!th.textContent.trim()) return; // an action column has no title
    sortTable(th);
  });
  // The pointer shows which headers sort; set once per table as it appears.
  new MutationObserver((records) => {
    for (const rec of records) {
      for (const node of rec.addedNodes) {
        if (node.nodeType !== 1) continue;
        for (const th of node.matches?.("table.grid") ? node.querySelectorAll("thead th") : node.querySelectorAll?.("table.grid thead th") || []) {
          if (th.textContent.trim()) { th.classList.add("sortable"); th.title ||= "Нажмите, чтобы отсортировать"; }
        }
      }
    }
  }).observe(document.body, { childList: true, subtree: true });
  document.querySelectorAll("table.grid thead th").forEach((th) => { if (th.textContent.trim()) th.classList.add("sortable"); });

  // The table as CSV (Excel opens it: semicolon, UTF-8 with a mark). The rows that are shown, in the order shown.
  function csvOf(table) {
    const cell = (c) => `"${textOf(c).replace(/\s+/g, " ").replace(/"/g, '""')}"`;
    const lines = [...table.tHead.rows[0].cells].map(cell).join(";");
    const rows = [...table.tBodies[0].rows].filter((r) => r.cells.length > 1 && !r.hidden).map((r) => [...r.cells].map(cell).join(";"));
    return "﻿" + [lines, ...rows].join("\r\n");
  }
  function exportTable(table, name) {
    const url = URL.createObjectURL(new Blob([csvOf(table)], { type: "text/csv;charset=utf-8" }));
    Object.assign(document.createElement("a"), { href: url, download: `${name || "таблица"}.csv` }).click();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }
  // A small «CSV» at the corner of every table that has a heading to say what it is.
  document.addEventListener("contextmenu", (ev) => {
    const th = ev.target.closest("table.grid thead th");
    if (!th) return;
    ev.preventDefault();
    const table = th.closest("table");
    exportTable(table, table.id || document.querySelector(".tab:not([hidden]) h2")?.textContent || "таблица");
    toast("Таблица выгружена в CSV");
  });
  window.NetAdmin.exportTable = exportTable;

  // The list of devices keeps its grouping between visits.
  const group = document.getElementById("group-by");
  if (group) {
    const saved = store.get("netadmin.groupBy");
    if (saved && [...group.options].some((o) => o.value === saved)) group.value = saved;
    group.addEventListener("change", () => store.set("netadmin.groupBy", group.value));
  }
})();
