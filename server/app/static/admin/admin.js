"use strict";
// NetAdmin web console. Talks to the same /api/v1 as the phone with the same API token,
// so whatever is entered here is what the app shows. Inventory only: nothing here reaches equipment.
(() => {
  const $ = (id) => document.getElementById(id);
  const TYPES = [
    ["SWITCH", "Коммутатор", "SW"], ["ACCESS_POINT", "Точка доступа", "AP"], ["CAMERA", "Камера", "CAM"],
    ["PRINTER", "МФУ", "МФУ"], ["WORKSTATION", "Рабочее место", "PC"], ["SERVER", "Сервер", "SRV"],
    ["PATCH_PANEL", "Патч-панель", "PP"], ["SOCKET", "Розетка", "R"], ["UNKNOWN", "Устройство", "?"],
  ];
  const TYPE = Object.fromEntries(TYPES.map(([id, label, short]) => [id, { label, short }]));
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
    floor: null, devices: [], links: [], image: null, imageUrl: null,
    tool: "move", pen: PENS[0], linkFrom: null, cable: "Cat6",
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
    S.floor = floors[0] || null;
    // As on the phone: devices without a floor show on the first one.
    S.devices = devices
      .filter((d) => !S.floor || d.floor_id === S.floor.id || d.floor_id == null)
      .map((d) => ({ ...d, x: d.x ?? 40, y: d.y ?? 40 }));
    S.links = links;
    await loadPlanImage();
    if (!S.touched) fit();
    draw();
    hint();
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

    for (const d of (S.floor && S.floor.drawings) || []) polyline(d.points, d.color, d.width * k);
    if (S.stroke && S.stroke.length > 3) polyline(S.stroke, S.pen, 3);

    const byId = devicesById();
    for (const l of S.links) {
      const a = byId.get(l.device_a_id), b = byId.get(l.device_b_id);
      if (!a || !b) continue;
      const fiber = /^O[MS]/i.test(l.cable_type || "");
      ctx.strokeStyle = fiber ? "rgba(90,200,250,.8)" : "rgba(139,149,165,.8)";
      ctx.lineWidth = fiber ? 2.5 : 1.5;
      ctx.setLineDash(fiber ? [] : [6, 4]);
      ctx.beginPath();
      ctx.moveTo(...toScreen(a.x, a.y));
      ctx.lineTo(...toScreen(b.x, b.y));
      ctx.stroke();
    }
    ctx.setLineDash([]);

    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    for (const d of S.devices) {
      const [sx, sy] = toScreen(d.x, d.y);
      const passive = PASSIVE.has(d.type);
      const rad = passive ? 10 : 15;
      const active = d.id === S.linkFrom || (S.drag && S.drag.id === d.id);
      ctx.beginPath();
      ctx.arc(sx, sy, rad, 0, Math.PI * 2);
      ctx.fillStyle = active ? "#4c9eff" : "#1c222c";
      ctx.fill();
      ctx.strokeStyle = active ? "#4c9eff" : "#6b7686";
      ctx.lineWidth = 1.5;
      ctx.stroke();
      ctx.fillStyle = "#e6edf5";
      ctx.font = `600 ${passive ? 8 : 9}px system-ui, sans-serif`;
      ctx.fillText((TYPE[d.type] || TYPE.UNKNOWN).short, sx, sy);
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
    const byId = devicesById();
    let best = null, bestDist = 7;
    for (const l of S.links) {
      const a = byId.get(l.device_a_id), b = byId.get(l.device_b_id);
      if (!a || !b) continue;
      const [ax, ay] = toScreen(a.x, a.y), [bx, by] = toScreen(b.x, b.y);
      const dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
      const t = len2 ? Math.max(0, Math.min(1, ((sx - ax) * dx + (sy - ay) * dy) / len2)) : 0;
      const dist = Math.hypot(sx - (ax + dx * t), sy - (ay + dy * t));
      if (dist < bestDist) { best = l; bestDist = dist; }
    }
    return best;
  }

  function hint() {
    const texts = {
      move: "Перетащите объект мышью, щёлкните по нему, чтобы изменить. Двойной щелчок по пустому месту добавляет объект. Колесо мыши масштабирует, перетаскивание пустого места двигает карту.",
      link: S.linkFrom
        ? `От ${devicesById().get(S.linkFrom)?.name || "?"}: щёлкните второе устройство (Esc — отмена).`
        : "Щёлкните два устройства, чтобы соединить их кабелем. Щелчок по кабелю удаляет его.",
      draw: "Рисуйте мышью. Линии видны на карте в приложении.",
      plan: S.floor && S.floor.plan_image_url
        ? "Перетащите план мышью, чтобы совместить его с устройствами. «−» и «+» меняют размер."
        : "Загрузите план здания: PNG, JPEG или SVG. PDF можно загрузить с телефона: приложение само превращает его в картинку.",
    };
    $("map-hint").textContent = texts[S.tool];
    $("link-hint").textContent = S.tool === "link" && S.linkFrom ? "выбрано первое устройство" : "";
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
    if (S.tool === "move" && device) {
      gesture.kind = "device";
      S.drag = { id: device.id, x0: device.x, y0: device.y };
    } else if (S.tool === "link" && device) {
      gesture.kind = "link-device";
    } else if (S.tool === "link" && linkAt(sx, sy)) {
      gesture.kind = "link-cable";
      gesture.link = linkAt(sx, sy);
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
      deleteLink(g.link);
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
    } else if (g.kind === "pan" && !g.moved && S.tool === "link" && S.linkFrom) {
      S.linkFrom = null;
      hint();
    }
    draw();
  }
  canvas.addEventListener("pointerup", endGesture);
  canvas.addEventListener("pointercancel", endGesture);

  canvas.addEventListener("dblclick", (ev) => {
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
    if (ev.key === "Escape" && S.linkFrom) { S.linkFrom = null; hint(); draw(); }
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
      await api("POST", "connections/link", { device_a_id: from, device_b_id: d.id, cable_type: $("cable").value });
      await loadMap();
    } catch (e) { fail(e); }
  }

  async function deleteLink(l) {
    const byId = devicesById();
    const names = `${byId.get(l.device_a_id)?.name || "?"} — ${byId.get(l.device_b_id)?.name || "?"}`;
    if (!confirm(`Удалить кабель ${names} (${l.cable_type})?`)) return;
    try { await api("DELETE", `connections/${l.id}`); await loadMap(); } catch (e) { fail(e); }
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
  $("map-reload").addEventListener("click", () => loadMap().catch(fail));

  // ---------------------------------------------------------------- device dialog

  let dialogDevice = null, dialogAt = null, dialogType = "SWITCH";
  for (const [id, label] of TYPES) {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = label;
    b.dataset.type = id;
    b.addEventListener("click", () => selectType(id));
    $("device-types").appendChild(b);
  }
  function selectType(id) {
    dialogType = id;
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
    };
    try {
      if (dialogDevice) {
        await api("PATCH", `devices/${dialogDevice.id}`, body);
      } else {
        const floor = await ensureFloor();
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

  async function refreshTab() {
    if (currentTab === "map") await loadMap();
    if (currentTab === "devices") await loadDevices();
  }

  // ---------------------------------------------------------------- devices tab

  let allDevices = [];
  async function loadDevices() {
    allDevices = await api("GET", "devices");
    renderDevices();
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
      const label = (TYPE[d.type] || TYPE.UNKNOWN).label;
      const hay = [d.name, d.ip, label, d.vendor, d.model].join(" ").toLowerCase();
      if (q && !hay.includes(q)) continue;
      const tr = document.createElement("tr");
      cell(tr, d.name);
      cell(tr, label);
      cell(tr, d.ip);
      cell(tr, d.vendor);
      cell(tr, d.model);
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
      td.colSpan = 7;
      td.className = "muted";
      rows.appendChild(tr);
    }
  }
  $("device-filter").addEventListener("input", renderDevices);
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
      cell(tr, (TYPE[d.type] || TYPE.UNKNOWN).label);
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

  // ---------------------------------------------------------------- go

  new ResizeObserver(() => { if (!S.touched) fit(); draw(); }).observe(canvas);
  if (token) {
    api("GET", "floors").then(start, (e) => { if (e.status !== 401) { logout(e.message); } });
  } else {
    logout("");
  }
})();
