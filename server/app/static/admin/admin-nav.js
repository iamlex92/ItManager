"use strict";
// Moving around: collapsing the list of sections, and Ctrl+K (or «/» outside a field) to find a section or a device by
// name, address, MAC or the number printed on its label.
(() => {
  const { $, api, openTab } = window.NetAdmin;
  const store = {
    get(key) { try { return localStorage.getItem(key); } catch { return null; } },
    set(key, value) { try { localStorage.setItem(key, value); } catch { /* private mode */ } },
  };

  // The list of sections folds to its icons; the choice stays.
  const side = $("tabs");
  const fold = (on) => {
    side.classList.toggle("collapsed", on);
    $("side-toggle").textContent = on ? "»" : "«";
    $("side-toggle").setAttribute("aria-label", on ? "Развернуть список разделов" : "Свернуть список разделов");
    store.set("netadmin.sideCollapsed", on ? "1" : "");
  };
  fold(store.get("netadmin.sideCollapsed") === "1");
  $("side-toggle").addEventListener("click", () => fold(!side.classList.contains("collapsed")));
  // A folded list still says what each icon is.
  side.querySelectorAll("button[data-tab]").forEach((b) => { b.title ||= b.textContent.trim(); });

  const dlg = $("palette"), input = $("palette-input"), list = $("palette-list");
  let items = [], at = 0, timer = 0, seq = 0;

  const sections = () => [...side.querySelectorAll("button[data-tab]")].map((b) => ({
    label: b.textContent.replace(/\d+$/, "").trim(), hint: "раздел", run: () => openTab(b.dataset.tab),
  }));

  function draw() {
    list.replaceChildren(...items.map((it, i) => {
      const li = document.createElement("li");
      li.setAttribute("role", "option");
      li.setAttribute("aria-selected", String(i === at));
      li.className = i === at ? "on" : "";
      li.append(Object.assign(document.createElement("span"), { textContent: it.label }),
        Object.assign(document.createElement("span"), { className: "muted small", textContent: it.hint }));
      li.addEventListener("click", () => choose(i));
      return li;
    }));
  }
  function choose(i) {
    const it = items[i];
    if (!it) return;
    dlg.close();
    it.run();
  }

  async function search() {
    const q = input.value.trim();
    const mine = ++seq;
    const found = q ? sections().filter((s) => s.label.toLowerCase().includes(q.toLowerCase())) : sections();
    items = found;
    at = 0;
    draw();
    if (q.length < 2) return;
    try {
      const devices = await api("GET", `search?q=${encodeURIComponent(q)}`);
      if (mine !== seq) return; // a newer search is on its way
      items = [...found, ...devices.map((d) => ({
        label: d.name, hint: [window.NetAdmin.typeOf(d).label, d.ip, ...(d.extra_ips || [])].filter(Boolean).join(" · "),
        run: () => window.NetAdmin.openCard(d.id),
      }))];
      draw();
    } catch { /* the sections still work */ }
  }

  function open() {
    if (!window.NetAdmin.signedIn()) return;
    input.value = "";
    dlg.showModal();
    search();
    input.focus();
  }
  input.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(search, 180); });
  input.addEventListener("keydown", (ev) => {
    if (ev.key === "ArrowDown") { ev.preventDefault(); at = Math.min(at + 1, items.length - 1); draw(); }
    else if (ev.key === "ArrowUp") { ev.preventDefault(); at = Math.max(at - 1, 0); draw(); }
    else if (ev.key === "Enter") { ev.preventDefault(); choose(at); }
  });
  dlg.addEventListener("click", (ev) => { if (ev.target === dlg) dlg.close(); });
  document.addEventListener("keydown", (ev) => {
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(ev.target.tagName) || ev.target.isContentEditable;
    if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === "k") { ev.preventDefault(); open(); }
    else if (ev.key === "/" && !typing && !document.querySelector("dialog[open]")) { ev.preventDefault(); open(); }
  });
})();
