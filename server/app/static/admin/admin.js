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
      $("mode").textContent = h.hal_mode === "emulator"
        ? "эмулятор оборудования"
        : `оборудование: ${h.hal_mode} · запись ${h.device_writes_enabled ? "включена" : "выключена"}`;
    } catch { $("mode").textContent = ""; }
  }

  // ---------------------------------------------------------------- tabs

  let currentTab = "map";
  $("tabs").addEventListener("click", (ev) => {
    const b = ev.target.closest("button[data-tab]");
    if (b) openTab(b.dataset.tab);
  });

  function openTab(name) {
    currentTab = name;
    for (const b of $("tabs").querySelectorAll("button")) b.classList.toggle("active", b.dataset.tab === name);
    for (const s of document.querySelectorAll("main > .tab")) s.hidden = s.id !== `tab-${name}`;
    if (name === "map") loadMap().catch(fail);
    if (name === "devices") loadDevices().catch(fail);
    if (name === "vlans") loadVlans().catch(fail);
    if (window.NetAdmin.tabs[name]) Promise.resolve(window.NetAdmin.tabs[name]()).catch(fail);
    if (name === "scan") loadLatestScan().catch(() => {});
    if (name === "scripts") loadScripts().catch(fail);
  }

  function start() {
    $("login").hidden = true;
    $("app").hidden = false;
    showMode();
    openTab(currentTab);
  }

  // ---------------------------------------------------------------- map state

  const S = {
    floor: null, floors: [], floorId: null, allHere: [], showVms: false, hiddenTypes: new Set(), devices: [], remote: new Map(), links: [], image: null, imageUrl: null,
    // The cable whose route is being edited, its picked bend, and a bend being dragged.
    selLink: null, selPoint: null, routeDrag: null,
    tool: "move", pen: PENS[0], linkFrom: null, cable: "Cat6",
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
    const [floors, devices, links] = await Promise.all([api("GET", "floors"), api("GET", "devices"), api("GET", "connections")]);
    S.floors = floors;
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
    for (const d of S.devices) {
      const [sx, sy] = toScreen(d.x, d.y);
      const passive = PASSIVE.has(d.type);
      const rad = passive ? 10 : 15;
      const active = d.id === S.linkFrom || (S.drag && S.drag.id === d.id);
      // A virtual machine has no cables: a dashed ring says it is not physically there.
      const virtual = d.type === "VIRTUAL";
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
      ctx.font = "11px system-ui, sans-serif";
      ctx.fillStyle = "rgba(11,14,19,.75)";
      const tw = ctx.measureText(d.name).width;
      ctx.fillRect(sx - tw / 2 - 3, sy + rad + 3, tw + 6, 15);
      ctx.fillStyle = "#e6edf5";
      ctx.fillText(d.name, sx, sy + rad + 11);
    }
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
    for (let i = S.devices.length - 1; i >= 0; i--) {
      const d = S.devices[i];
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

  canvas.addEventListener("wheel", (ev) => {
    ev.preventDefault();
    S.touched = true;
    const factor = Math.exp(-ev.deltaY * 0.0015);
    const scale = Math.max(0.02, Math.min(20, S.view.scale * factor));
    const f = scale / S.view.scale;
    S.view.ox = ev.offsetX - (ev.offsetX - S.view.ox) * f;
    S.view.oy = ev.offsetY - (ev.offsetY - S.view.oy) * f;
    S.view.scale = scale;
    draw();
  }, { passive: false });

  document.addEventListener("keydown", (ev) => {
    if (ev.key === "Escape" && S.zoneDraft && !ev.target.closest("dialog")) cancelZone();
    if (ev.key === "Escape" && (S.linkFrom || S.selLink)) { S.linkFrom = null; S.selLink = null; S.selPoint = null; hint(); draw(); }
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

  $("tools").addEventListener("click", (ev) => {
    const b = ev.target.closest("button[data-tool]");
    if (!b) return;
    S.tool = b.dataset.tool;
    S.linkFrom = null;
    S.selLink = null;
    S.selPoint = null;
    S.zoneDraft = null;
    fitDraftButtons();
    for (const x of $("tools").querySelectorAll("button")) x.classList.toggle("active", x === b);
    for (const o of document.querySelectorAll(".tool-opts")) o.hidden = o.dataset.for !== S.tool;
    hint();
    draw();
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
  $("add-device").addEventListener("click", () => {
    const [x, y] = toPlan(canvas.clientWidth / 2, canvas.clientHeight / 2);
    const { w, h } = floorSize();
    openDeviceDialog(null, { x: Math.max(0, Math.min(w, x)), y: Math.max(0, Math.min(h, y)) });
  });
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
    for (const b of $("device-types").children) b.classList.toggle("active", b.dataset.type === id);
  }

  function openDeviceDialog(device, at) {
    dialogDevice = device;
    dialogAt = at || null;
    $("device-heading").textContent = device ? device.name : "Новый объект";
    $("device-name").value = device ? device.name : "";
    $("device-ip").value = (device && device.ip) || "";
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
  $("device-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const body = {
      name: $("device-name").value.trim(),
      type: dialogType,
      ip: $("device-ip").value.trim() || null,
      vendor: $("device-vendor").value.trim() || null,
      model: $("device-model").value.trim() || null,
      host_id: dialogType === "VIRTUAL" ? $("device-host").value || null : null,
      comment: $("device-comment").value.trim() || null,
    };
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
    if (!dialogDevice || !confirm(`Удалить ${dialogDevice.name} и его кабели из инвентаря? Само оборудование это не затрагивает.`)) return;
    try { await api("DELETE", `devices/${dialogDevice.id}`); $("device-dialog").close(); await refreshTab(); }
    catch (e) { $("device-error").hidden = false; $("device-error").textContent = e.message; }
  });

  // ---------------------------------------------------------------- VLANs (inventory only)

  const vlanError = (e) => { $("vlan-error").hidden = !e; $("vlan-error").textContent = e ? e.message : ""; };

  async function loadVlans() {
    const vlans = await api("GET", "vlans");
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
              name: name.value.trim() || v.name, subnet_cidr: subnet.value.trim() || v.subnet_cidr, color_hex: color.value, description: desc.value.trim() || null,
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

  $("vlan-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    try {
      vlanError(null);
      await api("POST", "vlans", {
        vlan_id: Number.parseInt($("vlan-id").value, 10), name: $("vlan-name").value.trim(),
        subnet_cidr: $("vlan-subnet").value.trim(), color_hex: $("vlan-color").value, description: $("vlan-desc").value.trim() || null,
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
        if (!confirm(`Удалить порт ${p.number} (${p.name}) из инвентаря? Кабель на нём тоже уйдёт. Оборудование это не затрагивает.`)) return;
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

  async function renderRack() {
    allDevices = await api("GET", "devices");
    rackDevice = allDevices.find((d) => d.id === rackDevice.id) || rackDevice;
    const units = rackDevice.rack_units || DEFAULT_RACK_UNITS;
    const members = allDevices.filter((d) => d.rack_id === rackDevice.id && d.rack_u != null);
    const top = new Map(members.map((d) => [d.rack_u + (d.rack_h || 1) - 1, d]));
    const covered = new Set();
    for (const d of members) for (let u = d.rack_u; u < d.rack_u + (d.rack_h || 1); u++) covered.add(u);
    $("rack-form").hidden = true;
    const rows = [];
    for (let u = units; u >= 1; u--) {
      const row = document.createElement("div");
      row.className = "rack-unit";
      const no = document.createElement("span");
      no.className = "no";
      no.textContent = String(u);
      const slot = document.createElement("div");
      slot.className = "rack-slot";
      const d = top.get(u);
      if (d) {
        const h = d.rack_h || 1;
        row.style.minHeight = `${26 * h + 2 * (h - 1)}px`;
        slot.classList.add("busy");
        const label = document.createElement("span");
        label.append(glyphCanvas(d.type), ` ${d.name} · ${h} U`);
        const remove = document.createElement("button");
        remove.type = "button";
        remove.textContent = "Убрать";
        remove.addEventListener("click", async () => {
          try { rackError(null); await api("PATCH", `devices/${d.id}`, { rack_id: null }); await renderRack(); } catch (e) { rackError(e); }
        });
        slot.append(label, remove);
        row.append(no, slot);
        rows.push(row);
        // The device spans h units: the rows it covers below get no row of their own.
        u -= h - 1;
        continue;
      }
      if (covered.has(u)) continue;
      slot.textContent = "свободно";
      slot.style.cursor = "pointer";
      slot.addEventListener("click", () => offerMount(u));
      row.append(no, slot);
      rows.push(row);
    }
    $("rack-view").replaceChildren(...rows);
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

  function visibleDevices() {
    const q = $("device-filter").value.trim().toLowerCase();
    return allDevices.filter((d) => {
      if (typeFilter && d.type !== typeFilter) return false;
      return !q || [d.name, d.ip, typeOf(d).label, d.vendor, d.model].join(" ").toLowerCase().includes(q);
    });
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
    sel.append(new Option("Все категории", ""));
    for (const [id, label] of TYPES) sel.append(new Option(label, id));
    sel.addEventListener("change", () => { typeFilter = sel.value; renderDevices(); });
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
    const q = $("device-filter").value.trim().toLowerCase();
    const rows = $("device-rows");
    rows.replaceChildren();
    for (const d of allDevices) {
      const label = typeOf(d).label;
      const hay = [d.name, d.ip, label, d.vendor, d.model, (roomOf(d) || {}).name, d.comment].join(" ").toLowerCase();
      if (q && !hay.includes(q)) continue;
      if (typeFilter && d.type !== typeFilter) continue;
      const tr = document.createElement("tr");
      tr.classList.toggle("picked", picked.has(d.id));
      const pickCell = document.createElement("td");
      pickCell.className = "pick";
      const box = document.createElement("input");
      box.type = "checkbox";
      box.checked = picked.has(d.id);
      box.addEventListener("change", () => { togglePick(d.id, box.checked); tr.classList.toggle("picked", box.checked); });
      pickCell.appendChild(box);
      tr.appendChild(pickCell);
      cell(tr, d.name);
      const typeCell = cell(tr, "");
      typeCell.append(glyphCanvas(d.type), label);
      cell(tr, d.ip);
      cell(tr, d.vendor);
      cell(tr, d.model);
      cell(tr, (roomOf(d) || {}).name);
      cell(tr, d.rack_id ? `${(allDevices.find((h) => h.id === d.rack_id) || {}).name || "?"}${d.rack_u ? ` · U${d.rack_u}` : ""}` : null);
      cell(tr, d.host_id ? (allDevices.find((h) => h.id === d.host_id) || {}).name : null);
      cell(tr, d.x != null ? "да" : "нет");
      const actions = cell(tr, "");
      actions.className = "actions";
      const edit = document.createElement("button");
      edit.textContent = "Изменить";
      edit.addEventListener("click", () => openDeviceDialog(d));
      const show = document.createElement("button");
      show.textContent = "На карте";
      show.addEventListener("click", () => showOnMap(d.id));
      actions.append(edit, " ", show);
      rows.appendChild(tr);
    }
    if (!rows.children.length) {
      const tr = document.createElement("tr");
      const td = cell(tr, allDevices.length ? "Ничего не найдено" : "Устройств пока нет: добавьте их здесь или найдите сканером сети.");
      td.colSpan = 11;
      td.className = "muted";
      rows.appendChild(tr);
    }
    renderBulkBar();
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
    if (!confirm(`Удалить устройств: ${picked.size}? Они пропадут из инвентаря и с карты вместе с портами и кабелями. Само оборудование это не затрагивает.`)) return;
    try {
      await api("POST", "devices/bulk-delete", { ids: [...picked] });
      picked.clear();
      await loadDevices();
    } catch (e) { fail(e); }
  });
  $("device-new").addEventListener("click", () => openDeviceDialog(null));

  async function showOnMap(id) {
    openTab("map");
    await loadMap();
    const d = devicesById().get(id);
    if (!d) return;
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

  // What admin-more.js (the other tabs) builds on.
  window.NetAdmin = {
    $, api, toast, fail, typeOf, glyphCanvas, OPERATOR, tabs: {},
    picked, renderPorts: () => renderPorts(), portsDevice: () => portsDevice,
  };

  // ---------------------------------------------------------------- go

  new ResizeObserver(() => { if (!S.touched) fit(); draw(); }).observe(canvas);
  if (token) {
    api("GET", "floors").then(start, (e) => { if (e.status !== 401) { logout(e.message); } });
  } else {
    logout("");
  }
})();
