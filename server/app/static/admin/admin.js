"use strict";
// NetAdmin web console. Talks to the same /api/v1 as the phone with the same API token,
// so whatever is entered here is what the app shows. Inventory only: nothing here reaches equipment.
(() => {
  const $ = (id) => document.getElementById(id);
  // Id, label and the pictogram's colour: one hue per kind, as in the app. Green and red stay free for the online dot.
  const TYPES = [
    ["SWITCH", "Коммутатор", "#4c9eff"], ["ROUTER", "Маршрутизатор", "#64d2ff"],
    ["FIREWALL", "Межсетевой экран", "#ff8a80"], ["ACCESS_POINT", "Точка доступа", "#ffd60a"],
    ["SERVER", "Сервер", "#bf8cff"], ["VIRTUAL", "Виртуальная машина", "#8e9bff"],
    ["WORKSTATION", "Рабочее место", "#e6edf5"], ["PRINTER", "МФУ", "#5ad4b0"], ["CAMERA", "Камера", "#ff9f43"],
    ["PHONE", "IP-телефон", "#ff7eb6"], ["UPS", "ИБП", "#d4e157"], ["PATCH_PANEL", "Патч-панель", "#aeb7c4"],
    ["SOCKET", "Розетка", "#aeb7c4"], ["RACK", "Стойка", "#c9a46c"], ["UNKNOWN", "Устройство", "#8d97a5"],
  ];
  const TYPE = Object.fromEntries(TYPES.map(([id, label, color]) => [id, { label, color }]));
  const typeOf = (d) => TYPE[d.type] || TYPE.UNKNOWN;
  const PASSIVE = new Set(["SOCKET", "PATCH_PANEL"]);
  const PENS = ["#FFD60A", "#FF453A", "#30D158", "#0A84FF", "#FFFFFF"];
  const MAX_PLAN = 15 * 1024 * 1024;
  const KEY = "netadmin.token";
  const OPERATOR = "веб-консоль";

  let token = "";
  try { token = sessionStorage.getItem(KEY) || localStorage.getItem(KEY) || ""; } catch { /* storage blocked */ }

  // ---------------------------------------------------------------- API

  class ApiError extends Error {
    constructor(status, code, message) { super(message); this.status = status; this.code = code; }
  }

  function messageFor(status, detail) {
    if (detail && typeof detail === "object" && !Array.isArray(detail) && detail.message) return detail.message;
    if (Array.isArray(detail)) {
      return "Проверьте поля: " + detail.map((d) => `${(d.loc || []).slice(-1)[0]}: ${d.msg}`).join("; ");
    }
    if (typeof detail === "string") return detail;
    return `Ошибка сервера (${status})`;
  }

  async function api(method, path, body, raw) {
    const headers = { Authorization: `Bearer ${token}` };
    let payload;
    if (raw) { payload = raw.body; headers["Content-Type"] = raw.type; }
    else if (body !== undefined) { payload = JSON.stringify(body); headers["Content-Type"] = "application/json"; }
    let r;
    try { r = await fetch(`/api/v1/${path}`, { method, headers, body: payload }); }
    catch { throw new ApiError(0, "network", "Нет связи с агентом"); }
    if (r.status === 401) {
      logout("Ключ не подошёл или сменился. Войдите снова.");
      throw new ApiError(401, "unauthorized", "Нужен ключ доступа");
    }
    if (!r.ok) {
      let detail = null;
      try { detail = (await r.json()).detail; } catch { /* not JSON */ }
      throw new ApiError(r.status, detail && detail.code, messageFor(r.status, detail));
    }
    if (r.status === 204) return null;
    return (r.headers.get("content-type") || "").includes("json") ? r.json() : r.blob();
  }

  let toastTimer = 0;
  function toast(text, error = false) {
    const t = $("toast");
    t.textContent = text;
    t.className = error ? "toast error" : "toast";
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, error ? 7000 : 3500);
  }
  const fail = (e) => toast(e.message || String(e), true);

  // ---------------------------------------------------------------- login

  function store(remember) {
    try {
      sessionStorage.setItem(KEY, token);
      if (remember) localStorage.setItem(KEY, token); else localStorage.removeItem(KEY);
    } catch { /* storage blocked: the key lives until the tab closes */ }
  }

  function logout(message) {
    token = "";
    try { sessionStorage.removeItem(KEY); localStorage.removeItem(KEY); } catch { /* ignore */ }
    $("app").hidden = true;
    $("login").hidden = false;
    $("login-error").hidden = !message;
    $("login-error").textContent = message || "";
    $("login-token").value = "";
  }

  $("login-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    token = $("login-token").value.trim();
    try {
      await api("GET", "floors");
      store($("login-remember").checked);
      start();
    } catch (e) {
      if (e.status !== 401) { $("login-error").hidden = false; $("login-error").textContent = e.message; }
    }
  });
  $("logout").addEventListener("click", () => logout(""));

  async function showMode() {
    try {
      const h = await (await fetch("/api/v1/health")).json();
      const hal = h.hal_mode === "emulator"
        ? (h.monitor_mode === "real" ? "коммутаторы: линки по SNMP, управление эмулируется" : "коммутаторы: эмулятор")
        : `коммутаторы: ${h.hal_mode} · запись ${h.device_writes_enabled ? "включена" : "выключена"}`;
      // Without polling the printer and ping figures are made up: that must be seen at a glance.
      const monitor = h.monitor_mode === "emulator" ? "опрос устройств выключен" : "опрос устройств включён";
      $("mode").textContent = `${hal} · ${monitor}`;
      $("mode").classList.toggle("warn", h.monitor_mode === "emulator");
      window.NetAdmin.monitorEmulated = h.monitor_mode === "emulator";
    } catch { $("mode").textContent = ""; }
  }

  // ---------------------------------------------------------------- tabs

  let currentTab = "map";
  $("tabs").addEventListener("click", (ev) => {
    const b = ev.target.closest("button[data-tab]");
    if (b) { closeMenus(); openTab(b.dataset.tab); }
  });

  // Theme: follows the system until the toggle is used; the choice is kept in this browser.
  {
    const root = document.documentElement;
    try { const saved = localStorage.getItem("netadmin-theme"); if (saved) root.dataset.theme = saved; } catch { /* private mode */ }
    $("theme-toggle").addEventListener("click", () => {
      const light = root.dataset.theme ? root.dataset.theme === "light" : matchMedia("(prefers-color-scheme: light)").matches;
      root.dataset.theme = light ? "dark" : "light";
      try { localStorage.setItem("netadmin-theme", root.dataset.theme); } catch { /* private mode */ }
    });
  }

  // «Сервер и доступ» is one section with two views; sections with no menu entry light the one they belong to.
  const TAB_HOME = { creds: "settings", printers: "devices" };
  document.addEventListener("click", (ev) => {
    const b = ev.target.closest(".subnav button[data-tab]");
    if (b) openTab(b.dataset.tab);
  });
  $("printers-link").addEventListener("click", () => openTab("printers"));

  // Small pop-up menus («Ещё», «+ Добавить»): one open at a time, closed by a click elsewhere or Esc.
  const MENUS = [["more-toggle", "more-menu"], ["add-toggle", "add-menu"]];
  function closeMenus(except) {
    for (const [btn, menu] of MENUS) {
      if (menu === except) continue;
      $(menu).hidden = true;
      $(btn).setAttribute("aria-expanded", "false");
    }
  }
  for (const [btn, menu] of MENUS) {
    $(btn).addEventListener("click", (ev) => {
      ev.stopPropagation();
      closeMenus(menu);
      $(menu).hidden = !$(menu).hidden;
      $(btn).setAttribute("aria-expanded", String(!$(menu).hidden));
    });
  }
  document.addEventListener("click", (ev) => { if (!ev.target.closest(".pop")) closeMenus(); });
  document.addEventListener("keydown", (ev) => { if (ev.key === "Escape") closeMenus(); });

  function openTab(name) {
    currentTab = name;
    const home = TAB_HOME[name] || name;
    for (const b of $("tabs").querySelectorAll("button[data-tab]")) b.classList.toggle("active", b.dataset.tab === home);
    // A section from «Ещё» lights «Ещё» itself, so the user sees where they are.
    $("more-toggle").classList.toggle("active", !!$("more-menu").querySelector(`[data-tab="${home}"]`));
    for (const b of document.querySelectorAll(".subnav button[data-tab]")) b.classList.toggle("active", b.dataset.tab === name);
    for (const s of document.querySelectorAll("main > .tab")) s.hidden = s.id !== `tab-${name}`;
    if (name === "map") loadMap().catch(fail);
    if (name === "devices") loadDevices().catch(fail);
    if (name === "vlans") loadVlans().catch(fail);
    if (window.NetAdmin.tabs[name]) Promise.resolve(window.NetAdmin.tabs[name]()).catch(fail);
    if (name === "scan") loadLatestScan().catch(() => {});
    if (name === "scripts") loadScripts().catch(fail);
    if (name === "peripherals") loadPeripherals().catch(fail);
    if (name === "topology") loadTopology().catch(fail);
    if (name === "templates") loadTemplates().catch(fail);
    if (name === "alerts") refreshAlertCount();
  }

  /** Open events on the «События» tab, as the badge on the phone's bottom bar. */
  async function refreshAlertCount() {
    try {
      const open = await api("GET", "alerts?is_resolved=false");
      $("alerts-count").textContent = open.length > 99 ? "99+" : String(open.length);
      $("alerts-count").hidden = !open.length;
    } catch { /* the badge simply stays as it was */ }
  }

  let alertTimer = 0;
  function start() {
    $("login").hidden = true;
    $("app").hidden = false;
    showMode();
    openTab(currentTab);
    refreshAlertCount();
    clearInterval(alertTimer);
    alertTimer = setInterval(refreshAlertCount, 60_000);
  }

  // ---------------------------------------------------------------- map state

  const S = {
    floor: null, floors: [], floorId: null, allHere: [], showVms: false, hiddenTypes: new Set(), devices: [], remote: new Map(), links: [], image: null, imageUrl: null,
    // The cable whose route is being edited, its picked bend, and a bend being dragged.
    selLink: null, selPoint: null, routeDrag: null,
    // «view» until «Правка» is on; editTool is the tool «Правка» comes back to.
    tool: "view", editTool: "move", pen: PENS[0], linkFrom: null, cable: "Cat6",
    // Layers as in the app: L1 cables, L2 VLAN colours, L3 subnets; a VLAN picked to stand out.
    layer: "L1", vlanFilter: null, vlans: new Map(),
    // Marks tool: the kind and colour of the next mark, the tail of an arrow being placed, a mark being dragged.
    markKind: "BADGE", markColor: "#FFD60A", arrowStart: null, markDrag: null,
    // A device found by search: ringed until the next click. Clusters drawn at the last frame.
    focus: null, clusters: [],
    // Zone being outlined (flat x, y list), and the colour for the next cable (null: the usual grey/blue).
    zoneDraft: null, cableColor: null,
    view: { scale: 1, ox: 0, oy: 0 },
    // Until the user zooms or pans, the view keeps fitting the whole floor as the window changes.
    touched: false,
    stroke: null, drag: null, planShift: null,
  };
  const canvas = $("map");
  const ctx = canvas.getContext("2d");

  function floorSize() {
    const f = S.floor;
    const maxX = Math.max(0, ...S.devices.map((d) => d.x)) + 200;
    const maxY = Math.max(0, ...S.devices.map((d) => d.y)) + 200;
    return {
      w: (f && f.width_px) || Math.max(1600, maxX),
      h: (f && f.height_px) || Math.max(1000, maxY),
    };
  }

  function planRect() {
    const f = S.floor;
    if (!f || !f.plan_image_url) return null;
    const size = floorSize();
    const r = f.plan_w && f.plan_h
      ? { x: f.plan_x || 0, y: f.plan_y || 0, w: f.plan_w, h: f.plan_h }
      : { x: 0, y: 0, w: size.w, h: size.h };
    if (S.planShift) { r.x += S.planShift.x; r.y += S.planShift.y; }
    return r;
  }

  async function loadMap() {
    const [floors, devices, links, vlans] = await Promise.all([
      api("GET", "floors"), api("GET", "devices"), api("GET", "connections"), api("GET", "vlans"),
    ]);
    S.floors = floors;
    S.vlans = new Map(vlans.map((v) => [v.vlan_id, v]));
    renderVlanFilter();
    allDevices = devices;
    const first = floors[0] || null;
    const previous = S.floor && S.floor.id;
    S.floor = floors.find((f) => f.id === S.floorId) || first;
    S.floorId = S.floor && S.floor.id;
    if (previous && previous !== S.floorId) S.touched = false;
    // As on the phone: devices without a floor (or on a floor that is gone) show on the first one.
    const known = new Set(floors.map((f) => f.id));
    const floorOf = (d) => (d.floor_id && known.has(d.floor_id) ? d.floor_id : first && first.id);
    const all = devices.map((d) => ({ ...d, x: d.x ?? 40, y: d.y ?? 40, floor_id: floorOf(d) }));
    // A virtual machine has no cable and is not physically there: the map leaves it out unless asked.
    S.allHere = all.filter((d) => !S.floor || d.floor_id === S.floor.id);
    // Kinds switched off in the legend leave the map with their cables.
    const shown = (d) => !S.hiddenTypes.has(d.type) && (d.type !== "VIRTUAL" || S.showVms);
    S.devices = S.allHere.filter(shown);
    const here = new Set(S.devices.map((d) => d.id));
    const byId = new Map(all.map((d) => [d.id, d]));
    const unseen = new Set(all.filter((d) => !shown(d)).map((d) => d.id));
    S.links = links.filter((l) => (here.has(l.device_a_id) || here.has(l.device_b_id))
      && byId.has(l.device_a_id) && byId.has(l.device_b_id) && !unseen.has(l.device_a_id) && !unseen.has(l.device_b_id));
    S.remote = new Map();
    for (const l of S.links) {
      for (const id of [l.device_a_id, l.device_b_id]) if (!here.has(id)) S.remote.set(id, byId.get(id));
    }
    if (S.selLink && !S.links.some((l) => l.id === S.selLink)) { S.selLink = null; S.selPoint = null; }
    renderFloorSelect();
    renderLegend();
    await loadPlanImage();
    if (!S.touched) fit();
    // A fresh server: say what to do instead of showing an empty grid.
    $("map-empty").hidden = !(S.tool === "view" && !devices.length && !(S.floor && S.floor.plan_image_url));
    draw();
    hint();
  }

  /** Pictograms present on this floor, with counts. */
  function renderLegend() {
    const box = $("legend");
    const counts = new Map();
    for (const d of S.allHere) counts.set(d.type, (counts.get(d.type) || 0) + 1);
    const rows = TYPES.filter(([id]) => counts.has(id)).map(([id, label]) => {
      const row = document.createElement("button");
      row.type = "button";
      row.className = "legend-row";
      row.classList.toggle("off", S.hiddenTypes.has(id));
      row.title = S.hiddenTypes.has(id) ? "Показать на карте" : "Скрыть с карты";
      row.append(glyphCanvas(id), `${label} · ${counts.get(id)}`);
      row.addEventListener("click", () => {
        if (S.hiddenTypes.has(id)) S.hiddenTypes.delete(id); else S.hiddenTypes.add(id);
        loadMap().catch(fail);
      });
      return row;
    });
    if (S.hiddenTypes.size) {
      const all = document.createElement("button");
      all.type = "button";
      all.textContent = "Показать всё";
      all.addEventListener("click", () => { S.hiddenTypes.clear(); loadMap().catch(fail); });
      rows.push(all);
    }
    const note = document.createElement("div");
    note.className = "muted small";
    note.textContent = "Нажмите на тип, чтобы скрыть или показать";
    box.replaceChildren(note, ...rows);
    $("legend-toggle").hidden = !rows.length;
    if (!rows.length) box.hidden = true;
  }
  $("legend-toggle").addEventListener("click", () => { $("legend").hidden = !$("legend").hidden; });
  $("show-vms").addEventListener("change", (ev) => { S.showVms = ev.target.checked; loadMap().catch(fail); });

  function floorTitle(f) {
    if (!f) return "другой этаж";
    return f.name || `${f.building_name} · этаж ${f.floor_number}`;
  }

  function renderFloorSelect() {
    const sel = $("floor-select");
    sel.replaceChildren(...[...S.floors].reverse().map((f) => {
      const o = document.createElement("option");
      o.value = f.id;
      o.textContent = floorTitle(f);
      return o;
    }));
    sel.hidden = S.floors.length === 0;
    if (S.floor) sel.value = S.floor.id;
  }

  // ---------------------------------------------------------------- zones and rooms

  const ZONE_COLORS = ["#0a84ff", "#30d158", "#ff9f0a", "#bf5af2", "#ff453a", "#64d2ff"];
  const zonesOf = (floor) => (floor && floor.zones) || [];

  /** Even-odd ray casting over a flat x, y list, as the app does. */
  function inPolygon(points, x, y) {
    const n = points.length / 2;
    if (n < 3) return false;
    let inside = false;
    for (let i = 0, j = n - 1; i < n; j = i++) {
      const xi = points[i * 2], yi = points[i * 2 + 1], xj = points[j * 2], yj = points[j * 2 + 1];
      if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
    }
    return inside;
  }

  function polygonArea(points) {
    let a = 0;
    const n = points.length / 2;
    for (let i = 0, j = n - 1; i < n; j = i++) a += points[j * 2] * points[i * 2 + 1] - points[i * 2] * points[j * 2 + 1];
    return Math.abs(a) / 2;
  }

  /** A point well inside the outline: the grid cell nearest the centre of its bounding box that lies inside. */
  function innerPoint(points) {
    const xs = points.filter((_, i) => i % 2 === 0), ys = points.filter((_, i) => i % 2 === 1);
    const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
    const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
    let best = { x: points[0], y: points[1] }, bestDist = Infinity;
    for (let i = 0; i <= 20; i++) {
      for (let j = 0; j <= 20; j++) {
        const x = x0 + ((x1 - x0) * i) / 20, y = y0 + ((y1 - y0) * j) / 20;
        const dist = Math.hypot(x - cx, y - cy);
        if (dist < bestDist && inPolygon(points, x, y)) { best = { x, y }; bestDist = dist; }
      }
    }
    return best;
  }

  /** [n] points inside the outline, nearest the middle first, for laying devices out in a room. */
  function spreadPoints(points, n) {
    const xs = points.filter((_, i) => i % 2 === 0), ys = points.filter((_, i) => i % 2 === 1);
    const x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
    const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
    // Spaced so the pictograms and their names do not overlap; tighter only when the room is small.
    const step = Math.max(24, Math.min(90, Math.sqrt(((x1 - x0) * (y1 - y0)) / Math.max(n, 1)) / 1.2));
    const found = [];
    for (let x = cx - Math.floor((cx - x0) / step) * step; x <= x1; x += step) {
      for (let y = cy - Math.floor((cy - y0) / step) * step; y <= y1; y += step) if (inPolygon(points, x, y)) found.push({ x, y });
    }
    if (!found.length) found.push(innerPoint(points));
    found.sort((a, b) => Math.hypot(a.x - cx, a.y - cy) - Math.hypot(b.x - cx, b.y - cy));
    return Array.from({ length: n }, (_, i) => found[i % found.length]);
  }

  /** The smallest zone (room or not) of the floor that holds the point. */
  function zoneAtPoint(floor, x, y, roomsOnly = false) {
    let best = null;
    for (const z of zonesOf(floor)) {
      if (roomsOnly && !z.is_room) continue;
      if (inPolygon(z.points, x, y) && (!best || polygonArea(z.points) < polygonArea(best.points))) best = z;
    }
    return best;
  }

  const floorById = (id) => S.floors.find((f) => f.id === id) || S.floors[0] || null;

  /** The room a device stands in, by its place on its floor's plan. */
  function roomOf(d) {
    if (d.x == null || d.y == null) return null;
    return zoneAtPoint(floorById(d.floor_id), d.x, d.y, true);
  }

  /** The corner of a finished outline under the pointer, in screen pixels. */
  function zoneVertexAt(sx, sy) {
    for (const zone of zonesOf(S.floor)) {
      for (let index = 0; index * 2 + 1 < zone.points.length; index++) {
        const [px, py] = toScreen(zone.points[index * 2], zone.points[index * 2 + 1]);
        if (Math.hypot(sx - px, sy - py) <= 8) return { zone, index };
      }
    }
    return null;
  }

  function fitDraftButtons() {
    const n = (S.zoneDraft || []).length / 2;
    $("zone-finish").disabled = n < 3;
    $("zone-undo").disabled = n < 1;
    $("zone-cancel").disabled = n < 1;
  }

  function zoneClick(sx, sy) {
    const [x, y] = toPlan(sx, sy);
    if (!S.zoneDraft) {
      const z = zoneAtPoint(S.floor, x, y);
      if (z) { openZoneDialog(z); return; }
      S.zoneDraft = [];
    }
    const n = S.zoneDraft.length;
    if (n) {
      const [lx, ly] = toScreen(S.zoneDraft[n - 2], S.zoneDraft[n - 1]);
      if (Math.hypot(sx - lx, sy - ly) < 6) return;
    }
    S.zoneDraft.push(round1(x), round1(y));
    fitDraftButtons();
    hint();
  }

  function finishZone() {
    if (!S.zoneDraft || S.zoneDraft.length < 6) { toast("Нужно хотя бы три угла.", true); return; }
    openZoneDialog({
      id: crypto.randomUUID(), name: "", is_room: true, points: [...S.zoneDraft],
      color: ZONE_COLORS[zonesOf(S.floor).length % ZONE_COLORS.length],
    }, true);
  }

  function cancelZone() {
    S.zoneDraft = null;
    fitDraftButtons();
    hint();
    draw();
  }

  async function saveZones(zones) {
    const floor = await ensureFloor();
    const saved = await api("PATCH", `floors/${floor.id}`, { zones });
    S.floor = saved;
    S.floors = S.floors.map((f) => (f.id === saved.id ? saved : f));
    draw();
  }

  let dialogZone = null, dialogZoneNew = false;
  function openZoneDialog(zone, isNew = false) {
    dialogZone = zone;
    dialogZoneNew = isNew;
    $("zone-heading").textContent = isNew ? "Новое помещение" : zone.name;
    $("zone-name").value = zone.name;
    $("zone-room").checked = zone.is_room;
    $("zone-color").value = zone.color;
    $("zone-delete").hidden = isNew;
    $("zone-error").hidden = true;
    $("zone-dialog").showModal();
    $("zone-name").focus();
  }
  $("zone-cancel-dialog").addEventListener("click", () => $("zone-dialog").close());
  $("zone-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const zone = { ...dialogZone, name: $("zone-name").value.trim(), is_room: $("zone-room").checked, color: $("zone-color").value };
    const rest = zonesOf(S.floor).filter((z) => z.id !== zone.id);
    try {
      await saveZones(dialogZoneNew ? [...rest, zone] : zonesOf(S.floor).map((z) => (z.id === zone.id ? zone : z)));
      if (dialogZoneNew) { S.zoneDraft = null; fitDraftButtons(); }
      $("zone-dialog").close();
      hint();
      draw();
    } catch (e) { $("zone-error").hidden = false; $("zone-error").textContent = e.message; }
  });
  $("zone-delete").addEventListener("click", async () => {
    if (!confirm(`Удалить «${dialogZone.name}» с карты? Устройства останутся на своих местах.`)) return;
    try { await saveZones(zonesOf(S.floor).filter((z) => z.id !== dialogZone.id)); $("zone-dialog").close(); }
    catch (e) { $("zone-error").hidden = false; $("zone-error").textContent = e.message; }
  });
  $("zone-finish").addEventListener("click", finishZone);
  $("zone-cancel").addEventListener("click", cancelZone);
  $("zone-undo").addEventListener("click", () => {
    if (!S.zoneDraft) return;
    S.zoneDraft.splice(-2, 2);
    if (!S.zoneDraft.length) S.zoneDraft = null;
    fitDraftButtons();
    hint();
    draw();
  });

  // ---------------------------------------------------------------- pictograms

  /** Draws the pictogram of a device type, [size] px wide, centred on (cx, cy). Same shapes on the map, list and legend. */
  function drawGlyph(c, type, cx, cy, size, color) {
    const r = size / 2;
    c.save();
    c.translate(cx, cy);
    c.scale(r, r);
    c.strokeStyle = color;
    c.fillStyle = color;
    c.lineWidth = 2.2 / r;
    c.lineJoin = "round";
    c.lineCap = "round";
    const box = (x, y, w, h) => { c.beginPath(); c.rect(x, y, w, h); c.stroke(); };
    const dot = (x, y, d) => { c.beginPath(); c.arc(x, y, d, 0, Math.PI * 2); c.fill(); };
    const line = (...pts) => {
      c.beginPath();
      c.moveTo(pts[0], pts[1]);
      for (let i = 2; i < pts.length; i += 2) c.lineTo(pts[i], pts[i + 1]);
      c.stroke();
    };
    switch (type) {
      case "SWITCH":
        box(-0.9, -0.45, 1.8, 0.9);
        for (const x of [-0.55, -0.18, 0.18, 0.55]) c.fillRect(x - 0.11, -0.05, 0.22, 0.25);
        break;
      case "ROUTER":
        box(-0.9, 0, 1.8, 0.7);
        line(-0.5, 0, -0.75, -0.8);
        line(0.5, 0, 0.75, -0.8);
        dot(-0.45, 0.35, 0.12);
        dot(0, 0.35, 0.12);
        break;
      case "FIREWALL":
        c.beginPath();
        c.moveTo(0, -0.95); c.lineTo(0.8, -0.62); c.lineTo(0.8, 0.02);
        c.quadraticCurveTo(0.78, 0.68, 0, 1); c.quadraticCurveTo(-0.78, 0.68, -0.8, 0.02);
        c.lineTo(-0.8, -0.62); c.closePath(); c.stroke();
        line(-0.8, -0.05, 0.8, -0.05);
        line(0, -0.95, 0, 1);
        break;
      case "SERVER":
        for (const y of [-0.9, -0.28, 0.34]) { box(-0.8, y, 1.6, 0.52); dot(0.45, y + 0.26, 0.1); }
        break;
      case "VIRTUAL":
        c.beginPath();
        c.arc(-0.42, 0.2, 0.42, 0, Math.PI * 2);
        c.arc(0.1, -0.12, 0.55, 0, Math.PI * 2);
        c.arc(0.55, 0.25, 0.38, 0, Math.PI * 2);
        c.fill();
        c.fillRect(-0.42, 0.2, 0.97, 0.43);
        break;
      case "WORKSTATION":
        box(-0.9, -0.8, 1.8, 1.15);
        line(0, 0.35, 0, 0.75);
        line(-0.45, 0.8, 0.45, 0.8);
        break;
      case "PRINTER":
        box(-0.9, -0.3, 1.8, 0.85);
        box(-0.5, -0.9, 1, 0.6);
        box(-0.5, 0.3, 1, 0.6);
        break;
      case "CAMERA":
        box(-0.95, -0.5, 1.25, 1);
        c.beginPath(); c.moveTo(0.3, -0.12); c.lineTo(0.95, -0.5); c.lineTo(0.95, 0.5); c.lineTo(0.3, 0.12); c.closePath(); c.stroke();
        break;
      case "ACCESS_POINT":
        for (const rr of [0.4, 0.8, 1.2]) { c.beginPath(); c.arc(0, 0.6, rr, Math.PI * 1.25, Math.PI * 1.75); c.stroke(); }
        dot(0, 0.6, 0.14);
        break;
      case "PHONE":
        box(-0.75, -0.1, 1.5, 0.95);
        c.beginPath(); c.rect(-0.9, -0.75, 1.8, 0.38); c.fill();
        for (const x of [-0.35, 0, 0.35]) { dot(x, 0.18, 0.08); dot(x, 0.5, 0.08); }
        break;
      case "UPS":
        box(-0.9, -0.55, 1.6, 1.1);
        c.fillRect(0.7, -0.22, 0.2, 0.44);
        c.beginPath(); c.moveTo(-0.05, -0.45); c.lineTo(-0.35, 0.05); c.lineTo(-0.05, 0.05); c.lineTo(-0.2, 0.45);
        c.lineTo(0.3, -0.1); c.lineTo(0, -0.1); c.lineTo(0.15, -0.45); c.closePath(); c.fill();
        break;
      case "PATCH_PANEL":
        box(-0.95, -0.38, 1.9, 0.76);
        for (const x of [-0.6, -0.2, 0.2, 0.6]) c.fillRect(x - 0.1, -0.1, 0.2, 0.2);
        break;
      case "RACK":
        box(-0.7, -0.95, 1.4, 1.9);
        for (const y of [-0.55, -0.15, 0.25, 0.65]) line(-0.7, y, 0.7, y);
        for (const y of [-0.75, -0.35, 0.05, 0.45]) dot(0.4, y, 0.06);
        break;
      case "SOCKET":
        box(-0.75, -0.75, 1.5, 1.5);
        c.fillRect(-0.35, -0.2, 0.7, 0.42);
        c.fillRect(-0.15, 0.22, 0.3, 0.16);
        break;
      default:
        c.scale(1 / r, 1 / r);
        c.font = `700 ${Math.round(size * 0.9)}px system-ui, sans-serif`;
        c.textAlign = "center";
        c.textBaseline = "middle";
        c.fillText("?", 0, 1);
    }
    c.restore();
  }

  /** A small canvas with a type's pictogram, for tables, the legend and pickers. */
  function glyphCanvas(type, size = 18) {
    const k = window.devicePixelRatio || 1;
    const cv = document.createElement("canvas");
    cv.width = cv.height = Math.ceil(size * k);
    cv.style.width = cv.style.height = `${size}px`;
    cv.className = "glyph";
    const c = cv.getContext("2d");
    c.scale(k, k);
    drawGlyph(c, type, size / 2, size / 2, size * 0.86, (TYPE[type] || TYPE.UNKNOWN).color);
    return cv;
  }

  // ---------------------------------------------------------------- cable routes

  const RISER_OFFSET = 48;

  /** The part of a cable on this floor: points from end A towards end B, and where it leaves the floor. */
  function routeOf(l) {
    const wps = (S.routeDrag && S.routeDrag.id === l.id ? S.routeDrag.points : l.waypoints) || [];
    const byId = devicesById();
    const a = byId.get(l.device_a_id), b = byId.get(l.device_b_id);
    if (!a && !b) return null;
    const floorId = S.floor && S.floor.id;
    const floorA = a ? floorId : S.remote.get(l.device_a_id)?.floor_id;
    const local = [];
    wps.forEach((p, i) => { if ((p.floor_id || floorA) === floorId) local.push({ x: p.x, y: p.y, i }); });
    const points = [];
    if (a) points.push({ x: a.x, y: a.y, i: -1 });
    points.push(...local);
    if (b) points.push({ x: b.x, y: b.y, i: wps.length });
    let riser = null;
    if (!a || !b) {
      const other = a ? S.remote.get(l.device_b_id)?.floor_id : floorA;
      if (!local.length) {
        const end = points[0];
        riser = { x: end.x + RISER_OFFSET, y: end.y - RISER_OFFSET, other, virtual: true, insertAt: a ? 0 : wps.length };
      } else {
        const far = a ? local[local.length - 1] : local[0];
        riser = { x: far.x, y: far.y, other, virtual: false };
      }
    }
    return { link: l, wps, points, riser, aHere: !!a };
  }

  /** Screen points of a route, the riser included when the cable has no bend on this floor yet. */
  function screenPath(r) {
    const pts = r.points.map((p) => toScreen(p.x, p.y));
    if (r.riser && r.riser.virtual) {
      const at = toScreen(r.riser.x, r.riser.y);
      return r.aHere ? [...pts, at] : [at, ...pts];
    }
    return pts;
  }

  const insertAt = (r, k) => {
    const next = r.points[k + 1].i;
    return next >= 0 && next < r.wps.length ? next : r.points[k].i + 1;
  };

  /** A grip of the selected cable under the pointer: a bend, or a "+" that makes one. */
  function handleAt(sx, sy) {
    const l = S.links.find((x) => x.id === S.selLink);
    const r = l && routeOf(l);
    if (!r) return null;
    const near = (x, y) => { const [px, py] = toScreen(x, y); return Math.hypot(sx - px, sy - py) <= 9; };
    const bend = r.points.find((p) => p.i >= 0 && p.i < r.wps.length && near(p.x, p.y));
    if (bend) return { route: r, index: bend.i };
    if (r.riser && r.riser.virtual && near(r.riser.x, r.riser.y)) return { route: r, insert: r.riser.insertAt };
    for (let k = 0; k + 1 < r.points.length; k++) {
      const a = r.points[k], b = r.points[k + 1];
      if (near((a.x + b.x) / 2, (a.y + b.y) / 2)) return { route: r, insert: insertAt(r, k) };
    }
    return null;
  }

  const newBendFloor = (r) => (r.riser ? S.floor && S.floor.id : undefined);

  async function saveRoute(linkId, points) {
    const l = S.links.find((x) => x.id === linkId);
    if (!l) return;
    const before = l.waypoints;
    l.waypoints = points;
    draw();
    try {
      const saved = await api("PATCH", `connections/${linkId}`, {
        waypoints: points.map((p) => (p.floor_id ? { x: round1(p.x), y: round1(p.y), floor_id: p.floor_id } : { x: round1(p.x), y: round1(p.y) })),
      });
      l.waypoints = saved.waypoints;
    } catch (e) {
      l.waypoints = before;
      fail(e);
    }
    draw();
  }

  function deleteSelectedPoint() {
    const l = S.links.find((x) => x.id === S.selLink);
    if (!l || S.selPoint == null) return;
    const points = l.waypoints.filter((_, i) => i !== S.selPoint);
    S.selPoint = null;
    saveRoute(l.id, points);
  }

  function selectLink(id) {
    S.selLink = id;
    S.selPoint = null;
    hint();
    draw();
  }

  async function loadPlanImage() {
    const url = S.floor && S.floor.plan_image_url;
    if (url === S.imageUrl) return;
    S.imageUrl = url;
    S.image = null;
    if (!url) return;
    try {
      const path = url.replace(/^https?:\/\/[^/]+/, "");
      const blob = path.startsWith("/api/v1/")
        ? await api("GET", path.slice("/api/v1/".length))
        : await (await fetch(path)).blob();
      const img = new Image();
      img.src = URL.createObjectURL(blob);
      await img.decode();
      if (S.imageUrl === url) { S.image = img; draw(); }
    } catch (e) {
      toast(`План не загрузился: ${e.message}`, true);
    }
  }

  async function ensureFloor() {
    if (S.floor) return S.floor;
    const size = floorSize();
    S.floor = await api("POST", "floors", {
      building_name: "Здание", floor_number: 1, width_px: Math.ceil(size.w), height_px: Math.ceil(size.h),
    });
    return S.floor;
  }

  // ---------------------------------------------------------------- view

  function resize() {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function fit() {
    const { w, h } = floorSize();
    const cw = canvas.clientWidth || 800, ch = canvas.clientHeight || 500;
    const scale = Math.min(cw / w, ch / h) * 0.94;
    S.view = { scale, ox: (cw - w * scale) / 2, oy: (ch - h * scale) / 2 };
  }

  const toScreen = (x, y) => [x * S.view.scale + S.view.ox, y * S.view.scale + S.view.oy];
  const toPlan = (sx, sy) => [(sx - S.view.ox) / S.view.scale, (sy - S.view.oy) / S.view.scale];

  /** Marks placed in the app: room numbers in circles, captions, arrows. To the plan's scale, never unreadable. */
  function drawAnnotations() {
    const marks = (S.floor && S.floor.annotations) || [];
    const meter = Number((S.floor && S.floor.scale_pixels_per_meter) || 50) * S.view.scale;
    const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
    const caption = (text, x, y, px, color) => {
      ctx.font = `600 ${px}px system-ui, sans-serif`;
      const w = ctx.measureText(text).width;
      ctx.fillStyle = "rgba(11,14,19,.6)";
      ctx.fillRect(x - w / 2 - 3, y - px / 2 - 3, w + 6, px + 6);
      ctx.fillStyle = color;
      ctx.fillText(text, x, y);
    };
    ctx.save();
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    for (const m of marks) {
      // A mark being dragged is drawn where the pointer has taken it.
      const drag = S.markDrag && S.markDrag.id === m.id ? S.markDrag : null;
      const a = drag ? { ...m, x: m.x + drag.dx, y: m.y + drag.dy, x2: m.x2 != null ? m.x2 + drag.dx : m.x2, y2: m.y2 != null ? m.y2 + drag.dy : m.y2 } : m;
      const size = a.size || 1;
      const [x, y] = toScreen(a.x, a.y);
      if (a.kind === "BADGE") {
        const r = clamp(0.7 * meter * size, 10, 48);
        ctx.fillStyle = a.color;
        ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
        const rgb = parseInt(a.color.slice(1), 16);
        const light = 0.299 * (rgb >> 16) + 0.587 * ((rgb >> 8) & 255) + 0.114 * (rgb & 255) > 153;
        ctx.fillStyle = light ? "#000" : "#fff";
        ctx.font = `700 ${r * (a.text.length <= 3 ? 0.95 : 0.7)}px system-ui, sans-serif`;
        ctx.fillText(a.text, x, y);
      } else if (a.kind === "TEXT") {
        caption(a.text, x, y, clamp(0.6 * meter * size, 11, 40), a.color);
      } else if (a.kind === "ARROW" && a.x2 != null) {
        const [x2, y2] = toScreen(a.x2, a.y2);
        const len = Math.hypot(x2 - x, y2 - y);
        if (len < 1) continue;
        const ux = (x2 - x) / len, uy = (y2 - y) / len;
        const width = clamp(0.12 * meter * size, 2.5, 8);
        const head = Math.min(width * 4, len * 0.6);
        const bx = x2 - ux * head, by = y2 - uy * head;
        ctx.strokeStyle = a.color; ctx.lineWidth = width; ctx.lineCap = "round";
        ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(bx, by); ctx.stroke();
        ctx.fillStyle = a.color;
        ctx.beginPath(); ctx.moveTo(x2, y2);
        ctx.lineTo(bx - uy * head * 0.55, by + ux * head * 0.55);
        ctx.lineTo(bx + uy * head * 0.55, by - ux * head * 0.55);
        ctx.closePath(); ctx.fill();
        if (a.text) {
          const px = clamp(0.5 * meter * size, 11, 32);
          ctx.font = `600 ${px}px system-ui, sans-serif`;
          const w = ctx.measureText(a.text).width;
          caption(a.text, x - ux * (w / 2 + 6), y - uy * (w / 2 + 6), px, a.color);
        }
      }
    }
    if (S.arrowStart) {
      const [x, y] = toScreen(S.arrowStart.x, S.arrowStart.y);
      ctx.beginPath();
      ctx.arc(x, y, 6, 0, Math.PI * 2);
      ctx.fillStyle = S.markColor;
      ctx.fill();
    }
    ctx.restore();
  }

  function draw() {
    if (currentTab !== "map") return;
    resize();
    const cw = canvas.clientWidth, ch = canvas.clientHeight;
    ctx.clearRect(0, 0, cw, ch);
    const { w, h } = floorSize();
    const [fx, fy] = toScreen(0, 0);
    const k = S.view.scale;
    ctx.fillStyle = "#161b23";
    ctx.fillRect(fx, fy, w * k, h * k);

    const r = planRect();
    if (r && S.image) {
      const [px, py] = toScreen(r.x, r.y);
      ctx.globalAlpha = 0.9;
      ctx.drawImage(S.image, px, py, r.w * k, r.h * k);
      ctx.globalAlpha = 1;
      if (S.tool === "plan") {
        ctx.setLineDash([8, 5]);
        ctx.strokeStyle = "#4c9eff";
        ctx.lineWidth = 1.5;
        ctx.strokeRect(px, py, r.w * k, r.h * k);
        ctx.setLineDash([]);
      }
    }
    ctx.strokeStyle = "#343d4b";
    ctx.lineWidth = 1;
    ctx.strokeRect(fx, fy, w * k, h * k);

    for (const z of zonesOf(S.floor)) drawZone(z);
    drawAnnotations();
    if (S.tool === "zones" && !S.zoneDraft) {
      for (const z of zonesOf(S.floor)) {
        for (let i = 0; i + 1 < z.points.length; i += 2) {
          const [px, py] = toScreen(z.points[i], z.points[i + 1]);
          ctx.beginPath();
          ctx.arc(px, py, 5, 0, Math.PI * 2);
          ctx.fillStyle = "#fff";
          ctx.fill();
          ctx.strokeStyle = z.color;
          ctx.lineWidth = 2;
          ctx.stroke();
        }
      }
    }
    if (S.zoneDraft && S.zoneDraft.length) {
      polyline(S.zoneDraft, "#4c9eff", 2);
      for (let i = 0; i + 1 < S.zoneDraft.length; i += 2) {
        const [px, py] = toScreen(S.zoneDraft[i], S.zoneDraft[i + 1]);
        ctx.beginPath();
        ctx.arc(px, py, 4, 0, Math.PI * 2);
        ctx.fillStyle = i === 0 ? "#30d158" : "#4c9eff";
        ctx.fill();
      }
    }
    for (const d of (S.floor && S.floor.drawings) || []) polyline(d.points, d.color, d.width * k);
    if (S.stroke && S.stroke.length > 3) polyline(S.stroke, S.pen, 3);

    const routes = S.links.map(routeOf).filter(Boolean);
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    // On the VLAN and subnet layers the cables step back: colour is about the network, not the wire.
    if (S.layer !== "L1" && S.tool !== "link") ctx.globalAlpha = 0.25;
    for (const r of routes) {
      const pts = screenPath(r);
      if (pts.length < 2) continue;
      const fiber = /^O[MS]/i.test(r.link.cable_type || "");
      const selected = r.link.id === S.selLink && S.tool === "link";
      const own = r.link.color;
      const trace = () => { ctx.beginPath(); ctx.moveTo(...pts[0]); for (const p of pts.slice(1)) ctx.lineTo(...p); };
      if (selected && own) {
        // A coloured cable keeps its colour when picked; a blue halo shows the pick.
        ctx.setLineDash([]);
        ctx.strokeStyle = "rgba(76,158,255,.55)";
        ctx.lineWidth = 7;
        trace();
        ctx.stroke();
      }
      ctx.strokeStyle = own || (selected ? "#4c9eff" : fiber ? "rgba(90,200,250,.8)" : "rgba(139,149,165,.8)");
      ctx.lineWidth = selected ? 3 : own ? 2.5 : fiber ? 2.5 : 1.5;
      ctx.setLineDash(fiber || selected || own ? [] : [6, 4]);
      trace();
      ctx.stroke();
    }
    ctx.setLineDash([]);
    ctx.globalAlpha = 1;
    // Risers: where cables go up or down to another floor.
    const seen = new Set();
    const labels = [];
    ctx.textAlign = "left";
    ctx.textBaseline = "middle";
    for (const r of routes) {
      if (!r.riser) continue;
      const [sx, sy] = toScreen(r.riser.x, r.riser.y);
      const key = `${Math.round(sx / 4)},${Math.round(sy / 4)}`;
      if (seen.has(key)) continue;
      seen.add(key);
      ctx.fillStyle = "#1c222c";
      ctx.fillRect(sx - 6, sy - 6, 12, 12);
      ctx.strokeStyle = "#5ac8fa";
      ctx.lineWidth = 1.5;
      ctx.strokeRect(sx - 6, sy - 6, 12, 12);
      ctx.font = "11px system-ui, sans-serif";
      const text = `↕ ${floorTitle(S.floors.find((f) => f.id === r.riser.other))}`;
      const box = { x: sx + 10, y: sy - 8, w: ctx.measureText(text).width, h: 16 };
      // Risers side by side share one label rather than printing over each other.
      if (labels.some((o) => box.x < o.x + o.w && o.x < box.x + box.w && box.y < o.y + o.h && o.y < box.y + box.h)) continue;
      labels.push(box);
      ctx.fillStyle = "#5ac8fa";
      ctx.fillText(text, box.x, sy);
    }
    const sel = S.tool === "link" && routes.find((r) => r.link.id === S.selLink);
    if (sel) {
      for (let k = 0; k + 1 < sel.points.length; k++) {
        const a = sel.points[k], b = sel.points[k + 1];
        const [mx, my] = toScreen((a.x + b.x) / 2, (a.y + b.y) / 2);
        ctx.beginPath();
        ctx.arc(mx, my, 7, 0, Math.PI * 2);
        ctx.fillStyle = "#1c222c";
        ctx.fill();
        ctx.strokeStyle = "#4c9eff";
        ctx.lineWidth = 2;
        ctx.stroke();
        ctx.beginPath();
        ctx.moveTo(mx - 3.5, my); ctx.lineTo(mx + 3.5, my);
        ctx.moveTo(mx, my - 3.5); ctx.lineTo(mx, my + 3.5);
        ctx.stroke();
      }
      if (sel.riser && sel.riser.virtual) {
        const [rx, ry] = toScreen(sel.riser.x, sel.riser.y);
        ctx.beginPath();
        ctx.arc(rx, ry, 9, 0, Math.PI * 2);
        ctx.strokeStyle = "#4c9eff";
        ctx.stroke();
      }
      for (const p of sel.points) {
        if (p.i < 0 || p.i >= sel.wps.length) continue;
        const [px, py] = toScreen(p.x, p.y);
        ctx.beginPath();
        ctx.arc(px, py, 7, 0, Math.PI * 2);
        ctx.fillStyle = p.i === S.selPoint ? "#ff6b5e" : "#fff";
        ctx.fill();
        ctx.strokeStyle = "#4c9eff";
        ctx.lineWidth = 2;
        ctx.stroke();
      }
    }

    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    // Zoomed out in view mode, markers that would overlap gather into one circle with a count.
    S.clusters = S.tool === "view" ? clusterDevices() : S.devices.map((d) => ({ lead: d, ids: [d.id], x: d.x, y: d.y }));
    for (const c of S.clusters) {
      if (c.ids.length > 1) drawCluster(c); else drawDevice(c.lead);
    }
  }

  const TYPE_RANK = ["SWITCH", "ROUTER", "FIREWALL", "SERVER", "ACCESS_POINT", "PRINTER", "WORKSTATION"];
  // Markers closer than this on screen overlap: about one marker's width.
  const CLUSTER_PX = 26;

  /** Greedy grouping by distance on screen; the most important kind leads; the picked device stays alone. */
  function clusterDevices() {
    const rank = (d) => { const i = TYPE_RANK.indexOf(d.type); return i < 0 ? TYPE_RANK.length : i; };
    // Sockets and patch panels sit right by what they serve: they never gather, or every desk would be a «2».
    const order = S.devices.filter((d) => !PASSIVE.has(d.type)).sort((a, b) => rank(a) - rank(b));
    const used = new Set();
    const out = [];
    for (const d of order) {
      if (used.has(d.id)) continue;
      used.add(d.id);
      const [x, y] = toScreen(d.x, d.y);
      const group = [d];
      if (d.id !== S.focus) {
        for (const o of order) {
          if (used.has(o.id) || o.id === S.focus) continue;
          const [ox, oy] = toScreen(o.x, o.y);
          if (Math.hypot(ox - x, oy - y) < CLUSTER_PX) { group.push(o); used.add(o.id); }
        }
      }
      out.push({
        lead: d, ids: group.map((g) => g.id), down: group.some((g) => !g.is_online && !PASSIVE.has(g.type)),
        x: group.reduce((a, g) => a + g.x, 0) / group.length, y: group.reduce((a, g) => a + g.y, 0) / group.length,
      });
    }
    // Passive markers under a gathered circle step aside with it; elsewhere they stay, drawn first, underneath.
    const groups = out.filter((c) => c.ids.length > 1).map((c) => toScreen(c.x, c.y));
    const passive = S.devices.filter((d) => PASSIVE.has(d.type)).filter((d) => {
      const [x, y] = toScreen(d.x, d.y);
      return !groups.some(([gx, gy]) => Math.hypot(gx - x, gy - y) < CLUSTER_PX * 1.5);
    }).map((d) => ({ lead: d, ids: [d.id], x: d.x, y: d.y }));
    return [...passive, ...out];
  }

  function drawCluster(c) {
    const [sx, sy] = toScreen(c.x, c.y);
    ctx.beginPath();
    ctx.arc(sx, sy, 18, 0, Math.PI * 2);
    ctx.fillStyle = "#262e3a";
    ctx.fill();
    ctx.strokeStyle = typeOf(c.lead).color;
    ctx.lineWidth = 2;
    ctx.stroke();
    drawGlyph(ctx, c.lead.type, sx, sy - 3, 13, typeOf(c.lead).color);
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.font = "600 11px system-ui, sans-serif";
    ctx.fillStyle = "#e6edf5";
    ctx.fillText(String(c.ids.length), sx, sy + 9);
    if (c.down) {
      ctx.beginPath();
      ctx.arc(sx + 13, sy - 13, 4.5, 0, Math.PI * 2);
      ctx.fillStyle = "#ff6b5e";
      ctx.fill();
    }
  }

  /** The colour a device takes on the VLAN and subnet layers: its port's VLAN, grey without one. */
  const vlanColor = (d) => (S.vlans.get(d.vlan_id) || {}).color_hex || null;

  function drawDevice(d) {
    const [sx, sy] = toScreen(d.x, d.y);
    const passive = PASSIVE.has(d.type);
    const rad = passive ? 10 : 15;
    const active = d.id === S.linkFrom || (S.drag && S.drag.id === d.id);
    // A virtual machine has no cables: a dashed ring says it is not physically there.
    const virtual = d.type === "VIRTUAL";
    const layered = S.layer !== "L1";
    // A picked VLAN stands out; everyone else fades.
    ctx.globalAlpha = S.vlanFilter != null && layered && d.vlan_id !== S.vlanFilter ? 0.2 : 1;
    if (layered && vlanColor(d)) {
      ctx.beginPath();
      ctx.arc(sx, sy, rad + 4, 0, Math.PI * 2);
      ctx.fillStyle = vlanColor(d);
      ctx.fill();
    }
    if (d.id === S.focus) {
      ctx.beginPath();
      ctx.arc(sx, sy, rad + 8, 0, Math.PI * 2);
      ctx.strokeStyle = "#4c9eff";
      ctx.lineWidth = 3;
      ctx.stroke();
    }
    ctx.beginPath();
    ctx.arc(sx, sy, rad, 0, Math.PI * 2);
    ctx.fillStyle = active ? "#4c9eff" : "#1c222c";
    ctx.fill();
    ctx.strokeStyle = active ? "#4c9eff" : virtual ? "#8e9bff" : "#6b7686";
    ctx.lineWidth = 1.5;
    if (virtual) ctx.setLineDash([3, 3]);
    ctx.stroke();
    ctx.setLineDash([]);
    drawGlyph(ctx, d.type, sx, sy, passive ? 12 : 17, active ? "#ffffff" : typeOf(d).color);
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    if (!passive) {
      ctx.beginPath();
      ctx.arc(sx + rad * 0.72, sy - rad * 0.72, 4, 0, Math.PI * 2);
      ctx.fillStyle = d.is_online ? "#30d158" : "#ff6b5e";
      ctx.fill();
    }
    // On the subnet layer the address is what matters.
    const label = S.layer === "L3" && d.ip ? `${d.name} · ${d.ip}` : d.name;
    ctx.font = "11px system-ui, sans-serif";
    ctx.fillStyle = "rgba(11,14,19,.75)";
    const tw = ctx.measureText(label).width;
    ctx.fillRect(sx - tw / 2 - 3, sy + rad + 3, tw + 6, 15);
    ctx.fillStyle = "#e6edf5";
    ctx.fillText(label, sx, sy + rad + 11);
    ctx.globalAlpha = 1;
  }

  /** A gathered circle under the pointer: the click zooms in on it until it falls apart. */
  function clusterAt(sx, sy) {
    return S.clusters.find((c) => {
      if (c.ids.length < 2) return false;
      const [x, y] = toScreen(c.x, c.y);
      return Math.hypot(sx - x, sy - y) <= 20;
    }) || null;
  }

  function drawZone(z) {
    if (z.points.length < 6) return;
    ctx.beginPath();
    ctx.moveTo(...toScreen(z.points[0], z.points[1]));
    for (let i = 2; i + 1 < z.points.length; i += 2) ctx.lineTo(...toScreen(z.points[i], z.points[i + 1]));
    ctx.closePath();
    ctx.fillStyle = `${z.color}${z.is_room ? "2e" : "1a"}`;
    ctx.fill();
    ctx.strokeStyle = z.color;
    ctx.lineWidth = z.is_room ? 2 : 1.5;
    ctx.setLineDash(z.is_room ? [] : [8, 5]);
    ctx.stroke();
    ctx.setLineDash([]);
    const at = innerPoint(z.points);
    const [lx, ly] = toScreen(at.x, at.y);
    ctx.font = "600 12px system-ui, sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillStyle = z.color;
    ctx.globalAlpha = 0.9;
    ctx.fillText(z.name, lx, ly);
    ctx.globalAlpha = 1;
  }

  function polyline(points, color, width) {
    if (!points || points.length < 4) return;
    ctx.strokeStyle = color;
    ctx.lineWidth = Math.max(width, 1);
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.beginPath();
    ctx.moveTo(...toScreen(points[0], points[1]));
    for (let i = 2; i + 1 < points.length; i += 2) ctx.lineTo(...toScreen(points[i], points[i + 1]));
    ctx.stroke();
  }

  const devicesById = () => new Map(S.devices.map((d) => [d.id, d]));

  function deviceAt(sx, sy) {
    const gathered = new Set(S.clusters.filter((c) => c.ids.length > 1).flatMap((c) => c.ids));
    for (let i = S.devices.length - 1; i >= 0; i--) {
      const d = S.devices[i];
      if (gathered.has(d.id)) continue;
      const [x, y] = toScreen(d.x, d.y);
      if (Math.hypot(sx - x, sy - y) <= (PASSIVE.has(d.type) ? 13 : 18)) return d;
    }
    return null;
  }

  function linkAt(sx, sy) {
    let best = null, bestDist = 7;
    for (const l of S.links) {
      const r = routeOf(l);
      if (!r) continue;
      const pts = screenPath(r);
      for (let k = 0; k + 1 < pts.length; k++) {
        const [ax, ay] = pts[k], [bx, by] = pts[k + 1];
        const dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        const t = len2 ? Math.max(0, Math.min(1, ((sx - ax) * dx + (sy - ay) * dy) / len2)) : 0;
        const dist = Math.hypot(sx - (ax + dx * t), sy - (ay + dy * t));
        if (dist < bestDist) { best = l; bestDist = dist; }
      }
    }
    return best;
  }

  function hint() {
    const texts = {
      view: "Щелчок по устройству открывает его карточку, по кругу с числом приближает группу. Колесо мыши масштабирует, перетаскивание двигает карту. Чтобы что-то поменять, включите «Правка».",
      marks: S.arrowStart
        ? "Щёлкните, куда указывает стрелка (Esc — отмена)."
        : "Щёлкните по плану, чтобы поставить метку выбранного вида; стрелка ставится двумя щелчками: откуда и куда. Метку можно перетащить, щелчок открывает её.",
      move: "Перетащите объект мышью, щёлкните по нему, чтобы изменить. Двойной щелчок по пустому месту добавляет объект. Колесо мыши масштабирует, перетаскивание пустого места двигает карту.",
      link: S.linkFrom
        ? `От ${devicesById().get(S.linkFrom)?.name || "?"}: щёлкните второе устройство (Esc — отмена).`
        : S.selLink
          ? "Тяните белые точки, чтобы вести кабель по трассе; «+» добавляет точку поворота. Щелчок по точке выбирает её, Delete или двойной щелчок удаляет. Esc — закончить."
          : "Щёлкните два устройства, чтобы соединить их кабелем. Щёлкните кабель, чтобы проложить его по своей трассе.",
      zones: S.zoneDraft
        ? "Щёлкайте по углам контура. «Готово» или двойной щелчок замыкает его, «Убрать точку» отменяет последний угол."
        : "Щёлкните по плану, чтобы начать контур помещения или зоны. Белые точки на контуре можно тянуть, двойной щелчок по точке удаляет её; щелчок внутри контура открывает его для правки. Устройство считается в помещении, если стоит внутри контура.",
      draw: "Рисуйте мышью. Линии видны на карте в приложении.",
      plan: S.floor && S.floor.plan_image_url
        ? "Перетащите план мышью, чтобы совместить его с устройствами. «−» и «+» меняют размер."
        : "Загрузите план здания: PNG, JPEG или SVG. PDF можно загрузить с телефона: приложение само превращает его в картинку.",
    };
    $("map-hint").textContent = texts[S.tool];
    $("link-hint").textContent = S.tool === "link" && S.linkFrom ? "выбрано первое устройство" : "";
    $("route-opts").hidden = !(S.tool === "link" && S.selLink);
    renderCableColors();
    $("route-point-delete").disabled = S.selPoint == null;
  }

  // ---------------------------------------------------------------- map gestures

  let gesture = null;

  canvas.addEventListener("pointerdown", (ev) => {
    if (ev.button !== 0 && ev.button !== 1) return;
    canvas.setPointerCapture(ev.pointerId);
    const sx = ev.offsetX, sy = ev.offsetY;
    const device = deviceAt(sx, sy);
    gesture = { sx, sy, lastX: sx, lastY: sy, moved: false, kind: "pan", device };
    if (ev.button === 1) return;
    const handle = S.tool === "link" && S.selLink ? handleAt(sx, sy) : null;
    if (handle) {
      gesture.kind = "route";
      gesture.handle = handle;
    } else if (S.tool === "move" && device) {
      gesture.kind = "device";
      S.drag = { id: device.id, x0: device.x, y0: device.y };
    } else if (S.tool === "link" && device) {
      gesture.kind = "link-device";
    } else if (S.tool === "link" && linkAt(sx, sy)) {
      gesture.kind = "link-cable";
      gesture.link = linkAt(sx, sy);
    } else if (S.tool === "marks" && !S.arrowStart && annotationAt(sx, sy)) {
      gesture.kind = "mark";
      gesture.mark = annotationAt(sx, sy);
      S.markDrag = { id: gesture.mark.id, dx: 0, dy: 0 };
    } else if (S.tool === "zones" && !S.zoneDraft && zoneVertexAt(sx, sy)) {
      gesture.kind = "zone-vertex";
      gesture.vertex = zoneVertexAt(sx, sy);
    } else if (S.tool === "draw") {
      gesture.kind = "draw";
      S.stroke = toPlan(sx, sy);
    } else if (S.tool === "plan" && planRect()) {
      const [x, y] = toPlan(sx, sy);
      const r = planRect();
      if (x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h) {
        gesture.kind = "plan";
        S.planShift = { x: 0, y: 0 };
      }
    }
  });

  canvas.addEventListener("pointermove", (ev) => {
    if (!gesture) return;
    const sx = ev.offsetX, sy = ev.offsetY;
    const dx = sx - gesture.lastX, dy = sy - gesture.lastY;
    gesture.lastX = sx; gesture.lastY = sy;
    if (Math.hypot(sx - gesture.sx, sy - gesture.sy) > 3) gesture.moved = true;
    if (!gesture.moved) return;
    const k = S.view.scale;
    const { w, h } = floorSize();
    switch (gesture.kind) {
      case "device": {
        const d = gesture.device;
        d.x = Math.max(0, Math.min(w, d.x + dx / k));
        d.y = Math.max(0, Math.min(h, d.y + dy / k));
        break;
      }
      case "mark":
        S.markDrag.dx += dx / k;
        S.markDrag.dy += dy / k;
        break;
      case "zone-vertex": {
        const [x, y] = toPlan(sx, sy);
        const { zone, index } = gesture.vertex;
        zone.points[index * 2] = round1(x);
        zone.points[index * 2 + 1] = round1(y);
        break;
      }
      case "draw": {
        const [x, y] = toPlan(sx, sy);
        const n = S.stroke.length;
        const [lx, ly] = toScreen(S.stroke[n - 2], S.stroke[n - 1]);
        if (Math.hypot(sx - lx, sy - ly) >= 2 && n < 7800) S.stroke.push(round1(x), round1(y));
        break;
      }
      case "plan":
        S.planShift.x += dx / k;
        S.planShift.y += dy / k;
        break;
      case "route": {
        const [x0, y0] = toPlan(sx, sy);
        const x = Math.max(0, Math.min(w, x0)), y = Math.max(0, Math.min(h, y0));
        const hd = gesture.handle;
        if (!S.routeDrag) {
          const points = hd.route.wps.map((p) => ({ ...p }));
          if (hd.insert != null) {
            points.splice(hd.insert, 0, { x, y, floor_id: newBendFloor(hd.route) });
            hd.index = hd.insert;
          }
          S.routeDrag = { id: hd.route.link.id, points };
        }
        Object.assign(S.routeDrag.points[hd.index], { x, y });
        break;
      }
      default:
        S.touched = true;
        S.view.ox += dx;
        S.view.oy += dy;
    }
    draw();
  });

  const round1 = (v) => Math.round(v * 10) / 10;

  function endGesture() {
    const g = gesture;
    gesture = null;
    if (!g) return;
    const drag = S.drag;
    S.drag = null;
    if (g.kind === "device") {
      if (g.moved) moveDevice(g.device).catch((e) => { g.device.x = drag.x0; g.device.y = drag.y0; draw(); fail(e); });
      else openDeviceDialog(g.device);
    } else if (g.kind === "link-device" && !g.moved) {
      linkTap(g.device);
    } else if (g.kind === "link-cable" && !g.moved) {
      selectLink(g.link.id);
    } else if (g.kind === "route") {
      const drag = S.routeDrag;
      S.routeDrag = null;
      const hd = g.handle;
      if (drag) saveRoute(drag.id, drag.points);
      else if (hd.index != null) { S.selPoint = S.selPoint === hd.index ? null : hd.index; hint(); }
      else {
        // A click on "+" puts a bend right there.
        const r = hd.route;
        const k = r.points.findIndex((_, j) => j + 1 < r.points.length && insertAt(r, j) === hd.insert);
        const at = k >= 0
          ? { x: (r.points[k].x + r.points[k + 1].x) / 2, y: (r.points[k].y + r.points[k + 1].y) / 2 }
          : { x: r.riser.x, y: r.riser.y };
        const points = r.wps.map((p) => ({ ...p }));
        points.splice(hd.insert, 0, { ...at, floor_id: newBendFloor(r) });
        saveRoute(r.link.id, points);
      }
    } else if (g.kind === "zone-vertex") {
      if (g.moved) saveZones(zonesOf(S.floor)).catch((e) => { fail(e); loadMap().catch(fail); });
      else openZoneDialog(g.vertex.zone);
    } else if (g.kind === "draw") {
      const points = S.stroke;
      S.stroke = null;
      if (points && points.length >= 4) saveDrawings([...(S.floor?.drawings || []), {
        color: S.pen, width: Math.min(40, Math.max(1, 3 / S.view.scale)), points: points.map(round1),
      }]).catch(fail);
    } else if (g.kind === "plan") {
      const shift = S.planShift;
      S.planShift = null;
      if (g.moved && shift) {
        const r = planRect();
        placePlan({ x: r.x + shift.x, y: r.y + shift.y, w: r.w, h: r.h }).catch(fail);
      }
    } else if (g.kind === "mark") {
      const drag = S.markDrag;
      S.markDrag = null;
      if (g.moved && drag) moveMark(g.mark, drag.dx, drag.dy).catch(fail);
      else openMarkDialog(g.mark, false);
    } else if (g.kind === "pan" && !g.moved && S.tool === "marks") {
      markTap(...toPlan(g.sx, g.sy));
    } else if (g.kind === "pan" && !g.moved && S.tool === "view") {
      // View mode: a device opens its card, a gathered circle zooms in, empty map drops the search ring.
      const cluster = clusterAt(g.sx, g.sy);
      if (g.device) openCard(g.device.id);
      else if (cluster) zoomAt(g.sx, g.sy, 2.2);
      else if (S.focus) S.focus = null;
    } else if (g.kind === "pan" && !g.moved && S.tool === "zones") {
      zoneClick(g.sx, g.sy);
    } else if (g.kind === "pan" && !g.moved && S.tool === "link" && (S.linkFrom || S.selLink)) {
      S.linkFrom = null;
      S.selLink = null;
      S.selPoint = null;
      hint();
    }
    draw();
  }
  canvas.addEventListener("pointerup", endGesture);
  canvas.addEventListener("pointercancel", endGesture);

  canvas.addEventListener("dblclick", (ev) => {
    if (S.tool === "link" && S.selLink) {
      const hd = handleAt(ev.offsetX, ev.offsetY);
      if (hd && hd.index != null) { S.selPoint = hd.index; deleteSelectedPoint(); }
      return;
    }
    if (S.tool === "zones") {
      if (S.zoneDraft) { if (S.zoneDraft.length >= 6) finishZone(); return; }
      // A double click on a corner removes it (an outline keeps at least three).
      const v = zoneVertexAt(ev.offsetX, ev.offsetY);
      if (v && v.zone.points.length > 6) {
        v.zone.points.splice(v.index * 2, 2);
        saveZones(zonesOf(S.floor)).catch((e) => { fail(e); loadMap().catch(fail); });
        draw();
      }
      return;
    }
    if (S.tool !== "move" || deviceAt(ev.offsetX, ev.offsetY)) return;
    const [x, y] = toPlan(ev.offsetX, ev.offsetY);
    openDeviceDialog(null, { x, y });
  });

  function zoomAt(sx, sy, factor) {
    S.touched = true;
    const scale = Math.max(0.02, Math.min(20, S.view.scale * factor));
    const f = scale / S.view.scale;
    S.view.ox = sx - (sx - S.view.ox) * f;
    S.view.oy = sy - (sy - S.view.oy) * f;
    S.view.scale = scale;
    draw();
  }

  canvas.addEventListener("wheel", (ev) => {
    ev.preventDefault();
    zoomAt(ev.offsetX, ev.offsetY, Math.exp(-ev.deltaY * 0.0015));
  }, { passive: false });

  document.addEventListener("keydown", (ev) => {
    if (ev.key === "Escape" && S.zoneDraft && !ev.target.closest("dialog")) cancelZone();
    if (ev.key === "Escape" && (S.linkFrom || S.selLink)) { S.linkFrom = null; S.selLink = null; S.selPoint = null; hint(); draw(); }
    if (ev.key === "Escape" && S.arrowStart && !ev.target.closest("dialog")) { S.arrowStart = null; hint(); draw(); }
    const typing = ev.target.closest && ev.target.closest("input, textarea, select, dialog");
    if ((ev.key === "Delete" || ev.key === "Backspace") && !typing && S.tool === "link" && S.selLink && S.selPoint != null) {
      ev.preventDefault();
      deleteSelectedPoint();
    }
  });
  window.addEventListener("resize", () => draw());

  // ---------------------------------------------------------------- map actions

  async function moveDevice(d) {
    const floor = await ensureFloor();
    const saved = await api("PATCH", `devices/${d.id}`, { x: round1(d.x), y: round1(d.y), floor_id: floor.id });
    Object.assign(d, saved, { x: saved.x ?? d.x, y: saved.y ?? d.y });
  }

  async function linkTap(d) {
    if (!S.linkFrom) { S.linkFrom = d.id; hint(); draw(); return; }
    if (S.linkFrom === d.id) { S.linkFrom = null; hint(); draw(); return; }
    const from = S.linkFrom;
    S.linkFrom = null;
    hint();
    try {
      await api("POST", "connections/link", {
        device_a_id: from, device_b_id: d.id, cable_type: $("cable").value, ...(S.cableColor ? { color: S.cableColor } : {}),
      });
      await loadMap();
    } catch (e) { fail(e); }
  }

  async function deleteLink(l) {
    const byId = new Map([...S.remote, ...devicesById()]);
    const names = `${byId.get(l.device_a_id)?.name || "?"} — ${byId.get(l.device_b_id)?.name || "?"}`;
    if (!confirm(`Удалить кабель ${names} (${l.cable_type})?`)) return;
    try { await api("DELETE", `connections/${l.id}`); S.selLink = null; S.selPoint = null; await loadMap(); } catch (e) { fail(e); }
  }

  async function saveDrawings(drawings) {
    const floor = await ensureFloor();
    if (drawings.length > 500) throw new Error("На этаже уже 500 линий: удалите лишние.");
    floor.drawings = drawings;
    draw();
    S.floor = await api("PATCH", `floors/${floor.id}`, { drawings });
    draw();
  }

  async function placePlan(r) {
    const floor = await ensureFloor();
    const size = floorSize();
    S.floor = await api("PATCH", `floors/${floor.id}`, {
      plan_x: round1(r.x), plan_y: round1(r.y), plan_w: round1(r.w), plan_h: round1(r.h),
      width_px: Math.ceil(Math.max(size.w, r.x + r.w)), height_px: Math.ceil(Math.max(size.h, r.y + r.h)),
    });
    draw();
  }

  async function planSize(file) {
    if (file.type === "image/svg+xml" || /\.svg$/i.test(file.name)) {
      const doc = new DOMParser().parseFromString(await file.text(), "image/svg+xml");
      const svg = doc.documentElement;
      const vb = (svg.getAttribute("viewBox") || "").split(/[\s,]+/).map(Number);
      const w = parseFloat(svg.getAttribute("width")) || vb[2] || 1600;
      const h = parseFloat(svg.getAttribute("height")) || vb[3] || w * 0.625;
      return { w: Math.round(w), h: Math.round(h), type: "image/svg+xml" };
    }
    const bmp = await createImageBitmap(file);
    const size = { w: bmp.width, h: bmp.height, type: file.type };
    bmp.close();
    return size;
  }

  $("plan-file").addEventListener("change", async (ev) => {
    const file = ev.target.files[0];
    ev.target.value = "";
    if (!file) return;
    try {
      if (file.type === "application/pdf" || /\.pdf$/i.test(file.name)) {
        throw new Error("PDF загрузите с телефона (вкладка «Карта» → редактор → «Подложка») или сохраните страницу как PNG.");
      }
      if (file.size > MAX_PLAN) throw new Error("Файл больше 15 МБ: сохраните план меньшего размера.");
      const size = await planSize(file);
      if (!["image/png", "image/jpeg", "image/svg+xml"].includes(size.type)) {
        throw new Error("Подойдёт PNG, JPEG или SVG.");
      }
      const floor = await ensureFloor();
      toast("Загружаю план…");
      S.floor = await api("PUT", `floors/${floor.id}/plan-image?width=${size.w}&height=${size.h}`, undefined,
        { body: file, type: size.type });
      await loadPlanImage();
      fit();
      draw();
      hint();
      toast("План загружен. Перетащите его под устройства.");
    } catch (e) { fail(e); }
  });

  function scalePlan(factor) {
    const r = planRect();
    if (!r) return;
    const w = r.w * factor, h = r.h * factor;
    placePlan({ x: r.x + (r.w - w) / 2, y: r.y + (r.h - h) / 2, w, h }).catch(fail);
  }
  $("plan-smaller").addEventListener("click", () => scalePlan(1 / 1.1));
  $("plan-bigger").addEventListener("click", () => scalePlan(1.1));
  $("plan-fit").addEventListener("click", () => {
    const r = planRect();
    if (!r) return;
    const { w, h } = floorSize();
    const k = Math.min(w / r.w, h / r.h);
    placePlan({ x: 0, y: 0, w: r.w * k, h: r.h * k }).catch(fail);
  });
  $("plan-remove").addEventListener("click", async () => {
    if (!S.floor || !S.floor.plan_image_url) return;
    if (!confirm("Убрать план здания? Устройства, кабели и линии останутся.")) return;
    try { S.floor = await api("DELETE", `floors/${S.floor.id}/plan-image`); await loadPlanImage(); draw(); hint(); }
    catch (e) { fail(e); }
  });

  function setTool(tool) {
    S.tool = tool;
    if (tool !== "view") S.editTool = tool;
    S.linkFrom = null;
    S.selLink = null;
    S.selPoint = null;
    S.zoneDraft = null;
    S.arrowStart = null;
    fitDraftButtons();
    for (const x of $("tools").querySelectorAll("button")) x.classList.toggle("active", x.dataset.tool === tool);
    for (const o of document.querySelectorAll(".tool-opts")) o.hidden = o.dataset.for !== tool;
    hint();
    draw();
  }
  $("tools").addEventListener("click", (ev) => {
    const b = ev.target.closest("button[data-tool]");
    if (b) setTool(b.dataset.tool);
  });

  /** «Просмотр» opens cards and gathers crowded markers; «Правка» shows the tools and changes the map. */
  function setMode(mode) {
    for (const b of $("mode-seg").children) b.classList.toggle("active", b.dataset.mode === mode);
    $("edit-bar").hidden = mode !== "edit";
    $("map-empty").hidden = true;
    setTool(mode === "edit" ? S.editTool : "view");
  }
  $("mode-seg").addEventListener("click", (ev) => {
    const b = ev.target.closest("button[data-mode]");
    if (b) setMode(b.dataset.mode);
  });
  $("empty-edit").addEventListener("click", () => setMode("edit"));
  $("empty-scan").addEventListener("click", () => openTab("scan"));

  $("layer-seg").addEventListener("click", (ev) => {
    const b = ev.target.closest("button[data-layer]");
    if (!b) return;
    S.layer = b.dataset.layer;
    if (S.layer === "L1") S.vlanFilter = null;
    for (const x of $("layer-seg").children) x.classList.toggle("active", x === b);
    renderVlanFilter();
    draw();
  });
  /** On the VLAN and subnet layers: one list to pick a VLAN, and the way to the VLAN reference book. */
  function renderVlanFilter() {
    const sel = $("vlan-filter");
    const layered = S.layer !== "L1";
    sel.hidden = !layered || !S.vlans.size;
    $("vlans-open").hidden = !layered;
    $("vlans-open").textContent = S.vlans.size ? "VLAN и подсети…" : "Добавить VLAN и подсети…";
    sel.replaceChildren(new Option("Все VLAN", ""), ...[...S.vlans.values()].map((v) =>
      new Option(`${v.vlan_id} · ${v.name}${S.layer === "L3" && v.subnet_cidr ? ` · ${v.subnet_cidr}` : ""}`, String(v.vlan_id))));
    sel.value = S.vlanFilter == null ? "" : String(S.vlanFilter);
  }
  $("vlan-filter").addEventListener("change", (ev) => {
    S.vlanFilter = ev.target.value ? Number(ev.target.value) : null;
    draw();
  });
  $("vlans-open").addEventListener("click", () => openTab("vlans"));

  // ---------------------------------------------------------------- search on the map

  $("map-search").addEventListener("input", () => {
    const q = $("map-search").value.trim().toLowerCase();
    const box = $("search-results");
    if (!q) { box.hidden = true; return; }
    const norm = (v) => (v || "").toLowerCase().replaceAll("-", ":");
    const found = allDevicesOrMap().filter((d) => [d.name, d.ip, d.model].some((v) => (v || "").toLowerCase().includes(q))
      || norm(d.mac).includes(q.replaceAll("-", ":"))).slice(0, 8);
    box.replaceChildren(...(found.length ? found.map((d) => {
      const b = document.createElement("button");
      b.type = "button";
      b.append(glyphCanvas(d.type), `${d.name}${d.ip ? ` · ${d.ip}` : ""}`);
      b.addEventListener("click", () => { box.hidden = true; $("map-search").value = ""; showOnMap(d.id).catch(fail); });
      return b;
    }) : [Object.assign(document.createElement("div"), { className: "muted small", textContent: "Ничего не найдено" })]));
    box.hidden = false;
  });
  $("map-search").addEventListener("keydown", (ev) => {
    if (ev.key === "Enter") $("search-results").querySelector("button")?.click();
    if (ev.key === "Escape") { $("search-results").hidden = true; ev.target.value = ""; }
  });

  for (const color of PENS) {
    const b = document.createElement("button");
    b.style.background = color;
    b.title = color;
    b.className = color === S.pen ? "active" : "";
    b.addEventListener("click", () => {
      S.pen = color;
      for (const x of $("pens").children) x.classList.toggle("active", x === b);
    });
    $("pens").appendChild(b);
  }
  $("undo-line").addEventListener("click", () => {
    const d = S.floor?.drawings || [];
    if (d.length) saveDrawings(d.slice(0, -1)).catch(fail);
  });
  $("clear-lines").addEventListener("click", () => {
    if ((S.floor?.drawings || []).length && confirm("Стереть все линии карандаша на этаже?")) saveDrawings([]).catch(fail);
  });
  /** The middle of what is on screen, kept inside the floor: where «Добавить» puts things. */
  function screenCentre() {
    const [x, y] = toPlan(canvas.clientWidth / 2, canvas.clientHeight / 2);
    const { w, h } = floorSize();
    return { x: Math.max(0, Math.min(w, x)), y: Math.max(0, Math.min(h, y)) };
  }
  $("add-menu").addEventListener("click", (ev) => {
    const b = ev.target.closest("button[data-add]");
    if (!b) return;
    closeMenus();
    const at = screenCentre();
    if (b.dataset.add === "device") { dialogType = dialogType === "RACK" ? "SWITCH" : dialogType; openDeviceDialog(null, at); }
    if (b.dataset.add === "rack") { dialogType = "RACK"; openDeviceDialog(null, at); }
    if (b.dataset.add === "template") openPlaceDialog(at).catch(fail);
    if (b.dataset.add === "mark") {
      setTool("marks");
      setMarkKind("BADGE");
      markTap(at.x, at.y);
    }
  });

  // ---------------------------------------------------------------- marks: room numbers, captions, arrows

  const MARK_COLORS = ["#FFD60A", "#FF453A", "#30D158", "#0A84FF", "#BF5AF2", "#FFFFFF"];
  const marksOf = () => (S.floor && S.floor.annotations) || [];
  let markEditing = null;

  function setMarkKind(kind) {
    S.markKind = kind;
    S.arrowStart = null;
    for (const b of $("mark-kinds").children) b.classList.toggle("active", b.dataset.kind === kind);
    hint();
  }
  $("mark-kinds").addEventListener("click", (ev) => {
    const b = ev.target.closest("button[data-kind]");
    if (b) setMarkKind(b.dataset.kind);
  });
  for (const color of MARK_COLORS) {
    const b = document.createElement("button");
    b.type = "button";
    b.style.background = color;
    b.title = color;
    b.className = color === S.markColor ? "active" : "";
    b.addEventListener("click", () => {
      S.markColor = color;
      for (const x of $("mark-colors").children) x.classList.toggle("active", x === b);
    });
    $("mark-colors").appendChild(b);
  }

  /** The mark under the pointer: within its circle, its caption box or near its arrow line. */
  function annotationAt(sx, sy) {
    const meter = Number((S.floor && S.floor.scale_pixels_per_meter) || 50) * S.view.scale;
    for (const a of [...marksOf()].reverse()) {
      const [x, y] = toScreen(a.x, a.y);
      const r = Math.max(10, Math.min(48, 0.7 * meter * (a.size || 1)));
      if (a.kind === "ARROW" && a.x2 != null) {
        const [x2, y2] = toScreen(a.x2, a.y2);
        const dx = x2 - x, dy = y2 - y, len2 = dx * dx + dy * dy;
        const t = len2 ? Math.max(0, Math.min(1, ((sx - x) * dx + (sy - y) * dy) / len2)) : 0;
        if (Math.hypot(sx - (x + dx * t), sy - (y + dy * t)) <= 8) return a;
      } else if (Math.hypot(sx - x, sy - y) <= r) return a;
    }
    return null;
  }

  /** A click on empty map with the marks tool: a new number or caption, or one end of an arrow. */
  function markTap(x, y) {
    if (marksOf().length >= 500) { toast("На этаже уже 500 меток: удалите лишние.", true); return; }
    const id = `m${Math.random().toString(36).slice(2, 10)}`;
    if (S.markKind !== "ARROW") {
      openMarkDialog({ id, kind: S.markKind, x: round1(x), y: round1(y), text: "", color: S.markColor, size: 1 }, true);
    } else if (!S.arrowStart) {
      S.arrowStart = { x: round1(x), y: round1(y) };
      hint();
      draw();
    } else {
      const start = S.arrowStart;
      S.arrowStart = null;
      hint();
      draw();
      openMarkDialog({ id, kind: "ARROW", x: start.x, y: start.y, x2: round1(x), y2: round1(y), text: "", color: S.markColor, size: 1 }, true);
    }
  }

  function openMarkDialog(mark, isNew) {
    markEditing = { mark, isNew };
    $("mark-heading").textContent = { BADGE: "Номер кабинета", TEXT: "Подпись", ARROW: "Стрелка" }[mark.kind];
    $("mark-text").value = mark.text || "";
    $("mark-text").placeholder = mark.kind === "BADGE" ? "201" : mark.kind === "TEXT" ? "Серверная" : "Можно пусто";
    $("mark-color").value = (mark.color || "#FFD60A").toLowerCase();
    $("mark-size").value = mark.size || 1;
    $("mark-delete").hidden = isNew;
    fieldError("mark-text", null);
    $("mark-dialog").showModal();
    $("mark-text").focus();
  }
  $("mark-cancel").addEventListener("click", () => $("mark-dialog").close());
  $("mark-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const { mark } = markEditing;
    const text = $("mark-text").value.trim();
    if (mark.kind !== "ARROW" && !text) { fieldError("mark-text", mark.kind === "BADGE" ? "Укажите номер" : "Укажите текст"); return; }
    const saved = { ...mark, text, color: $("mark-color").value.toUpperCase(), size: Number($("mark-size").value) };
    const list = marksOf().filter((a) => a.id !== saved.id).concat(saved);
    try { await saveAnnotations(list); $("mark-dialog").close(); } catch (e) { fieldError("mark-text", e.message); }
  });
  $("mark-delete").addEventListener("click", async () => {
    const { mark } = markEditing;
    try { await saveAnnotations(marksOf().filter((a) => a.id !== mark.id)); $("mark-dialog").close(); } catch (e) { fail(e); }
  });

  async function moveMark(mark, dx, dy) {
    const moved = { ...mark, x: round1(mark.x + dx), y: round1(mark.y + dy) };
    if (mark.x2 != null) Object.assign(moved, { x2: round1(mark.x2 + dx), y2: round1(mark.y2 + dy) });
    await saveAnnotations(marksOf().map((a) => (a.id === mark.id ? moved : a)));
  }

  async function saveAnnotations(annotations) {
    const floor = await ensureFloor();
    floor.annotations = annotations;
    draw();
    S.floor = await api("PATCH", `floors/${floor.id}`, { annotations });
    draw();
  }

  // ---------------------------------------------------------------- device templates

  let placeAt = null, templates = [];

  async function openPlaceDialog(at) {
    placeAt = at;
    templates = await api("GET", "device-templates");
    const sel = $("place-template");
    sel.replaceChildren(...templates.map((t) => new Option(`${t.name}${t.port_count ? ` · ${t.port_count} портов` : ""}`, t.id)));
    $("place-empty").hidden = templates.length > 0;
    sel.closest("label").hidden = !templates.length;
    $("place-save").disabled = !templates.length;
    $("place-name").value = "";
    $("place-ip").value = "";
    fieldError("place-name", null);
    $("place-error").hidden = true;
    $("place-dialog").showModal();
  }
  $("place-cancel").addEventListener("click", () => $("place-dialog").close());
  $("place-templates").addEventListener("click", () => { $("place-dialog").close(); openTab("templates"); });
  $("place-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const name = $("place-name").value.trim();
    if (!name) { fieldError("place-name", "Укажите имя"); return; }
    try {
      const floor = await ensureFloor();
      await api("POST", `device-templates/${$("place-template").value}/create-device`, {
        name, ip: $("place-ip").value.trim() || null, floor_id: floor.id, x: round1(placeAt.x), y: round1(placeAt.y),
      });
      $("place-dialog").close();
      await loadMap();
    } catch (e) {
      $("place-error").hidden = false;
      $("place-error").textContent = e.status === 409 ? "Устройство с таким именем или IP уже есть." : e.message;
    }
  });

  async function loadTemplates() {
    templates = await api("GET", "device-templates");
    const rows = $("template-rows");
    if (!templates.length) {
      rows.replaceChildren(emptyRow(6, "Шаблонов пока нет. Шаблон хранит тип, модель и порты типового устройства.", "Создать первый шаблон", () => openTemplate(null)));
      return;
    }
    rows.replaceChildren(...templates.map((t) => {
      const tr = document.createElement("tr");
      cell(tr, t.name);
      cell(tr, "").append(glyphCanvas(t.device_type), (TYPE[t.device_type] || TYPE.UNKNOWN).label);
      cell(tr, t.vendor);
      cell(tr, t.model);
      cell(tr, String(t.port_count));
      const act = cell(tr, "");
      act.className = "actions";
      const edit = document.createElement("button");
      edit.textContent = "Изменить";
      edit.addEventListener("click", () => openTemplate(t));
      act.append(edit);
      return tr;
    }));
  }

  let templateEditing = null;
  {
    const sel = $("template-type");
    for (const [id, label] of TYPES) sel.append(new Option(label, id));
  }
  function openTemplate(t) {
    templateEditing = t;
    $("template-heading").textContent = t ? t.name : "Новый шаблон";
    $("template-name").value = t ? t.name : "";
    $("template-type").value = t ? t.device_type : "SWITCH";
    $("template-vendor").value = (t && t.vendor) || "";
    $("template-model").value = (t && t.model) || "";
    $("template-ports").value = t ? t.port_count : 24;
    $("template-prefix").value = t ? t.port_prefix : "Gi0/";
    $("template-rack-h").value = (t && t.rack_h) || "";
    $("template-delete").hidden = !t;
    $("template-error").hidden = true;
    fieldError("template-name", null);
    $("template-dialog").showModal();
  }
  $("template-new").addEventListener("click", () => openTemplate(null));
  $("template-cancel").addEventListener("click", () => $("template-dialog").close());
  $("template-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const name = $("template-name").value.trim();
    if (!name) { fieldError("template-name", "Укажите название"); return; }
    const body = {
      name, device_type: $("template-type").value, vendor: $("template-vendor").value.trim() || null,
      model: $("template-model").value.trim() || null, port_count: Number.parseInt($("template-ports").value, 10) || 0,
      port_prefix: $("template-prefix").value || "Port", rack_h: Number.parseInt($("template-rack-h").value, 10) || null,
    };
    try {
      if (templateEditing) await api("PATCH", `device-templates/${templateEditing.id}`, body);
      else await api("POST", "device-templates", body);
      $("template-dialog").close();
      await loadTemplates();
    } catch (e) {
      $("template-error").hidden = false;
      $("template-error").textContent = e.status === 409 ? "Шаблон с таким названием уже есть." : e.message;
    }
  });
  $("template-delete").addEventListener("click", async () => {
    if (!templateEditing || !confirm(`Удалить шаблон «${templateEditing.name}»? Созданные из него устройства останутся.`)) return;
    try { await api("DELETE", `device-templates/${templateEditing.id}`); $("template-dialog").close(); await loadTemplates(); }
    catch (e) { $("template-error").hidden = false; $("template-error").textContent = e.message; }
  });

  // ---------------------------------------------------------------- shared form helpers

  /** An error under one field (null clears it): the field turns red and says what is wrong. */
  function fieldError(id, message) {
    const input = $(id), note = $(`${id}-error`);
    input.classList.toggle("invalid", !!message);
    if (note) { note.hidden = !message; note.textContent = message || ""; }
    if (message) input.focus();
  }

  /** A table with nothing in it: why, and the next step as a button. */
  function emptyRow(cols, text, action, onAction) {
    const tr = document.createElement("tr");
    const td = document.createElement("td");
    td.colSpan = cols;
    const box = document.createElement("div");
    box.className = "empty-state";
    box.append(Object.assign(document.createElement("span"), { className: "muted", textContent: text }));
    if (action) {
      const b = document.createElement("button");
      b.className = "primary";
      b.textContent = action;
      b.addEventListener("click", onAction);
      box.append(b);
    }
    td.append(box);
    tr.append(td);
    return tr;
  }
  $("map-fit").addEventListener("click", () => { S.touched = false; fit(); draw(); });
  $("route-point-delete").addEventListener("click", deleteSelectedPoint);
  $("route-straight").addEventListener("click", () => {
    const l = S.links.find((x) => x.id === S.selLink);
    const r = l && routeOf(l);
    if (!r) return;
    const here = new Set(r.points.map((p) => p.i));
    S.selPoint = null;
    saveRoute(l.id, l.waypoints.filter((_, i) => !here.has(i)));
  });
  $("route-delete").addEventListener("click", () => {
    const l = S.links.find((x) => x.id === S.selLink);
    if (l) deleteLink(l);
  });
  $("floor-select").addEventListener("change", (ev) => {
    S.floorId = ev.target.value;
    S.selLink = null;
    S.selPoint = null;
    S.linkFrom = null;
    loadMap().catch(fail);
  });

  // ---------------------------------------------------------------- cable colours

  const CABLE_COLORS = ["#FF453A", "#FF9F0A", "#FFD60A", "#30D158", "#0A84FF", "#BF5AF2", "#FFFFFF", "#8E8E93"];
  const selectedLink = () => S.links.find((x) => x.id === S.selLink) || null;

  function renderCableColors() {
    const box = $("cable-colors");
    const current = (selectedLink() ? selectedLink().color : S.cableColor) || null;
    const swatch = (color, title) => {
      const b = document.createElement("button");
      b.type = "button";
      b.title = title;
      b.className = (color ? color.toUpperCase() : null) === (current ? current.toUpperCase() : null) ? "active" : "";
      if (color) b.style.background = color; else { b.textContent = "∅"; b.style.fontSize = "12px"; }
      b.addEventListener("click", () => pickCableColor(color));
      return b;
    };
    const custom = document.createElement("input");
    custom.type = "color";
    custom.title = "Свой цвет";
    custom.value = current || "#ffffff";
    custom.addEventListener("change", () => pickCableColor(custom.value.toUpperCase()));
    box.replaceChildren(swatch(null, "Обычный цвет"), ...CABLE_COLORS.map((c) => swatch(c, c)), custom);
  }

  async function pickCableColor(color) {
    const l = selectedLink();
    if (!l) { S.cableColor = color; renderCableColors(); return; }
    try {
      const saved = await api("PATCH", `connections/${l.id}`, { color });
      l.color = saved.color ?? null;
    } catch (e) { fail(e); }
    renderCableColors();
    draw();
  }

  // ---------------------------------------------------------------- floors

  function renderFloorRows() {
    const rows = $("floor-rows");
    const top = [...S.floors].reverse();
    rows.replaceChildren(...top.map((f, i) => {
      const tr = document.createElement("tr");
      const input = (value, type, placeholder) => {
        const td = document.createElement("td");
        const el = document.createElement("input");
        el.type = type;
        el.value = value ?? "";
        if (placeholder) el.placeholder = placeholder;
        el.maxLength = 100;
        td.appendChild(el);
        tr.appendChild(td);
        return el;
      };
      const name = input(f.name, "text", `Этаж ${f.floor_number}`);
      const building = input(f.building_name, "text");
      const number = input(f.floor_number, "number");
      const save = () => patchFloor(f.id, {
        name: name.value.trim(), building_name: building.value.trim() || f.building_name,
        floor_number: Number.parseInt(number.value, 10) || 0,
      });
      for (const el of [name, building, number]) el.addEventListener("change", save);
      const td = document.createElement("td");
      const button = (text, title, onClick, disabled) => {
        const b = document.createElement("button");
        b.type = "button";
        b.textContent = text;
        b.title = title;
        b.disabled = !!disabled;
        b.addEventListener("click", onClick);
        td.appendChild(b);
        return b;
      };
      button("↑", "Выше", () => moveFloor(f, true), i === 0);
      button("↓", "Ниже", () => moveFloor(f, false), i === top.length - 1);
      button("Открыть", "Показать на карте", () => { S.floorId = f.id; $("floors-dialog").close(); loadMap().catch(fail); });
      button("Удалить", "Удалить пустой этаж", () => deleteFloor(f), S.floors.length < 2).classList.add("danger");
      tr.appendChild(td);
      return tr;
    }));
  }

  function floorsError(e) {
    $("floors-error").hidden = !e;
    $("floors-error").textContent = e ? e.message : "";
  }

  async function reloadFloors() {
    await loadMap();
    renderFloorRows();
  }

  async function patchFloor(id, body) {
    try { floorsError(null); await api("PATCH", `floors/${id}`, body); await reloadFloors(); } catch (e) { floorsError(e); }
  }

  async function moveFloor(f, up) {
    const i = S.floors.findIndex((x) => x.id === f.id);
    const other = S.floors[up ? i + 1 : i - 1];
    if (!other) return;
    const [na, nb] = f.floor_number !== other.floor_number
      ? [other.floor_number, f.floor_number]
      : [other.floor_number + (up ? 1 : -1), other.floor_number];
    try {
      floorsError(null);
      await api("PATCH", `floors/${f.id}`, { floor_number: na });
      await api("PATCH", `floors/${other.id}`, { floor_number: nb });
      await reloadFloors();
    } catch (e) { floorsError(e); }
  }

  async function deleteFloor(f) {
    if (!confirm(`Удалить «${floorTitle(f)}»? Удаляется только пустой этаж: его план и линии.`)) return;
    try {
      floorsError(null);
      await api("DELETE", `floors/${f.id}`);
      if (S.floorId === f.id) S.floorId = null;
      await reloadFloors();
    } catch (e) { floorsError(e); }
  }

  $("floors-edit").addEventListener("click", () => { floorsError(null); renderFloorRows(); $("floors-dialog").showModal(); });
  $("floors-close").addEventListener("click", () => $("floors-dialog").close());
  $("floor-add").addEventListener("click", async () => {
    const top = S.floors[S.floors.length - 1];
    const size = floorSize();
    try {
      floorsError(null);
      const f = await api("POST", "floors", {
        building_name: top ? top.building_name : "Здание",
        floor_number: top ? top.floor_number + 1 : 1,
        width_px: Math.ceil(size.w), height_px: Math.ceil(size.h),
      });
      S.floorId = f.id;
      await reloadFloors();
    } catch (e) { floorsError(e); }
  });
  $("map-reload").addEventListener("click", () => loadMap().catch(fail));

  // ---------------------------------------------------------------- device dialog

  const DEFAULT_RACK_UNITS = 42;
  let dialogDevice = null, dialogAt = null, dialogType = "SWITCH";
  for (const [id, label] of TYPES) {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = label;
    b.dataset.type = id;
    b.addEventListener("click", () => selectType(id));
    $("device-types").appendChild(b);
  }
  /** Fills a select with the servers a virtual machine can run on. */
  function fillHosts(select, selfId, value, none) {
    select.replaceChildren(new Option(none, ""));
    for (const h of allDevicesOrMap().filter((x) => x.type === "SERVER" && x.id !== selfId)) select.append(new Option(h.name, h.id));
    select.value = value;
  }
  const allDevicesOrMap = () => (allDevices.length ? allDevices : S.allHere);

  /** The rooms of a floor, for the "Помещение" picker. */
  function fillRooms(floorId, value) {
    const rooms = zonesOf(floorById(floorId)).filter((z) => z.is_room);
    $("device-room").replaceChildren(new Option("Не выбрано", ""), ...rooms.map((z) => new Option(z.name, z.id)));
    $("device-room").value = rooms.some((z) => z.id === value) ? value : "";
    $("device-room-label").hidden = !rooms.length;
  }
  const dialogFloorId = () => ($("device-floor-label").hidden ? S.floor && S.floor.id : $("device-floor").value);
  $("device-floor").addEventListener("change", () => fillRooms(dialogFloorId(), ""));

  function selectType(id) {
    dialogType = id;
    $("device-host-label").hidden = id !== "VIRTUAL";
    $("device-units-label").hidden = id !== "RACK";
    $("device-mount").hidden = id === "RACK" || id === "VIRTUAL";
    $("device-ports").hidden = !dialogDevice || id === "RACK" || id === "VIRTUAL";
    $("device-rack-open").hidden = !dialogDevice || id !== "RACK";
    $("device-net").hidden = id === "RACK" || id === "SOCKET" || id === "PATCH_PANEL";
    $("device-ssh-label").hidden = id !== "SWITCH";
    // Read-only SNMP: links and traffic of switches, toner and pages of printers.
    $("device-snmp-label").hidden = !["SWITCH", "PRINTER", "ROUTER", "FIREWALL", "ACCESS_POINT", "UPS"].includes(id);
    for (const b of $("device-types").children) b.classList.toggle("active", b.dataset.type === id);
  }

  /** SSH logins from «Учётные данные»; «по умолчанию» is the server's SWITCH_CREDENTIAL. */
  async function fillSshLogins(value) {
    const select = $("device-ssh");
    select.replaceChildren(new Option("По умолчанию сервера (SWITCH_CREDENTIAL)", ""));
    select.value = "";
    try {
      const creds = (await api("GET", "credentials")).filter((c) => c.kind === "ssh");
      select.append(...creds.map((c) => new Option(c.username ? `${c.name} (${c.username})` : c.name, c.id)));
      select.value = creds.some((c) => c.id === value) ? value : "";
    } catch { /* the list stays at the default */ }
  }

  function openDeviceDialog(device, at) {
    dialogDevice = device;
    dialogAt = at || null;
    $("device-heading").textContent = device ? device.name : "Новое устройство";
    $("device-name").value = device ? device.name : "";
    $("device-ip").value = (device && device.ip) || "";
    $("device-mac").value = (device && device.mac) || "";
    for (const f of ["device-name", "device-ip", "device-mac"]) fieldError(f, null);
    $("device-vendor").value = (device && device.vendor) || "";
    $("device-model").value = (device && device.model) || "";
    $("device-delete").hidden = !device;
    const floorSel = $("device-floor");
    floorSel.replaceChildren(...[...S.floors].reverse().map((f) => {
      const o = document.createElement("option");
      o.value = f.id;
      o.textContent = floorTitle(f);
      return o;
    }));
    floorSel.value = (device && device.floor_id) || (S.floor && S.floor.id) || "";
    $("device-floor-label").hidden = S.floors.length < 2;
    fillHosts($("device-host"), device && device.id, (device && device.host_id) || "", "Не указан");
    fillRooms(floorSel.value, (device && roomOf(device) && roomOf(device).id) || "");
    $("device-comment").value = (device && device.comment) || "";
    fillSshLogins(device ? device.ssh_credential_id || "" : "");
    // The community is write-only: the server only says whether one is set.
    $("device-snmp").value = "";
    $("device-snmp").placeholder = device ? (device.has_snmp_community ? "задано — пусто, чтобы не менять" : "не задано") : "public";
    $("device-snmp-state").textContent = device ? (device.has_snmp_community ? "(задано)" : "(не задано)") : "(только чтение)";
    $("device-units").value = (device && device.rack_units) || DEFAULT_RACK_UNITS;
    const rackSel = $("device-rack");
    rackSel.replaceChildren(new Option("Не в стойке", ""),
      ...allDevicesOrMap().filter((x) => x.type === "RACK" && (!device || x.id !== device.id)).map((x) => new Option(x.name, x.id)));
    rackSel.value = (device && device.rack_id) || "";
    $("device-rack-u").value = (device && device.rack_u) || "";
    $("device-rack-h").value = (device && device.rack_h) || "";
    $("device-error").hidden = true;
    selectType(device ? device.type : dialogType);
    $("device-dialog").showModal();
    $("device-name").focus();
  }

  $("device-cancel").addEventListener("click", () => $("device-dialog").close());
  const IPV4 = /^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;
  const MAC = /^[0-9a-f]{2}([:-][0-9a-f]{2}){5}$/i;
  $("device-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const ip = $("device-ip").value.trim(), mac = $("device-mac").value.trim();
    const passive = $("device-net").hidden;
    // Errors under their fields, the first one focused; nothing goes to the server until they are fixed.
    const problems = [
      ["device-mac", !passive && mac && !MAC.test(mac) ? "Нужен адрес вида aa:bb:cc:dd:ee:ff" : null],
      ["device-ip", !passive && ip && !IPV4.test(ip) && !ip.includes(":") ? "Нужен адрес вида 10.0.0.1" : null],
      ["device-name", $("device-name").value.trim() ? null : "Укажите имя"],
    ];
    for (const [f, m] of problems) fieldError(f, m);
    if (problems.some(([, m]) => m)) return;
    const body = {
      name: $("device-name").value.trim(),
      type: dialogType,
      ip: passive ? null : ip || null,
      mac: passive ? null : mac.replaceAll("-", ":").toLowerCase() || null,
      vendor: $("device-vendor").value.trim() || null,
      model: $("device-model").value.trim() || null,
      host_id: dialogType === "VIRTUAL" ? $("device-host").value || null : null,
      comment: $("device-comment").value.trim() || null,
    };
    if (dialogType === "SWITCH") body.ssh_credential_id = $("device-ssh").value || null;
    const community = $("device-snmp").value.trim();
    if (community && !$("device-snmp-label").hidden) body.snmp_community = community;
    if (dialogType === "RACK") {
      Object.assign(body, { rack_units: Number.parseInt($("device-units").value, 10) || DEFAULT_RACK_UNITS, rack_id: null });
    } else {
      const rackId = dialogType === "VIRTUAL" ? null : $("device-rack").value || null;
      Object.assign(body, {
        rack_id: rackId,
        rack_u: rackId ? Number.parseInt($("device-rack-u").value, 10) || null : null,
        rack_h: rackId ? Number.parseInt($("device-rack-h").value, 10) || 1 : null,
      });
    }
    try {
      const floorId = S.floors.length > 1 ? $("device-floor").value : null;
      // Choosing another room carries the object into it on the plan.
      const room = zonesOf(floorById(dialogFloorId())).find((z) => z.id === $("device-room").value);
      const wasIn = dialogDevice && roomOf(dialogDevice);
      let moveTo = null;
      if (room && (!wasIn || wasIn.id !== room.id)) moveTo = innerPoint(room.points);
      if (moveTo && dialogDevice) Object.assign(body, { x: round1(moveTo.x), y: round1(moveTo.y), floor_id: floorId || dialogFloorId() });
      if (moveTo && !dialogDevice) dialogAt = moveTo;
      if (dialogDevice) {
        await api("PATCH", `devices/${dialogDevice.id}`, floorId ? { ...body, floor_id: floorId } : body);
      } else {
        const floor = floorId ? { id: floorId } : await ensureFloor();
        const { w, h } = floorSize();
        const at = dialogAt || { x: w / 2, y: h / 2 };
        await api("POST", "devices", { ...body, floor_id: floor.id, x: round1(at.x), y: round1(at.y) });
      }
      $("device-dialog").close();
      await refreshTab();
    } catch (e) {
      $("device-error").hidden = false;
      $("device-error").textContent = e.status === 409 ? "Устройство с таким именем или IP уже есть." : e.message;
    }
  });
  $("device-delete").addEventListener("click", async () => {
    if (!dialogDevice || !confirm(`Удалить ${dialogDevice.name}, его порты и кабели из учёта? Само оборудование это не затрагивает.`)) return;
    try {
      await api("DELETE", `devices/${dialogDevice.id}`);
      if (cardId === dialogDevice.id) { cardId = null; $("card-dialog").close(); }
      $("device-dialog").close();
      await refreshTab();
    }
    catch (e) { $("device-error").hidden = false; $("device-error").textContent = e.message; }
  });

  // ---------------------------------------------------------------- VLANs (inventory only)

  const vlanError = (e) => { $("vlan-error").hidden = !e; $("vlan-error").textContent = e ? e.message : ""; };

  async function loadVlans() {
    const vlans = await api("GET", "vlans");
    if (!vlans.length) {
      $("vlan-rows").replaceChildren(emptyRow(6, "VLAN пока нет. Добавьте первый в строке ниже или импортируйте конфигурацию коммутатора (Порты… → Импорт конфига)."));
      return;
    }
    $("vlan-rows").replaceChildren(...vlans.map((v) => {
      const tr = document.createElement("tr");
      const td = (el) => { const c = document.createElement("td"); c.append(el); tr.append(c); return el; };
      const input = (type, value) => { const el = document.createElement("input"); el.type = type; el.value = value ?? ""; return td(el); };
      td(document.createTextNode(String(v.vlan_id)));
      const name = input("text", v.name); name.maxLength = 50;
      const subnet = input("text", v.subnet_cidr);
      const color = input("color", v.color_hex);
      const desc = input("text", v.description);
      const actions = document.createElement("td");
      const del = document.createElement("button");
      del.type = "button"; del.textContent = "Удалить"; del.className = "danger";
      actions.append(del); tr.append(actions);
      for (const el of [name, subnet, color, desc]) {
        el.addEventListener("change", async () => {
          try {
            vlanError(null);
            await api("PATCH", `vlans/${v.vlan_id}`, {
              name: name.value.trim() || v.name, subnet_cidr: subnet.value.trim() || null, color_hex: color.value, description: desc.value.trim() || null,
            });
            toast(`VLAN ${v.vlan_id} сохранён`);
          } catch (e) { vlanError(e); }
        });
      }
      del.addEventListener("click", async () => {
        if (!confirm(`Удалить VLAN ${v.vlan_id} (${v.name}) из справочника?`)) return;
        try { vlanError(null); await api("DELETE", `vlans/${v.vlan_id}`); await loadVlans(); } catch (e) { vlanError(e); }
      });
      return tr;
    }));
  }

  const CIDR = /^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\/(3[0-2]|[12]?\d)$/;
  $("vlan-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const id = Number.parseInt($("vlan-id").value, 10);
    const subnet = $("vlan-subnet").value.trim();
    const problems = [
      ["vlan-subnet", subnet && !CIDR.test(subnet) ? "Подсеть пишется так: 10.10.20.0/24" : null],
      ["vlan-name", $("vlan-name").value.trim() ? null : "Укажите название"],
      ["vlan-id", id >= 1 && id <= 4094 ? null : "Номер от 1 до 4094"],
    ];
    for (const [f, m] of problems) fieldError(f, m);
    if (problems.some(([, m]) => m)) return;
    try {
      vlanError(null);
      await api("POST", "vlans", {
        vlan_id: Number.parseInt($("vlan-id").value, 10), name: $("vlan-name").value.trim(),
        subnet_cidr: $("vlan-subnet").value.trim() || null, color_hex: $("vlan-color").value, description: $("vlan-desc").value.trim() || null,
      });
      $("vlan-form").reset();
      await loadVlans();
    } catch (e) { vlanError(e); }
  });

  // ---------------------------------------------------------------- ports (inventory only)

  let portsDevice = null;
  const portsError = (e) => { $("ports-error").hidden = !e; $("ports-error").textContent = e ? e.message : ""; };

  async function openPorts(d) {
    portsDevice = d;
    $("ports-heading").textContent = `Порты: ${d.name}`;
    portsError(null);
    await renderPorts();
    $("ports-dialog").showModal();
  }

  function parseVlans(text) {
    const ids = text.split(/[\s,;]+/).filter(Boolean).map(Number);
    if (ids.some((v) => !Number.isInteger(v) || v < 1 || v > 4094)) throw new Error("VLAN: числа от 1 до 4094 через запятую.");
    return [...new Set(ids)].sort((a, b) => a - b);
  }

  async function renderPorts() {
    const ports = (await api("GET", `devices/${portsDevice.id}/ports`)).sort((a, b) => a.number - b.number);
    portsDevice.ports = ports;
    $("port-count").value = 1;
    $("port-prefix").value = ports.length ? (ports[ports.length - 1].name.replace(/\d+$/, "") || "Gi0/") : "Gi0/";
    const rows = $("port-rows");
    rows.replaceChildren(...ports.map((p) => {
      const tr = document.createElement("tr");
      const td = (el) => { const c = document.createElement("td"); c.append(el); tr.append(c); return el; };
      const input = (type, value) => { const el = document.createElement("input"); el.type = type; el.value = value ?? ""; return td(el); };
      td(document.createTextNode(String(p.number)));
      const name = input("text", p.name); name.maxLength = 50;
      const uplink = input("checkbox"); uplink.checked = p.is_uplink;
      const mode = document.createElement("select");
      mode.append(new Option("Access", "ACCESS"), new Option("Trunk", "TRUNK"));
      mode.value = p.mode; td(mode);
      const access = input("number", p.access_vlan); access.min = 1; access.max = 4094;
      const tagged = input("text", (p.tagged_vlans || []).join(", "));
      const speed = input("number", p.speed_mbps); speed.min = 1;
      const poe = input("checkbox"); poe.checked = p.poe_enabled;
      td(document.createTextNode(p.connected_device_name || "—"));
      const actions = document.createElement("td");
      const del = document.createElement("button");
      del.type = "button"; del.textContent = "Удалить"; del.className = "danger";
      actions.append(del); tr.append(actions);
      const save = async () => {
        try {
          portsError(null);
          await api("PATCH", `ports/${p.id}`, {
            name: name.value.trim() || p.name, is_uplink: uplink.checked, mode: mode.value,
            access_vlan: access.value ? Number.parseInt(access.value, 10) : null,
            tagged_vlans: parseVlans(tagged.value), speed_mbps: Number.parseInt(speed.value, 10) || p.speed_mbps,
            poe_enabled: poe.checked,
          });
          tr.classList.remove("picked");
          toast(`Порт ${p.number} сохранён`);
        } catch (e) { portsError(e); }
      };
      for (const el of [name, uplink, mode, access, tagged, speed, poe]) el.addEventListener("change", () => { tr.classList.add("picked"); save(); });
      del.addEventListener("click", async () => {
        if (!confirm(`Удалить порт ${p.number} (${p.name}) из учёта? Кабель на нём тоже уйдёт. Оборудование это не затрагивает.`)) return;
        try { portsError(null); await api("DELETE", `ports/${p.id}`); await renderPorts(); } catch (e) { portsError(e); }
      });
      return tr;
    }));
    if (!ports.length) {
      const tr = document.createElement("tr");
      const c = cell(tr, "Портов пока нет: добавьте их ниже.");
      c.colSpan = 10; c.className = "muted";
      rows.append(tr);
    }
  }

  $("port-add").addEventListener("click", async () => {
    const count = Math.max(1, Math.min(96, Number.parseInt($("port-count").value, 10) || 1));
    const prefix = $("port-prefix").value;
    let n = Math.max(0, ...(portsDevice.ports || []).map((p) => p.number));
    try {
      portsError(null);
      for (let i = 0; i < count; i++) {
        n += 1;
        await api("POST", "ports", { device_id: portsDevice.id, number: n, name: `${prefix}${n}` });
      }
      await renderPorts();
    } catch (e) { portsError(e); await renderPorts().catch(() => {}); }
  });
  $("ports-close").addEventListener("click", () => $("ports-dialog").close());
  $("device-ports").addEventListener("click", () => {
    const d = dialogDevice;
    $("device-dialog").close();
    openPorts(d).catch(fail);
  });

  // ---------------------------------------------------------------- rack front view

  let rackDevice = null;
  const rackError = (e) => { $("rack-error").hidden = !e; $("rack-error").textContent = e ? e.message : ""; };

  async function openRack(rack) {
    rackDevice = rack;
    $("rack-heading").textContent = rack.name;
    rackError(null);
    await renderRack();
    $("rack-dialog").showModal();
  }

  const RACK_WIDTH = { narrow: 300, normal: 440, wide: 640 };
  const HEX = /^#[0-9a-fA-F]{6}$/;

  /** How the rack is drawn, from the rack device's metadata (shared with the app); unknown values fall back to the default. */
  function rackStyle(rack) {
    const m = rack.metadata || {};
    return {
      color: HEX.test(m.rack_color) ? m.rack_color : null,
      numbering: m.rack_numbering === "top" ? "top" : "bottom",
      labels: ["name", "none"].includes(m.rack_labels) ? m.rack_labels : "name_model",
      width: RACK_WIDTH[m.rack_width] ? m.rack_width : "normal",
    };
  }

  /** Writes the style over the rack_* keys and keeps every other metadata key. */
  async function saveRackStyle(patch) {
    const style = { ...rackStyle(rackDevice), ...patch };
    const metadata = { ...(rackDevice.metadata || {}) };
    if (style.color) metadata.rack_color = style.color; else delete metadata.rack_color;
    metadata.rack_numbering = style.numbering;
    metadata.rack_labels = style.labels;
    metadata.rack_width = style.width;
    try { rackError(null); await api("PATCH", `devices/${rackDevice.id}`, { metadata }); await renderRack(); } catch (e) { rackError(e); }
  }
  $("rack-color").addEventListener("change", (ev) => saveRackStyle({ color: ev.target.value }));
  $("rack-color-reset").addEventListener("click", () => saveRackStyle({ color: null }));
  for (const [id, key] of [["rack-numbering", "numbering"], ["rack-labels", "labels"], ["rack-width", "width"]]) {
    $(id).addEventListener("change", (ev) => saveRackStyle({ [key]: ev.target.value }));
  }

  async function renderRack() {
    allDevices = await api("GET", "devices");
    rackDevice = allDevices.find((d) => d.id === rackDevice.id) || rackDevice;
    const units = rackDevice.rack_units || DEFAULT_RACK_UNITS;
    const style = rackStyle(rackDevice);
    $("rack-color").value = style.color || "#8b95a5";
    $("rack-numbering").value = style.numbering;
    $("rack-labels").value = style.labels;
    $("rack-width").value = style.width;
    const view = $("rack-view");
    view.style.maxWidth = `${RACK_WIDTH[style.width]}px`;
    view.style.borderColor = style.color || "";
    const topFirst = style.numbering === "top";
    const members = allDevices.filter((d) => d.rack_id === rackDevice.id && d.rack_u != null);
    // The row a device starts on: its lowest unit when unit 1 is at the top, its highest when unit 1 is at the bottom.
    const start = new Map(members.map((d) => [topFirst ? d.rack_u : d.rack_u + (d.rack_h || 1) - 1, d]));
    const covered = new Set();
    for (const d of members) for (let u = d.rack_u; u < d.rack_u + (d.rack_h || 1); u++) covered.add(u);
    $("rack-form").hidden = true;
    const order = Array.from({ length: units }, (_, i) => (topFirst ? i + 1 : units - i));
    const rows = [];
    for (let i = 0; i < order.length; i++) {
      const u = order[i];
      const row = document.createElement("div");
      row.className = "rack-unit";
      const no = document.createElement("span");
      no.className = "no";
      no.textContent = String(u);
      const slot = document.createElement("div");
      slot.className = "rack-slot";
      if (style.color) slot.style.borderColor = style.color;
      const d = start.get(u);
      if (d) {
        const h = d.rack_h || 1;
        row.style.minHeight = `${26 * h + 2 * (h - 1)}px`;
        slot.classList.add("busy");
        const text = style.labels === "none" ? `${h} U` : `${d.name}${style.labels === "name_model" && d.model ? ` · ${d.model}` : ""} · ${h} U`;
        const label = document.createElement("span");
        label.append(glyphCanvas(d.type), ` ${text}`);
        const remove = document.createElement("button");
        remove.type = "button";
        remove.textContent = "Убрать";
        remove.addEventListener("click", async () => {
          try { rackError(null); await api("PATCH", `devices/${d.id}`, { rack_id: null }); await renderRack(); } catch (e) { rackError(e); }
        });
        slot.append(label, remove);
        row.append(no, slot);
        rows.push(row);
        // The device spans h rows: the ones it covers get no row of their own.
        i += h - 1;
        continue;
      }
      if (covered.has(u)) continue;
      slot.textContent = "свободно";
      slot.style.cursor = "pointer";
      slot.addEventListener("click", () => offerMount(u));
      row.append(no, slot);
      rows.push(row);
    }
    view.replaceChildren(...rows);
  }

  function offerMount(u) {
    const free = allDevices.filter((d) => d.type !== "RACK" && d.type !== "VIRTUAL" && !d.rack_id);
    $("rack-device").replaceChildren(...free.map((d) => new Option(`${d.name} (${typeOf(d).label})`, d.id)));
    $("rack-u").value = u;
    $("rack-h").value = 1;
    $("rack-form").hidden = false;
    rackError(free.length ? null : new Error("Все устройства уже в стойках. Добавьте новое на вкладке «Устройства»."));
  }

  $("rack-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    if (!$("rack-device").value) return;
    try {
      rackError(null);
      await api("PATCH", `devices/${$("rack-device").value}`, {
        rack_id: rackDevice.id, rack_u: Number.parseInt($("rack-u").value, 10), rack_h: Number.parseInt($("rack-h").value, 10) || 1,
      });
      await renderRack();
    } catch (e) { rackError(e); }
  });
  $("rack-close").addEventListener("click", () => { $("rack-dialog").close(); refreshTab().catch(() => {}); });
  $("device-rack-open").addEventListener("click", () => {
    const d = dialogDevice;
    $("device-dialog").close();
    openRack(d).catch(fail);
  });

  async function refreshTab() {
    if (currentTab === "map") await loadMap();
    if (currentTab === "devices") await loadDevices();
    if (currentTab === "peripherals") await loadPeripherals();
    if (currentTab === "topology") await loadTopology();
  }

  // ---------------------------------------------------------------- devices tab

  let allDevices = [];
  // Devices ticked for a group change, and the category the list is narrowed to.
  const picked = new Set();
  let typeFilter = "";
  async function loadDevices() {
    [allDevices, S.floors] = await Promise.all([api("GET", "devices"), api("GET", "floors")]);
    for (const id of [...picked]) if (!allDevices.some((d) => d.id === id)) picked.delete(id);
    renderDevices();
  }

  const isDown = (d) => !d.is_online && !PASSIVE.has(d.type) && d.type !== "RACK";
  const matchesQuery = (d, q) => !q || [d.name, d.ip, d.mac, typeOf(d).label, d.vendor, d.model, (roomOf(d) || {}).name, d.comment]
    .join(" ").toLowerCase().includes(q);
  const matchesType = (d, f) => !f || (f === "OFFLINE" ? isDown(d) : d.type === f);

  function visibleDevices() {
    const q = $("device-filter").value.trim().toLowerCase();
    return allDevices.filter((d) => matchesType(d, typeFilter) && matchesQuery(d, q));
  }

  /** The filter list with how many each choice would show for the current search; empty kinds are left out. */
  function renderTypeFilter() {
    const q = $("device-filter").value.trim().toLowerCase();
    const found = allDevices.filter((d) => matchesQuery(d, q));
    const sel = $("type-filter");
    const opts = [new Option(`Все категории · ${found.length}`, "")];
    const down = found.filter(isDown).length;
    if (down || typeFilter === "OFFLINE") opts.push(new Option(`● Не в сети · ${down}`, "OFFLINE"));
    for (const [id, label] of TYPES) {
      const n = found.filter((d) => d.type === id).length;
      if (n || typeFilter === id) opts.push(new Option(`${label} · ${n}`, id));
    }
    sel.replaceChildren(...opts);
    sel.value = typeFilter;
    $("printers-link").hidden = typeFilter !== "PRINTER";
  }

  /** Sections of the list, as in the app: by kind, by floor (bottom to top) or by room on the floor's plan. */
  function groupsOf(list, by) {
    const byName = (a, b) => a.name.localeCompare(b.name, "ru");
    if (by === "type") {
      return TYPES.map(([id, label]) => ({ title: label, items: list.filter((d) => d.type === id).sort(byName) })).filter((g) => g.items.length);
    }
    const out = [];
    for (const f of S.floors) {
      const here = list.filter((d) => d.floor_id === f.id);
      if (!here.length) continue;
      if (by === "floor") { out.push({ title: floorTitle(f), items: here.sort(byName) }); continue; }
      const rooms = new Map();
      for (const d of here) {
        const z = d.x != null ? zoneAtPoint(f, d.x, d.y, true) : null;
        const key = z ? z.name : "";
        (rooms.get(key) || rooms.set(key, []).get(key)).push(d);
      }
      const keys = [...rooms.keys()].sort((a, b) => (a === "") - (b === "") || a.localeCompare(b, "ru"));
      for (const k of keys) out.push({ title: `${floorTitle(f)} · ${k || "вне помещений"}`, items: rooms.get(k).sort(byName) });
    }
    const known = new Set(S.floors.map((f) => f.id));
    const loose = list.filter((d) => !d.floor_id || !known.has(d.floor_id));
    if (loose.length) out.push({ title: "Не на карте", items: loose.sort(byName) });
    return out;
  }

  function togglePick(id, on) {
    if (on) picked.add(id); else picked.delete(id);
    renderBulkBar();
  }

  function renderBulkBar() {
    $("bulk-bar").hidden = !picked.size;
    $("bulk-count").textContent = `Выбрано: ${picked.size}`;
    const shown = visibleDevices();
    const all = $("pick-all");
    all.checked = shown.length > 0 && shown.every((d) => picked.has(d.id));
  }

  {
    const sel = $("type-filter");
    sel.addEventListener("change", () => { typeFilter = sel.value; renderDevices(); });
    $("group-by").addEventListener("change", renderDevices);
    $("pick-all").addEventListener("change", (ev) => {
      for (const d of visibleDevices()) { if (ev.target.checked) picked.add(d.id); else picked.delete(d.id); }
      renderDevices();
    });
    $("bulk-clear").addEventListener("click", () => { picked.clear(); renderDevices(); });
  }

  function cell(tr, text) {
    const td = document.createElement("td");
    td.textContent = text ?? "—";
    tr.appendChild(td);
    return td;
  }

  function renderDevices() {
    renderTypeFilter();
    const rows = $("device-rows");
    rows.replaceChildren();
    const shown = visibleDevices();
    $("device-count").textContent = `Показано: ${shown.length}`;
    const byId = new Map(allDevices.map((x) => [x.id, x]));
    for (const group of groupsOf(shown, $("group-by").value)) {
      const head = document.createElement("tr");
      head.className = "group";
      const ht = cell(head, `${group.title} · ${group.items.length}`);
      ht.colSpan = 10;
      rows.appendChild(head);
      for (const d of group.items) rows.appendChild(deviceRow(d, byId));
    }
    if (!shown.length) {
      rows.appendChild(allDevices.length
        ? emptyRow(10, "Ничего не найдено.", "Сбросить поиск и фильтр", () => { $("device-filter").value = ""; typeFilter = ""; renderDevices(); })
        : emptyRow(10, "В учёте пока нет устройств. Найдите их сканером сети или добавьте вручную.", "Сканер сети", () => openTab("scan")));
    }
    renderBulkBar();
  }

  /** One row: a click opens the device card; the tick box picks it for a group change. */
  function deviceRow(d, byId) {
    const tr = document.createElement("tr");
    tr.className = "clickable";
    tr.classList.toggle("picked", picked.has(d.id));
    tr.addEventListener("click", (ev) => { if (!ev.target.closest("input, button")) openCard(d.id).catch(fail); });
    const pickCell = document.createElement("td");
    pickCell.className = "pick";
    const box = document.createElement("input");
    box.type = "checkbox";
    box.checked = picked.has(d.id);
    box.addEventListener("change", () => { togglePick(d.id, box.checked); tr.classList.toggle("picked", box.checked); });
    pickCell.appendChild(box);
    tr.appendChild(pickCell);
    const name = cell(tr, d.name);
    if (isDown(d)) name.append(Object.assign(document.createElement("span"), { className: "down-mark", textContent: "не в сети" }));
    cell(tr, "").append(glyphCanvas(d.type), typeOf(d).label);
    cell(tr, d.ip);
    cell(tr, d.vendor);
    cell(tr, d.model);
    cell(tr, (roomOf(d) || {}).name);
    cell(tr, d.rack_id ? `${(byId.get(d.rack_id) || {}).name || "?"}${d.rack_u ? ` · U${d.rack_u}` : ""}` : null);
    cell(tr, d.host_id ? (byId.get(d.host_id) || {}).name : null);
    const actions = cell(tr, "");
    actions.className = "actions";
    const edit = document.createElement("button");
    edit.textContent = "Изменить";
    edit.addEventListener("click", () => openDeviceDialog(d));
    actions.append(edit);
    if (d.x != null && d.type !== "VIRTUAL") {
      const show = document.createElement("button");
      show.textContent = "На карте";
      show.addEventListener("click", () => showOnMap(d.id).catch(fail));
      actions.append(" ", show);
    }
    return tr;
  }
  $("device-filter").addEventListener("input", renderDevices);

  // ---------------------------------------------------------------- group edit

  const bulkType = $("bulk-type");
  bulkType.append(new Option("Не менять", ""));
  for (const [id, label] of TYPES) bulkType.append(new Option(label, id));
  bulkType.addEventListener("change", () => { $("bulk-host-label").hidden = bulkType.value !== "VIRTUAL"; });

  $("bulk-edit").addEventListener("click", async () => {
    $("bulk-heading").textContent = `Изменить устройства: ${picked.size}`;
    bulkType.value = "";
    $("bulk-host-label").hidden = true;
    fillHosts($("bulk-host"), null, "", "Не менять");
    const floors = S.floors.length ? S.floors : await api("GET", "floors");
    const sel = $("bulk-floor");
    sel.replaceChildren(new Option("Не менять", ""), ...[...floors].reverse().map((f) => new Option(floorTitle(f), f.id)));
    $("bulk-floor-label").hidden = floors.length < 2;
    $("bulk-vendor").value = "";
    $("bulk-error").hidden = true;
    const rooms = floors.flatMap((f) => zonesOf(f).filter((z) => z.is_room).map((z) => ({ f, z })));
    $("bulk-room").replaceChildren(new Option("Не менять", ""), ...rooms.map(({ f, z }) => new Option(floors.length > 1 ? `${floorTitle(f)} · ${z.name}` : z.name, z.id)));
    bulkRooms = rooms;
    $("bulk-room-label").hidden = !rooms.length;
    $("bulk-dialog").showModal();
  });
  $("bulk-cancel").addEventListener("click", () => $("bulk-dialog").close());
  let bulkRooms = [];
  $("bulk-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const set = {};
    const room = bulkRooms.find(({ z }) => z.id === $("bulk-room").value);
    if (bulkType.value) set.type = bulkType.value;
    if ($("bulk-floor").value) set.floor_id = $("bulk-floor").value;
    if ($("bulk-vendor").value.trim()) set.vendor = $("bulk-vendor").value.trim();
    if (bulkType.value === "VIRTUAL" && $("bulk-host").value) set.host_id = $("bulk-host").value;
    if (!Object.keys(set).length && !room) {
      $("bulk-error").hidden = false;
      $("bulk-error").textContent = "Выберите, что менять.";
      return;
    }
    try {
      if (Object.keys(set).length) await api("POST", "devices/bulk", { ids: [...picked], set });
      if (room) {
        // Everyone goes inside the room's outline, spread out so they do not stack on one spot.
        const ids = [...picked];
        const spots = spreadPoints(room.z.points, ids.length);
        for (const [i, id] of ids.entries()) {
          await api("PATCH", `devices/${id}`, { x: round1(spots[i].x), y: round1(spots[i].y), floor_id: room.f.id });
        }
      }
      picked.clear();
      $("bulk-dialog").close();
      await loadDevices();
    } catch (e) {
      $("bulk-error").hidden = false;
      $("bulk-error").textContent = e.message;
    }
  });
  $("bulk-delete").addEventListener("click", async () => {
    if (!confirm(`Удалить устройств: ${picked.size}? Они пропадут из учёта и с карты вместе с портами и кабелями. Само оборудование это не затрагивает.`)) return;
    try {
      await api("POST", "devices/bulk-delete", { ids: [...picked] });
      picked.clear();
      await loadDevices();
    } catch (e) { fail(e); }
  });
  $("device-new").addEventListener("click", () => openDeviceDialog(null));

  async function showOnMap(id) {
    // Another floor, a hidden kind or a virtual machine: switch to where it is and make it visible first.
    const known = allDevices.find((x) => x.id === id);
    if (known && known.floor_id) S.floorId = known.floor_id;
    if (known) { S.hiddenTypes.delete(known.type); if (known.type === "VIRTUAL") { S.showVms = true; $("show-vms").checked = true; } }
    S.focus = id;
    openTab("map");
    await loadMap();
    const d = devicesById().get(id);
    if (!d) { toast("Этого устройства нет на карте: оно не расставлено на плане."); return; }
    S.touched = true;
    S.view.scale = Math.max(S.view.scale, 1);
    S.view.ox = canvas.clientWidth / 2 - d.x * S.view.scale;
    S.view.oy = canvas.clientHeight / 2 - d.y * S.view.scale;
    draw();
  }

  // ---------------------------------------------------------------- scan tab

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

  // ---------------------------------------------------------------- scripts tab

  let scripts = [], editing = null;

  async function loadScripts() {
    scripts = await api("GET", "scripts");
    renderScriptList();
  }

  function renderScriptList() {
    const list = $("script-list");
    list.replaceChildren();
    for (const s of scripts) {
      const li = document.createElement("li");
      li.textContent = s.title;
      if (s.critical) li.textContent += " · важный";
      li.className = editing && editing.id === s.id ? "active" : "";
      li.addEventListener("click", () => editScript(s));
      list.appendChild(li);
    }
    if (!scripts.length) {
      const li = document.createElement("li");
      li.className = "muted";
      li.textContent = "Скриптов пока нет.";
      list.appendChild(li);
    }
  }

  function paramRow(p) {
    const row = document.createElement("div");
    row.className = "param";
    const name = Object.assign(document.createElement("input"), { placeholder: "имя (латиницей)", value: p.name || "" });
    const label = Object.assign(document.createElement("input"), { placeholder: "подпись в приложении", value: p.label || "" });
    const ph = Object.assign(document.createElement("input"), { placeholder: "подсказка", value: p.placeholder || "" });
    const reqLabel = document.createElement("label");
    reqLabel.className = "check";
    const req = Object.assign(document.createElement("input"), { type: "checkbox", checked: p.required !== false });
    reqLabel.append(req, "обязательный");
    const del = Object.assign(document.createElement("button"), { type: "button", textContent: "✕", title: "Убрать параметр" });
    del.addEventListener("click", () => row.remove());
    row.append(name, label, ph, reqLabel, del);
    row.read = () => ({
      name: name.value.trim(), label: label.value.trim(), placeholder: ph.value.trim() || null, required: req.checked,
    });
    return row;
  }

  function editScript(s) {
    editing = s;
    $("script-form").hidden = false;
    $("script-heading").textContent = s ? s.title : "Новый скрипт";
    $("script-title").value = s ? s.title : "";
    $("script-description").value = s ? s.description : "";
    $("script-shell").value = s ? s.shell : "powershell";
    $("script-critical").checked = s ? s.critical : false;
    $("script-body").value = s ? s.body : "";
    $("params").replaceChildren(...(s ? s.params : []).map(paramRow));
    $("script-delete").hidden = !s;
    $("script-test").hidden = !s;
    $("script-output").hidden = true;
    $("script-msg").textContent = "";
    renderScriptList();
  }

  $("script-new").addEventListener("click", () => editScript(null));
  $("param-add").addEventListener("click", () => $("params").appendChild(paramRow({})));

  $("script-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const body = {
      title: $("script-title").value.trim(),
      description: $("script-description").value.trim(),
      shell: $("script-shell").value,
      critical: $("script-critical").checked,
      body: $("script-body").value,
      params: [...$("params").children].map((r) => r.read()).filter((p) => p.name || p.label),
    };
    try {
      const saved = editing ? await api("PATCH", `scripts/${editing.id}`, body) : await api("POST", "scripts", body);
      await loadScripts();
      editScript(scripts.find((s) => s.id === saved.id) || saved);
      $("script-msg").textContent = "Сохранено. Скрипт уже виден в приложении.";
    } catch (e) { $("script-msg").textContent = e.message; }
  });

  $("script-delete").addEventListener("click", async () => {
    if (!editing || !confirm(`Удалить скрипт «${editing.title}»?`)) return;
    try { await api("DELETE", `scripts/${editing.id}`); editing = null; $("script-form").hidden = true; await loadScripts(); }
    catch (e) { fail(e); }
  });

  $("script-test").addEventListener("click", async () => {
    if (!editing) return;
    const params = {};
    for (const p of editing.params) {
      const v = prompt(`${p.label}${p.required ? "" : " (необязательно)"}`, p.placeholder || "");
      if (v === null) return;
      if (v.trim()) params[p.name] = v.trim();
    }
    if (editing.critical && !confirm("Важный скрипт. Запустить проверку? Это эмуляция: ничего не выполнится.")) return;
    try {
      const r = await api("POST", `runbooks/${editing.runbook_id}/run`, { params, confirm: true, operator: OPERATOR });
      const out = $("script-output");
      out.hidden = false;
      out.textContent = r.stdout + (r.stderr ? `\n\n${r.stderr}` : "");
    } catch (e) { fail(e); }
  });

  // ---------------------------------------------------------------- peripherals

  // Monitors, keyboards and the like: attached to a device or kept in stock. Inventory only.
  const KINDS = [
    ["MONITOR", "Монитор"], ["KEYBOARD", "Клавиатура"], ["MOUSE", "Мышь"], ["DOCK", "Док-станция"],
    ["HEADSET", "Гарнитура"], ["WEBCAM", "Веб-камера"], ["SPEAKERS", "Колонки"], ["SCANNER", "Сканер"],
    ["PRINTER", "Принтер (USB)"], ["PROJECTOR", "Проектор"], ["UPS", "ИБП"], ["OTHER", "Другое"],
  ];
  const KIND = Object.fromEntries(KINDS);
  const NO_PERIPHERALS = new Set(["RACK", "SOCKET", "PATCH_PANEL"]);
  let peripherals = [];
  let perEditing = null;
  let perKind = "MONITOR";

  {
    const sel = $("per-kind-filter");
    sel.append(new Option("Все виды", ""));
    for (const [id, label] of KINDS) sel.append(new Option(label, id));
    for (const [id, label] of KINDS) {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = label;
      b.dataset.kind = id;
      b.addEventListener("click", () => selectKind(id));
      $("per-kinds").appendChild(b);
    }
    for (const id of ["per-filter", "per-kind-filter", "per-place-filter"]) $(id).addEventListener("input", renderPeripherals);
  }

  function selectKind(id) {
    perKind = id;
    for (const b of $("per-kinds").children) b.classList.toggle("active", b.dataset.kind === id);
  }

  async function loadPeripherals() {
    [peripherals, allDevices] = await Promise.all([api("GET", "peripherals"), api("GET", "devices")]);
    renderPeripherals();
  }

  function renderPeripherals() {
    const q = $("per-filter").value.trim().toLowerCase();
    const kind = $("per-kind-filter").value;
    const place = $("per-place-filter").value;
    const rows = $("per-rows");
    rows.replaceChildren();
    const shown = peripherals.filter((p) => {
      if (kind && p.kind !== kind) return false;
      if (place === "attached" && !p.device_id) return false;
      if (place === "stock" && p.device_id) return false;
      const hay = [KIND[p.kind], p.vendor, p.model, p.specs, p.serial_number, p.inventory_number, p.comment, p.device_name].join(" ").toLowerCase();
      return !q || hay.includes(q);
    });
    shown.sort((a, b) => (a.device_name || "￿").localeCompare(b.device_name || "￿") || a.kind.localeCompare(b.kind));
    for (const p of shown) {
      const tr = document.createElement("tr");
      cell(tr, KIND[p.kind] || p.kind);
      cell(tr, p.vendor);
      cell(tr, p.model);
      cell(tr, p.specs);
      cell(tr, p.serial_number);
      cell(tr, p.inventory_number);
      cell(tr, p.device_name || "на складе");
      const actions = cell(tr, "");
      actions.className = "actions";
      const edit = document.createElement("button");
      edit.textContent = "Изменить";
      edit.addEventListener("click", () => openPeripheral(p));
      actions.append(edit);
      rows.appendChild(tr);
    }
    if (!shown.length) {
      rows.appendChild(peripherals.length
        ? emptyRow(8, "Ничего не найдено.")
        : emptyRow(8, "Периферии пока нет: мониторы, клавиатуры, док-станции и прочее, что стоит у компьютеров.", "Добавить первую", () => openPeripheral(null)));
    }
  }

  function openPeripheral(p, deviceId) {
    perEditing = p;
    $("per-heading").textContent = p ? `${KIND[p.kind]} ${[p.vendor, p.model].filter(Boolean).join(" ")}` : "Новая периферия";
    $("per-vendor").value = (p && p.vendor) || "";
    $("per-model").value = (p && p.model) || "";
    $("per-specs").value = (p && p.specs) || "";
    $("per-serial").value = (p && p.serial_number) || "";
    $("per-inventory").value = (p && p.inventory_number) || "";
    $("per-comment").value = (p && p.comment) || "";
    const sel = $("per-device");
    sel.replaceChildren(new Option("На складе", ""));
    for (const d of [...allDevices].filter((d) => !NO_PERIPHERALS.has(d.type)).sort((a, b) => a.name.localeCompare(b.name))) {
      sel.append(new Option(`${d.name}${d.ip ? ` · ${d.ip}` : ""}`, d.id));
    }
    sel.value = (p ? p.device_id : deviceId) || "";
    selectKind(p ? p.kind : perKind);
    $("per-delete").hidden = !p;
    $("per-error").hidden = true;
    $("per-dialog").showModal();
  }

  $("per-new").addEventListener("click", () => openPeripheral(null));
  $("per-cancel").addEventListener("click", () => $("per-dialog").close());
  $("per-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const val = (id) => $(id).value.trim() || null;
    const body = {
      kind: perKind, vendor: val("per-vendor"), model: val("per-model"), specs: val("per-specs"),
      serial_number: val("per-serial"), inventory_number: val("per-inventory"), comment: val("per-comment"),
      device_id: $("per-device").value || null,
    };
    try {
      if (perEditing) await api("PATCH", `peripherals/${perEditing.id}`, body);
      else await api("POST", "peripherals", body);
      $("per-dialog").close();
      await loadPeripherals();
    } catch (e) {
      $("per-error").hidden = false;
      $("per-error").textContent = e.message;
    }
  });
  $("per-delete").addEventListener("click", async () => {
    if (!perEditing || !confirm("Удалить запись из учёта? Если вещь просто убрали, лучше выберите «На складе».")) return;
    try { await api("DELETE", `peripherals/${perEditing.id}`); $("per-dialog").close(); await loadPeripherals(); }
    catch (e) { $("per-error").hidden = false; $("per-error").textContent = e.message; }
  });

  // ---------------------------------------------------------------- topology

  // The network as a tree from the top: routers and firewalls, switches under them, end devices in a grid
  // under their switch. Same rules as the phone (TopologyLayouter.kt).
  const INFRA = new Set(["ROUTER", "FIREWALL", "SWITCH"]);
  const CELL_W = 120, CELL_H = 96, NODE_R = 18, GRID = 6;
  const topoCanvas = $("topo");
  const T = { data: null, layout: null, scale: 1, ox: 0, oy: 0, selected: null, drag: null, touched: false };

  function layoutTopology(data, all) {
    const nodes = data.nodes.filter((n) => all || INFRA.has(n.type));
    const byId = new Map(nodes.map((n) => [n.id, n]));
    const links = data.links.filter((l) => byId.has(l.a_id) && byId.has(l.b_id) && l.a_id !== l.b_id);
    const adj = new Map();
    const link = (a, b) => { if (!adj.has(a)) adj.set(a, []); adj.get(a).push(b); };
    for (const l of links) { link(l.a_id, l.b_id); link(l.b_id, l.a_id); }
    const infraIds = new Set(nodes.filter((n) => INFRA.has(n.type)).map((n) => n.id));
    const rootRank = (t) => (t === "FIREWALL" ? 3 : t === "ROUTER" ? 2 : 0);
    const infraDeg = (id) => (adj.get(id) || []).filter((x) => infraIds.has(x)).length;
    const parent = new Map(), children = new Map(), roots = [];
    const ordered = nodes.filter((n) => infraIds.has(n.id))
      .sort((a, b) => rootRank(b.type) - rootRank(a.type) || infraDeg(b.id) - infraDeg(a.id) || a.name.localeCompare(b.name));
    for (const root of ordered) {
      if (parent.has(root.id)) continue;
      parent.set(root.id, null);
      roots.push(root.id);
      const queue = [root.id];
      while (queue.length) {
        const id = queue.shift();
        const next = (adj.get(id) || []).filter((x) => infraIds.has(x) && !parent.has(x)).sort((a, b) => byId.get(a).name.localeCompare(byId.get(b).name));
        for (const n of next) {
          if (parent.has(n)) continue;
          parent.set(n, id);
          if (!children.has(id)) children.set(id, []);
          children.get(id).push(n);
          queue.push(n);
        }
      }
    }
    const leaves = new Map();
    const addLeaf = (owner, id) => { if (!leaves.has(owner)) leaves.set(owner, []); leaves.get(owner).push(id); };
    const ends = nodes.filter((n) => !infraIds.has(n.id)).sort((a, b) => a.name.localeCompare(b.name));
    const placedLeaf = new Set();
    for (const n of ends.filter((n) => n.type !== "VIRTUAL")) {
      const owner = (adj.get(n.id) || []).find((x) => infraIds.has(x));
      if (owner) { addLeaf(owner, n.id); placedLeaf.add(n.id); }
    }
    const loose = ends.filter((n) => !placedLeaf.has(n.id) && n.type !== "VIRTUAL").map((n) => n.id);
    for (const vm of ends.filter((n) => n.type === "VIRTUAL")) {
      const host = vm.host_id && byId.has(vm.host_id) ? vm.host_id : null;
      if (host && (placedLeaf.has(host) || loose.includes(host) || infraIds.has(host))) addLeaf(host, vm.id);
      else loose.push(vm.id);
    }
    const places = new Map();
    const rows = (n) => Math.ceil(n / GRID);
    const flat = (id) => (leaves.get(id) || []).flatMap((x) => [x, ...(leaves.get(x) || [])]);
    const width = (id) => Math.max(1, Math.min(flat(id).length, GRID), (children.get(id) || []).reduce((s, k) => s + width(k), 0));
    const place = (id, left, top) => {
      const w = width(id);
      places.set(id, { x: left + w / 2, y: top });
      const f = flat(id);
      const gw = Math.min(f.length, GRID);
      f.forEach((leaf, i) => places.set(leaf, { x: left + (w - gw) / 2 + (i % GRID) + 0.5, y: top + 1.6 + Math.floor(i / GRID) }));
      const childTop = top + 1.6 + rows(f.length) + (f.length ? 0.6 : 0);
      const kids = children.get(id) || [];
      const kidsW = kids.reduce((s, k) => s + width(k), 0);
      let x = left + (w - kidsW) / 2;
      let bottom = top + (f.length ? 1.6 + rows(f.length) : 1);
      for (const k of kids) { bottom = Math.max(bottom, place(k, x, childTop)); x += width(k); }
      return bottom;
    };
    let left = 0, bottom = 0;
    for (const r of roots) { bottom = Math.max(bottom, place(r, left, 0)); left += width(r) + 1; }
    const flatLoose = [...new Set(loose.flatMap((id) => [id, ...(leaves.get(id) || [])]))];
    const cols = Math.max(GRID, Math.floor(left - 1) || 1);
    const looseTop = roots.length ? bottom + 0.8 : 0;
    flatLoose.forEach((id, i) => { if (!places.has(id)) places.set(id, { x: (i % cols) + 0.5, y: looseTop + Math.floor(i / cols) }); });
    let maxX = 0, maxY = 0;
    for (const p of places.values()) { maxX = Math.max(maxX, p.x + 0.5); maxY = Math.max(maxY, p.y + 1); }
    return { places, links, nodes: nodes.filter((n) => places.has(n.id)), width: maxX, height: maxY, byId };
  }

  const centreOf = (id) => {
    const p = T.layout && T.layout.places.get(id);
    return p ? { x: p.x * CELL_W, y: p.y * CELL_H + NODE_R + 8 } : null;
  };

  async function loadTopology() {
    [T.data, allDevices] = await Promise.all([api("GET", "topology"), api("GET", "devices")]);
    relayout(!T.layout);
    const down = T.data.nodes.filter((n) => !n.is_online && n.type !== "VIRTUAL").length;
    $("topo-stats").textContent = `${T.data.nodes.length} устр. · ${T.data.links.filter((l) => l.kind === "CABLE").length} связей` + (down ? ` · не в сети: ${down}` : "");
  }

  function relayout(refit) {
    if (!T.data) return;
    T.layout = layoutTopology(T.data, $("topo-all").checked);
    if (T.selected && !T.layout.places.has(T.selected)) T.selected = null;
    if (refit || !T.touched) topoFit();
    renderTopoCard();
    drawTopology();
  }

  function topoFit() {
    if (!T.layout) return;
    const r = topoCanvas.getBoundingClientRect();
    const w = T.layout.width * CELL_W, h = T.layout.height * CELL_H;
    T.scale = Math.max(0.2, Math.min(1.2, (r.width - 48) / Math.max(w, 1), (r.height - 48) / Math.max(h, 1)));
    T.ox = (r.width - w * T.scale) / 2;
    T.oy = 24;
    T.touched = false;
  }

  function drawTopology() {
    const k = window.devicePixelRatio || 1;
    const r = topoCanvas.getBoundingClientRect();
    if (!r.width) return;
    topoCanvas.width = Math.round(r.width * k);
    topoCanvas.height = Math.round(r.height * k);
    const c = topoCanvas.getContext("2d");
    c.setTransform(k, 0, 0, k, 0, 0);
    c.clearRect(0, 0, r.width, r.height);
    if (!T.layout) return;
    c.translate(T.ox, T.oy);
    c.scale(T.scale, T.scale);
    const sel = T.selected;
    const mine = (l) => sel && (l.a_id === sel || l.b_id === sel);
    for (const l of [...T.layout.links].sort((a, b) => (mine(a) ? 1 : 0) - (mine(b) ? 1 : 0))) {
      const a = centreOf(l.a_id), b = centreOf(l.b_id);
      if (!a || !b) continue;
      const both = INFRA.has(T.layout.byId.get(l.a_id).type) && INFRA.has(T.layout.byId.get(l.b_id).type);
      c.strokeStyle = mine(l) ? "#4c9eff" : l.kind === "VIRTUAL" ? "rgba(142,155,255,.6)" : l.is_up ? `rgba(50,215,75,${both ? 0.85 : 0.45})` : "#3a4350";
      c.lineWidth = mine(l) || both ? 2.5 : 1;
      c.setLineDash(l.kind === "VIRTUAL" || l.is_up === false ? [6, 5] : []);
      c.beginPath(); c.moveTo(a.x, a.y); c.lineTo(b.x, b.y); c.stroke();
    }
    c.setLineDash([]);
    c.textAlign = "center";
    c.textBaseline = "top";
    for (const n of T.layout.nodes) {
      const p = centreOf(n.id);
      const color = typeOf(n).color;
      c.fillStyle = "#1c222c";
      c.beginPath(); c.arc(p.x, p.y, NODE_R, 0, Math.PI * 2); c.fill();
      c.strokeStyle = n.id === sel ? "#4c9eff" : color;
      c.lineWidth = n.id === sel ? 3.5 : 1.5;
      c.stroke();
      drawGlyph(c, n.type, p.x, p.y, NODE_R * 1.1, color);
      if (n.type !== "VIRTUAL") {
        c.fillStyle = n.is_online ? "#30d158" : "#ff453a";
        c.beginPath(); c.arc(p.x + NODE_R * 0.72, p.y - NODE_R * 0.72, 4.5, 0, Math.PI * 2); c.fill();
      }
      if (T.scale >= 0.45) {
        c.fillStyle = "#e6edf5";
        c.font = "11px system-ui, sans-serif";
        c.fillText(clip(c, n.name, CELL_W - 8), p.x, p.y + NODE_R + 4);
        if (n.ip) {
          c.fillStyle = "#8b95a5";
          c.font = "10px ui-monospace, monospace";
          c.fillText(n.ip, p.x, p.y + NODE_R + 18);
        }
      }
    }
  }

  function clip(c, text, max) {
    if (c.measureText(text).width <= max) return text;
    let t = text;
    while (t.length > 1 && c.measureText(`${t}…`).width > max) t = t.slice(0, -1);
    return `${t}…`;
  }

  function renderTopoCard() {
    const card = $("topo-card");
    const n = T.selected && T.data && T.data.nodes.find((x) => x.id === T.selected);
    card.hidden = !n;
    if (!n) return;
    const names = new Map(T.data.nodes.map((x) => [x.id, x.name]));
    card.replaceChildren();
    const head = document.createElement("div");
    head.className = "row";
    const title = document.createElement("strong");
    title.textContent = n.name;
    const close = document.createElement("button");
    close.textContent = "×";
    close.className = "ghost";
    close.addEventListener("click", () => { T.selected = null; renderTopoCard(); drawTopology(); });
    const spacer = document.createElement("div");
    spacer.className = "spacer";
    head.append(glyphCanvas(n.type), title, spacer, close);
    const sub = document.createElement("div");
    sub.className = "muted small";
    sub.textContent = [typeOf(n).label, n.vendor, n.model, n.ip, n.type === "VIRTUAL" ? null : n.is_online ? "в сети" : "не отвечает"].filter(Boolean).join(" · ");
    card.append(head, sub);
    const mineLinks = T.data.links.filter((l) => l.a_id === n.id || l.b_id === n.id);
    if (!mineLinks.length) {
      const none = document.createElement("div");
      none.className = "muted small";
      none.textContent = "Кабелем ни с чем не соединено";
      card.append(none);
    }
    for (const l of mineLinks) {
      const self = l.a_id === n.id;
      const other = self ? l.b_id : l.a_id;
      const row = document.createElement("div");
      row.className = "link";
      const dot = document.createElement("span");
      dot.className = "dot";
      dot.style.background = l.is_up ? "#30d158" : l.is_up === false ? "#3a4350" : "#8b95a5";
      let text;
      if (l.kind === "VIRTUAL") text = `${self ? "ВМ" : "Хост"} ${names.get(other) || "?"}`;
      else {
        text = `${(self ? l.a_port : l.b_port) || "?"} → ${names.get(other) || "?"} ${(self ? l.b_port : l.a_port) || ""}`;
        if (l.via.length) text += ` через ${l.via.join(", ")}`;
        if (l.is_up && l.speed_mbps) text += ` · ${l.speed_mbps >= 1000 ? `${l.speed_mbps / 1000} Гбит/с` : `${l.speed_mbps} Мбит/с`}`;
      }
      row.append(dot, text);
      row.addEventListener("click", () => selectNode(other, true));
      // Unplug this end: the cable at this device's port leaves the inventory, the cabling beyond stays.
      const cut = l.kind === "VIRTUAL" ? null : (self ? l.connection_ids[0] : l.connection_ids[l.connection_ids.length - 1]);
      if (cut) {
        const x = document.createElement("button");
        x.type = "button";
        x.className = "ghost";
        x.textContent = "✕";
        x.title = `Отключить: удалить кабель у порта ${(self ? l.a_port : l.b_port) || "?"}`;
        x.addEventListener("click", async (ev) => {
          ev.stopPropagation();
          if (!confirm(`Удалить из учёта кабель у порта ${(self ? l.a_port : l.b_port) || "?"} (${n.name} → ${names.get(other) || "?"})? Розетки и патч-панели на пути остаются.`)) return;
          try { await api("DELETE", `connections/${cut}`); toast("Кабель удалён"); await loadTopology(); } catch (e) { fail(e); }
        });
        const wrap = document.createElement("div");
        wrap.className = "row";
        wrap.append(row, x);
        card.append(wrap);
      } else card.append(row);
    }
    const actions = document.createElement("div");
    actions.className = "row wrap";
    const open = document.createElement("button");
    open.className = "primary";
    open.textContent = "Карточка устройства";
    open.addEventListener("click", () => openCard(n.id).catch(fail));
    const edit = document.createElement("button");
    edit.textContent = "Изменить";
    edit.addEventListener("click", () => { const d = allDevices.find((x) => x.id === n.id); if (d) openDeviceDialog(d); });
    actions.append(open, edit);
    if (n.type !== "VIRTUAL") {
      const link = document.createElement("button");
      link.textContent = "Соединить…";
      link.addEventListener("click", () => openLinkDialog(n.id).catch(fail));
      actions.append(link);
    }
    if (n.type !== "VIRTUAL") {
      const map = document.createElement("button");
      map.textContent = "На карте";
      map.addEventListener("click", () => showOnMap(n.id).catch(fail));
      actions.append(map);
    }
    card.append(actions);
  }

  function selectNode(id, centre) {
    T.selected = id;
    if (centre) {
      const p = centreOf(id);
      const r = topoCanvas.getBoundingClientRect();
      if (p) { T.ox = r.width / 2 - p.x * T.scale; T.oy = r.height / 3 - p.y * T.scale; T.touched = true; }
    }
    renderTopoCard();
    drawTopology();
  }

  function topoNodeAt(sx, sy) {
    if (!T.layout) return null;
    const wx = (sx - T.ox) / T.scale, wy = (sy - T.oy) / T.scale;
    let best = null, bestD = (NODE_R * 1.6) ** 2;
    for (const n of T.layout.nodes) {
      const p = centreOf(n.id);
      const d = (p.x - wx) ** 2 + (p.y - wy) ** 2;
      if (d < bestD) { best = n; bestD = d; }
    }
    return best;
  }

  topoCanvas.addEventListener("wheel", (ev) => {
    ev.preventDefault();
    const r = topoCanvas.getBoundingClientRect();
    const sx = ev.clientX - r.left, sy = ev.clientY - r.top;
    const scale = Math.max(0.15, Math.min(3, T.scale * Math.exp(-ev.deltaY * 0.0015)));
    T.ox = sx - (sx - T.ox) * (scale / T.scale);
    T.oy = sy - (sy - T.oy) * (scale / T.scale);
    T.scale = scale;
    T.touched = true;
    drawTopology();
  }, { passive: false });
  topoCanvas.addEventListener("pointerdown", (ev) => {
    topoCanvas.setPointerCapture(ev.pointerId);
    T.drag = { x: ev.clientX, y: ev.clientY, ox: T.ox, oy: T.oy, moved: false };
  });
  topoCanvas.addEventListener("pointermove", (ev) => {
    if (!T.drag) return;
    const dx = ev.clientX - T.drag.x, dy = ev.clientY - T.drag.y;
    if (Math.abs(dx) + Math.abs(dy) > 3) T.drag.moved = true;
    T.ox = T.drag.ox + dx;
    T.oy = T.drag.oy + dy;
    if (T.drag.moved) { T.touched = true; drawTopology(); }
  });
  topoCanvas.addEventListener("pointerup", (ev) => {
    const d = T.drag;
    T.drag = null;
    if (!d || d.moved) return;
    const r = topoCanvas.getBoundingClientRect();
    const n = topoNodeAt(ev.clientX - r.left, ev.clientY - r.top);
    T.selected = n ? n.id : null;
    renderTopoCard();
    drawTopology();
  });
  $("topo-all").addEventListener("change", () => relayout(true));
  $("topo-fit").addEventListener("click", () => { topoFit(); drawTopology(); });
  $("topo-reload").addEventListener("click", () => loadTopology().catch(fail));
  $("topo-find").addEventListener("keydown", (ev) => {
    if (ev.key !== "Enter" || !T.layout) return;
    const q = ev.target.value.trim().toLowerCase();
    if (!q) return;
    const n = T.layout.nodes.find((x) => x.name.toLowerCase() === q || x.ip === q)
      || T.layout.nodes.find((x) => x.name.toLowerCase().includes(q) || (x.ip || "").includes(q));
    if (n) selectNode(n.id, true); else toast("Не найдено", true);
  });
  new ResizeObserver(() => { if (!T.touched) topoFit(); drawTopology(); }).observe(topoCanvas);

  // ---------------------------------------------------------------- faceplates

  // A drawing of a device's front panel with its ports marked, as fractions of the drawing.
  const P = { device: null, ports: [], marks: new Map(), selected: null, w: 0.022, h: 0.28, url: null, has: false };

  async function openFaceplate(device) {
    P.device = device;
    P.marks = new Map();
    P.selected = null;
    $("plate-heading").textContent = `Схема панели · ${device.name}`;
    $("plate-error").hidden = true;
    const sources = allDevices.filter((d) => d.id !== device.id && d.type === device.type)
      .sort((a, b) => (a.model === device.model ? 0 : 1) - (b.model === device.model ? 0 : 1) || a.name.localeCompare(b.name));
    $("plate-copy").replaceChildren(new Option("Скопировать с…", ""), ...sources.map((d) => new Option(`${d.name}${d.model ? ` · ${d.model}` : ""}`, d.id)));
    try {
      P.ports = (await api("GET", `devices/${device.id}/ports`)).sort((a, b) => a.number - b.number);
      await loadPlate();
    } catch (e) { fail(e); return; }
    $("plate-dialog").showModal();
  }

  async function loadPlate() {
    let plate = null;
    try { plate = await api("GET", `devices/${P.device.id}/faceplate`); }
    catch (e) { if (e.status !== 404) throw e; }
    P.has = !!plate;
    if (P.url) URL.revokeObjectURL(P.url);
    P.url = null;
    P.marks = new Map((plate ? plate.ports : []).map((m) => [m.port_id, m]));
    if (plate) {
      P.url = URL.createObjectURL(await api("GET", `devices/${P.device.id}/faceplate/svg`));
      const last = plate.ports[plate.ports.length - 1];
      if (last) { P.w = last.w; P.h = last.h; }
    }
    $("plate-img").src = P.url || "";
    $("plate-stage").hidden = !plate;
    P.selected = (P.ports.find((p) => !P.marks.has(p.id)) || P.ports[0] || {}).id || null;
    $("plate-w").value = P.w;
    $("plate-h").value = P.h;
    renderPlate();
  }

  function plateStep() { return $("plate-step2").checked ? 2 : 1; }

  function plateSequence(fromId) {
    const i = P.ports.findIndex((p) => p.id === fromId);
    if (i < 0) return [];
    return P.ports.slice(i).filter((_, k) => k % plateStep() === 0);
  }

  function markAt(port, fx, fy) {
    const x = Math.min(Math.max(fx - P.w / 2, 0), 1 - P.w), y = Math.min(Math.max(fy - P.h / 2, 0), 1 - P.h);
    return { port_id: port.id, port_name: port.name, port_number: port.number, x, y, w: P.w, h: P.h };
  }

  function renderPlate() {
    const box = $("plate-marks");
    box.replaceChildren();
    for (const m of P.marks.values()) {
      const el = document.createElement("div");
      el.className = "plate-mark" + (m.port_id === P.selected ? " sel" : "");
      Object.assign(el.style, { left: `${m.x * 100}%`, top: `${m.y * 100}%`, width: `${m.w * 100}%`, height: `${m.h * 100}%` });
      el.textContent = m.port_number;
      el.title = m.port_name;
      box.appendChild(el);
    }
    const chips = $("plate-ports");
    chips.replaceChildren();
    for (const p of P.ports) {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = p.name;
      b.classList.toggle("marked", P.marks.has(p.id));
      b.classList.toggle("active", p.id === P.selected);
      b.addEventListener("click", () => { P.selected = p.id; renderPlate(); });
      chips.appendChild(b);
    }
    $("plate-count").textContent = P.has ? `Отмечено ${P.marks.size} из ${P.ports.length}` : "Схемы ещё нет: загрузите SVG";
    for (const id of ["plate-delete", "plate-row", "plate-unmark", "plate-save"]) $(id).disabled = !P.has;
  }

  $("plate-stage").addEventListener("click", (ev) => {
    const r = $("plate-img").getBoundingClientRect();
    const fx = (ev.clientX - r.left) / r.width, fy = (ev.clientY - r.top) / r.height;
    if (fx < 0 || fx > 1 || fy < 0 || fy > 1) return;
    const hit = [...P.marks.values()].find((m) => fx >= m.x && fx <= m.x + m.w && fy >= m.y && fy <= m.y + m.h);
    if (hit && hit.port_id !== P.selected) { P.selected = hit.port_id; renderPlate(); return; }
    const port = P.ports.find((p) => p.id === P.selected);
    if (!port) return;
    P.marks.set(port.id, markAt(port, fx, fy));
    const next = plateSequence(port.id).slice(1).find((p) => !P.marks.has(p.id));
    if (next) P.selected = next.id;
    renderPlate();
  });
  $("plate-row").addEventListener("click", () => {
    const i = P.ports.findIndex((p) => p.id === P.selected);
    const s = plateStep();
    const a = i - 2 * s >= 0 && P.marks.get(P.ports[i - 2 * s].id);
    const b = i - s >= 0 && P.marks.get(P.ports[i - s].id);
    if (!a || !b) { toast("Сначала отметьте два порта перед выбранным (с тем же шагом)", true); return; }
    const dx = b.x - a.x, dy = b.y - a.y;
    let last = b;
    for (const p of plateSequence(P.selected)) {
      const x = last.x + dx, y = last.y + dy;
      if (x < -0.001 || y < -0.001 || x + b.w > 1.001 || y + b.h > 1.001) break;
      last = { port_id: p.id, port_name: p.name, port_number: p.number, x: Math.min(Math.max(x, 0), 1 - b.w), y: Math.min(Math.max(y, 0), 1 - b.h), w: b.w, h: b.h };
      P.marks.set(p.id, last);
    }
    renderPlate();
  });
  $("plate-unmark").addEventListener("click", () => { P.marks.delete(P.selected); renderPlate(); });
  for (const id of ["plate-w", "plate-h"]) {
    $(id).addEventListener("input", () => {
      P.w = Number($("plate-w").value);
      P.h = Number($("plate-h").value);
      const m = P.marks.get(P.selected);
      const port = P.ports.find((p) => p.id === P.selected);
      if (m && port) P.marks.set(port.id, markAt(port, m.x + m.w / 2, m.y + m.h / 2));
      renderPlate();
    });
  }
  $("plate-save").addEventListener("click", async () => {
    const ports = [...P.marks.values()].map(({ port_id, x, y, w, h }) => ({ port_id, x, y, w, h }));
    try { await api("PUT", `devices/${P.device.id}/faceplate/ports`, { ports }); toast("Метки сохранены"); }
    catch (e) { $("plate-error").hidden = false; $("plate-error").textContent = e.message; }
  });
  $("plate-file").addEventListener("change", async (ev) => {
    const file = ev.target.files[0];
    ev.target.value = "";
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) { toast("SVG больше 2 МБ", true); return; }
    try {
      await api("PUT", `devices/${P.device.id}/faceplate`, undefined, { body: file, type: "image/svg+xml" });
      await loadPlate();
    } catch (e) { $("plate-error").hidden = false; $("plate-error").textContent = e.message; }
  });
  $("plate-copy").addEventListener("change", async (ev) => {
    const from = ev.target.value;
    ev.target.value = "";
    if (!from) return;
    try { await api("POST", `devices/${P.device.id}/faceplate/copy`, { from_device_id: from }); await loadPlate(); }
    catch (e) { $("plate-error").hidden = false; $("plate-error").textContent = e.message; }
  });
  $("plate-delete").addEventListener("click", async () => {
    if (!confirm("Удалить схему панели и все метки портов?")) return;
    try { await api("DELETE", `devices/${P.device.id}/faceplate`); await loadPlate(); }
    catch (e) { fail(e); }
  });
  $("plate-close").addEventListener("click", () => $("plate-dialog").close());

  // ---------------------------------------------------------------- the device card

  // One card for a device, the same from the map, the list, the network and the events, as in the app.
  let cardId = null, cardPart = "overview";
  const PORTED = new Set(["SWITCH", "ROUTER", "FIREWALL", "PATCH_PANEL"]);
  const PC_KINDS = new Set(["WORKSTATION", "SERVER", "VIRTUAL"]);
  const node = (tag, props = {}, ...kids) => { const e = Object.assign(document.createElement(tag), props); e.append(...kids); return e; };
  const linkTo = (text, onClick) => node("button", { type: "button", className: "link", textContent: text, onclick: onClick });

  /** [refresh]: redraw the card that is open now, and nothing at all if it was closed meanwhile. */
  async function openCard(id, refresh = false) {
    const [devices, floors, vlans, alerts] = await Promise.all([
      api("GET", "devices"), api("GET", "floors"), api("GET", "vlans"), api("GET", "alerts?is_resolved=false"),
    ]);
    allDevices = devices;
    S.floors = floors;
    const d = devices.find((x) => x.id === id);
    if (refresh && (cardId !== id || !$("card-dialog").open)) return;
    if (!d) { cardId = null; $("card-dialog").close(); toast("Устройство уже удалено из учёта.", true); return; }
    cardId = id;
    const byId = new Map(devices.map((x) => [x.id, x]));
    const vlanById = new Map(vlans.map((v) => [v.vlan_id, v]));
    const c = {
      d, byId, vlanById, vlans, floors,
      alerts: alerts.filter((a) => a.device_id === id),
      ports: PORTED.has(d.type) ? (await api("GET", `devices/${id}/ports`)).sort((a, b) => a.number - b.number) : [],
      printer: d.type === "PRINTER" ? (await api("GET", "printers")).find((p) => p.id === id) || null : null,
      peripherals: NO_PERIPHERALS.has(d.type) || d.type === "VIRTUAL" ? null : (await api("GET", "peripherals")).filter((p) => p.device_id === id),
    };
    if (refresh && (cardId !== id || !$("card-dialog").open)) return;
    renderCardHead(c);
    const parts = { overview: true, ports: PORTED.has(d.type), peripherals: c.peripherals != null, config: d.type === "SWITCH" };
    for (const b of $("card-tabs").children) b.hidden = !parts[b.dataset.part];
    if (!parts[cardPart]) cardPart = "overview";
    renderCardOverview(c);
    renderCardPorts(c);
    if (c.peripherals) renderCardPeripherals(c);
    renderCardConfig(c);
    showCardPart(cardPart);
    if (!$("card-dialog").open) $("card-dialog").showModal();
  }

  function renderCardHead({ d }) {
    const g = $("card-glyph").getContext("2d");
    g.clearRect(0, 0, 36, 36);
    drawGlyph(g, d.type, 18, 18, 26, typeOf(d).color);
    $("card-name").textContent = d.name;
    $("card-sub").textContent = [typeOf(d).label, d.vendor, d.model].filter(Boolean).join(" · ");
    const passive = PASSIVE.has(d.type) || d.type === "RACK";
    $("card-online").textContent = passive ? "пассивное оборудование" : d.is_online ? "● в сети" : "● не отвечает";
    $("card-online").className = passive ? "muted" : d.is_online ? "online" : "offline";
    $("card-addr").textContent = [d.ip, d.mac].filter(Boolean).join("  ·  ");
    $("card-map").hidden = d.type === "VIRTUAL";
    // Ping runs on the switch the device is cabled to: the console itself is not in the plant network.
    $("card-ping").hidden = !(d.ip && d.switch_id);
    $("card-plate").hidden = d.type === "RACK" || d.type === "SOCKET";
    // A Windows computer: the domain admin's tools (disks, processes, licences, reboot) are one click away.
    $("card-pc").hidden = !PC_KINDS.has(d.type);
    $("card-assist").hidden = !PC_KINDS.has(d.type);
  }

  function kv(rows) {
    const box = node("div", { className: "kv" });
    for (const [k, v] of rows) if (v != null && v !== "") box.append(node("span", { textContent: k }), typeof v === "string" ? node("span", { textContent: v }) : v);
    return box;
  }

  function renderCardOverview(c) {
    const { d, byId, vlanById } = c;
    const out = [];
    if (c.alerts.length) {
      out.push(node("div", { className: "card" }, node("h3", { textContent: `Открытые события · ${c.alerts.length}` }),
        ...c.alerts.map((a) => node("div", {}, node("span", { className: `sev-${a.severity}`, textContent: "● " }), a.message))));
    }
    const floor = c.floors.find((f) => f.id === d.floor_id);
    const room = floor && d.x != null ? zoneAtPoint(floor, d.x, d.y, true) : null;
    const rack = d.rack_id && byId.get(d.rack_id);
    const host = d.host_id && byId.get(d.host_id);
    const place = d.type === "VIRTUAL"
      ? [["Хост", host ? linkTo(host.name, () => openCard(host.id).catch(fail)) : "не указан"]]
      : [
        ["Этаж", floor ? floorTitle(floor) : "не на карте"],
        ["Помещение", room ? room.name : null],
        ["Стойка", rack ? linkTo(`${rack.name}${d.rack_u ? ` · U${d.rack_u}${(d.rack_h || 1) > 1 ? `–${d.rack_u + d.rack_h - 1}` : ""}` : ""}`, () => openCard(rack.id).catch(fail)) : null],
      ];
    const where = node("div", { className: "card" }, node("h3", { textContent: "Расположение" }), kv(place));
    if (d.type === "RACK") where.append(node("div", { className: "row" }, node("button", { textContent: "Вид стойки…", onclick: () => openRack(d) })));
    out.push(where);

    if (!["VIRTUAL", "RACK", "SWITCH"].includes(d.type)) {
      const sw = d.switch_id && byId.get(d.switch_id);
      const box = node("div", { className: "card" }, node("h3", { textContent: "Подключение" }));
      if (!sw) {
        box.append(node("span", { className: "muted small", textContent: "Кабель до коммутатора не проложен. Соедините устройство на карте (инструмент «Кабели» в правке) или в разделе «Сеть»." }));
      } else {
        const v = vlanById.get(d.vlan_id);
        const pick = node("select", { title: "Сменить VLAN порта (запись в учёте, на коммутатор ничего не уходит)" });
        pick.append(new Option(d.vlan_id == null ? "VLAN не задан" : `VLAN ${d.vlan_id}${v ? ` · ${v.name}` : ""}`, ""));
        for (const x of c.vlans) if (x.vlan_id !== d.vlan_id) pick.append(new Option(`→ ${x.vlan_id} · ${x.name}`, String(x.vlan_id)));
        pick.addEventListener("change", () => { if (pick.value) setPortVlan(d, Number(pick.value)).catch(fail); });
        box.append(kv([
          ["Коммутатор", linkTo(`${sw.name}${d.switch_port_name ? ` · порт ${d.switch_port_name}` : ""}`, () => openCard(sw.id).catch(fail))],
          ["VLAN", pick],
          ["Подсеть", v && v.subnet_cidr ? v.subnet_cidr : null],
        ]), node("div", { className: "row" }, linkTo(c.vlans.length ? "Справочник VLAN и подсетей" : "Добавить VLAN и подсети", () => { $("card-dialog").close(); openTab("vlans"); })));
      }
      out.push(box);
    }

    const note = node("textarea", { rows: 3, maxLength: 4000, value: d.comment || "", placeholder: "За кем закреплено, особенности, где ключ от шкафа" });
    const saveNote = node("button", { textContent: "Сохранить комментарий", disabled: true });
    note.addEventListener("input", () => { saveNote.disabled = note.value.trim() === (d.comment || ""); });
    saveNote.addEventListener("click", async () => {
      try { await api("PATCH", `devices/${d.id}`, { comment: note.value.trim() || null }); toast("Комментарий сохранён"); await openCard(d.id); } catch (e) { fail(e); }
    });
    out.push(node("div", { className: "card" }, node("h3", { textContent: "Комментарий" }), note, node("div", { className: "row" }, saveNote)));

    if (c.printer) {
      const p = c.printer;
      out.push(node("div", { className: "card" }, node("h3", { textContent: "Расходники" }),
        node("span", { className: "muted small", textContent: [p.total_pages != null ? `${p.total_pages} стр.` : "", p.polled_at ? `опрошен ${new Date(p.polled_at).toLocaleString("ru-RU")}` : "ещё не опрашивался"].filter(Boolean).join(" · ") }),
        ...p.consumables.map((x) => node("div", {}, node("div", { className: "small", textContent: `${x.name}: ${x.level_percent}%` }),
          node("div", { className: "bar" }, (() => { const i = node("i"); i.style.width = `${x.level_percent}%`; i.style.background = x.color_hex; return i; })()))),
        node("div", { className: "row" }, node("button", { textContent: "Опросить сейчас", onclick: async () => { try { await api("POST", `printers/${p.id}/refresh`); await openCard(d.id); } catch (e) { fail(e); } } }))));
    }
    const members = [...byId.values()].filter((x) => x.rack_id === d.id).sort((a, b) => (b.rack_u || 0) - (a.rack_u || 0));
    if (members.length) {
      out.push(node("div", { className: "card" }, node("h3", { textContent: "В стойке" }),
        ...members.map((m) => node("div", {}, node("span", { className: "muted", textContent: m.rack_u ? `U${m.rack_u}  ` : "" }), linkTo(m.name, () => openCard(m.id).catch(fail))))));
    }
    const guests = [...byId.values()].filter((x) => x.host_id === d.id);
    if (guests.length) {
      out.push(node("div", { className: "card" }, node("h3", { textContent: "Виртуальные машины" }),
        ...guests.map((m) => node("div", {}, linkTo(m.name, () => openCard(m.id).catch(fail)), node("span", { className: "muted", textContent: m.ip ? `  ${m.ip}` : "" })))));
    }
    $("card-overview").replaceChildren(node("div", { className: "info" }, ...out), node("pre", { id: "card-ping-out", className: "output", hidden: true }));
  }

  /** The port the device is cabled to moves to access [vlanId]: the inventory record only. */
  async function setPortVlan(d, vlanId) {
    const port = (await api("GET", `devices/${d.switch_id}/ports`)).find((p) => p.id === d.switch_port_id);
    if (!port) throw new Error("Порт не найден на сервере");
    await api("PATCH", `ports/${port.id}`, { mode: "ACCESS", access_vlan: vlanId, tagged_vlans: [] });
    toast(`Порт ${port.name} переведён в VLAN ${vlanId}`);
    await openCard(d.id);
  }

  function renderCardPorts(c) {
    const { d, ports, vlanById } = c;
    const table = node("table", { className: "grid" });
    table.append(node("thead", {}, node("tr", {}, ...["№", "Порт", "Статус", "Режим и VLAN", "Подключено"].map((t) => node("th", { textContent: t })))));
    const body = node("tbody");
    for (const p of ports) {
      const status = !p.is_admin_up ? "выключен" : !p.is_oper_up ? "нет линка" : `${p.speed_mbps >= 1000 ? `${p.speed_mbps / 1000} Гбит/с` : `${p.speed_mbps} Мбит/с`}`;
      const vlan = p.mode === "TRUNK" ? `trunk ${p.tagged_vlans.join(",") || "все"}` : p.access_vlan != null ? `${p.access_vlan} ${(vlanById.get(p.access_vlan) || {}).name || ""}` : "—";
      body.append(node("tr", {}, node("td", { textContent: String(p.number) }), node("td", { textContent: `${p.name}${p.is_uplink ? " · uplink" : ""}` }),
        node("td", { textContent: status, className: p.is_oper_up && p.is_admin_up ? "online" : "muted" }), node("td", { textContent: vlan }),
        node("td", { textContent: p.connected_device_name || "" })));
    }
    if (!ports.length) body.append(emptyRow(5, "Портов в учёте пока нет.", "Добавить порты", () => openPorts(d).catch(fail)));
    table.append(body);
    $("card-ports").replaceChildren(
      node("div", { className: "row" }, node("button", { className: "primary", textContent: "Изменить порты…", onclick: () => openPorts(d).catch(fail) }),
        node("span", { className: "muted small", textContent: "Записи учёта; на оборудование ничего не отправляется." })),
      node("div", { className: "table-wrap" }, table));
  }

  function renderCardPeripherals(c) {
    const { d } = c;
    const stock = () => peripherals.filter((p) => !p.device_id);
    const rows = c.peripherals.map((p) => node("tr", { className: "clickable", onclick: () => openPeripheral(p, d.id) },
      node("td", { textContent: KIND[p.kind] || p.kind }), node("td", { textContent: [p.vendor, p.model].filter(Boolean).join(" ") || "—" }),
      node("td", { textContent: p.specs || "" }), node("td", { textContent: p.inventory_number || "" })));
    const body = node("tbody", {}, ...rows);
    if (!rows.length) body.append(emptyRow(4, `К ${d.name} ничего не подключено.`, "Добавить монитор или клавиатуру", () => openPeripheral(null, d.id)));
    const fromStock = node("select");
    fromStock.addEventListener("change", async () => {
      if (!fromStock.value) return;
      try { await api("PATCH", `peripherals/${fromStock.value}`, { device_id: d.id }); await loadPeripherals(); await openCard(d.id); } catch (e) { fail(e); }
    });
    const fillStock = async () => {
      peripherals = await api("GET", "peripherals");
      const free = stock();
      fromStock.replaceChildren(new Option(free.length ? `Со склада (${free.length})…` : "Склад пуст", ""),
        ...free.map((p) => new Option(`${KIND[p.kind] || p.kind} ${[p.vendor, p.model].filter(Boolean).join(" ")}${p.inventory_number ? ` · ${p.inventory_number}` : ""}`, p.id)));
      fromStock.disabled = !free.length;
    };
    fillStock().catch(() => {});
    $("card-peripherals").replaceChildren(
      node("div", { className: "row" }, node("button", { className: "primary", textContent: "+ Добавить", onclick: () => openPeripheral(null, d.id) }), fromStock),
      node("div", { className: "table-wrap" }, node("table", { className: "grid" },
        node("thead", {}, node("tr", {}, ...["Вид", "Модель", "Характеристики", "Инв. №"].map((t) => node("th", { textContent: t })))), body)));
  }

  function renderCardConfig({ d }) {
    const action = (title, sub, onClick) => node("button", { type: "button", className: "config-action", onclick: onClick },
      node("strong", { textContent: title }), node("span", { className: "muted small", textContent: sub }));
    const withPorts = (fn) => () => { portsDevice = d; fn(); };
    $("card-config").replaceChildren(node("div", { className: "info" },
      action("История", "Изменения портов и снятые копии конфигурации", withPorts(() => window.NetAdmin.openDetails("history"))),
      action("Конфигурация", "Снять копию, сравнить с коммутатором или две копии", withPorts(() => window.NetAdmin.openDetails("configs"))),
      action("Трафик", "Загрузка портов за час, сутки, неделю", withPorts(() => window.NetAdmin.openDetails("traffic"))),
      action("Порты по VLAN", "Какие порты несут какой VLAN", withPorts(() => window.NetAdmin.openDetails("vlan"))),
      action("Импорт конфигурации", "Порты и VLAN из текста running-config", withPorts(() => window.NetAdmin.openImport())),
      action("Порты в учёте", "Номера, имена, режимы, VLAN, PoE", () => openPorts(d).catch(fail))));
  }

  function showCardPart(name) {
    cardPart = name;
    for (const b of $("card-tabs").children) b.classList.toggle("active", b.dataset.part === name);
    for (const part of ["overview", "ports", "peripherals", "config"]) $(`card-${part}`).hidden = part !== name;
  }
  $("card-tabs").addEventListener("click", (ev) => { const b = ev.target.closest("button[data-part]"); if (b) showCardPart(b.dataset.part); });
  $("card-close").addEventListener("click", () => $("card-dialog").close());
  $("card-dialog").addEventListener("close", () => { cardId = null; });
  const cardDevice = () => allDevices.find((x) => x.id === cardId);
  $("card-edit").addEventListener("click", () => { const d = cardDevice(); if (d) openDeviceDialog(d); });
  $("card-map").addEventListener("click", () => {
    const id = cardId;
    $("card-dialog").close();
    showOnMap(id).catch(fail);
  });
  $("card-plate").addEventListener("click", () => { const d = cardDevice(); if (d) openFaceplate(d); });
  $("card-pc").addEventListener("click", () => {
    const d = cardDevice();
    if (!d) return;
    $("card-dialog").close();
    window.NetAdmin.openPc(d.name || d.ip);
  });
  $("card-assist").addEventListener("click", () => {
    const d = cardDevice();
    if (!d) return;
    $("card-dialog").close();
    window.NetAdmin.assistPc(d.name || d.ip);
  });
  $("card-ping").addEventListener("click", async () => {
    const d = cardDevice();
    const out = $("card-ping-out");
    if (!d || !out) return;
    showCardPart("overview");
    out.hidden = false;
    out.textContent = `Ping ${d.ip} с коммутатора ${(allDevices.find((x) => x.id === d.switch_id) || {}).name || ""}…`;
    try {
      const r = await api("POST", `devices/${d.switch_id}/ping`, { target: d.ip, count: 4 });
      out.textContent = `${r.success ? "Отвечает" : "Не отвечает"}\n\n${r.command}\n${r.output}`;
    } catch (e) { out.textContent = e.message; }
  });
  // Whatever was changed in a form opened from the card shows in the card when the form closes.
  for (const id of ["device-dialog", "per-dialog", "ports-dialog", "rack-dialog", "plate-dialog", "import-dialog", "detail-dialog"]) {
    $(id).addEventListener("close", () => { if (cardId && $("card-dialog").open) openCard(cardId, true).catch(() => {}); });
  }

  // ---------------------------------------------------------------- new links (the network view)

  const NETWORK_GEAR = new Set(["SWITCH", "ROUTER", "FIREWALL"]);
  const linkError = (e) => { $("link-error").hidden = !e; $("link-error").textContent = e ? e.message : ""; };

  /** A cable between two devices, with the ports picked or left to the server. Inventory only. */
  async function openLinkDialog(fromId) {
    allDevices = await api("GET", "devices");
    const cabled = allDevices.filter((d) => d.type !== "VIRTUAL")
      .sort((a, b) => (NETWORK_GEAR.has(b.type) - NETWORK_GEAR.has(a.type)) || a.name.localeCompare(b.name, "ru"));
    const options = () => cabled.map((d) => new Option(`${d.name}${d.ip ? ` · ${d.ip}` : ""} · ${typeOf(d).label}`, d.id));
    $("link-a").replaceChildren(new Option("Выберите устройство", ""), ...options());
    $("link-b").replaceChildren(new Option("Выберите устройство", ""), ...options());
    $("link-a").value = fromId || "";
    $("link-b").value = "";
    linkError(null);
    fieldError("link-b", null);
    await Promise.all([fillLinkPorts("a"), fillLinkPorts("b")]);
    $("link-dialog").showModal();
  }

  async function fillLinkPorts(end) {
    const id = $(`link-${end}`).value;
    const sel = $(`link-${end}-port`);
    sel.replaceChildren(new Option("Любой свободный", ""));
    if (!id) return;
    const ports = (await api("GET", `devices/${id}/ports`)).sort((a, b) => a.number - b.number);
    for (const p of ports) {
      const o = new Option(p.connected_device_name ? `${p.name} — занят: ${p.connected_device_name}` : p.name, p.id);
      o.disabled = !!p.connected_device_name;
      sel.append(o);
    }
  }
  $("link-a").addEventListener("change", () => fillLinkPorts("a").catch(fail));
  $("link-b").addEventListener("change", () => fillLinkPorts("b").catch(fail));
  $("link-cancel").addEventListener("click", () => $("link-dialog").close());
  $("link-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const a = $("link-a").value, b = $("link-b").value;
    if (!a) { fieldError("link-a", "Выберите устройство"); return; }
    if (!b || a === b) { fieldError("link-b", !b ? "Выберите устройство" : "Нужно другое устройство"); return; }
    fieldError("link-b", null);
    try {
      await api("POST", "connections/link", {
        device_a_id: a, device_b_id: b, cable_type: $("link-cable").value,
        port_a_id: $("link-a-port").value || null, port_b_id: $("link-b-port").value || null,
      });
      $("link-dialog").close();
      toast("Соединено");
      await refreshTab();
    } catch (e) { linkError(e); }
  });
  $("topo-new-link").addEventListener("click", () => openLinkDialog(T.selected).catch(fail));
  $("topo-new-device").addEventListener("click", async () => {
    // A new device stands in the middle of the first floor; its place is set on the map later.
    if (!S.floor) { S.floors = await api("GET", "floors"); S.floor = S.floors[0] || null; }
    openDeviceDialog(null);
  });

  // What admin-more.js (the other tabs) builds on.
  window.NetAdmin = {
    $, api, toast, fail, typeOf, glyphCanvas, OPERATOR, tabs: {},
    picked, renderPorts: () => renderPorts(), portsDevice: () => portsDevice,
    openCard: (id) => openCard(id).catch(fail),
    openTab: (name) => openTab(name),
    showMode: () => showMode(),
  };

  // ---------------------------------------------------------------- go

  new ResizeObserver(() => { if (!S.touched) fit(); draw(); }).observe(canvas);
  if (token) {
    api("GET", "floors").then(start, (e) => { if (e.status !== 401) { logout(e.message); } });
  } else {
    logout("");
  }
})();
