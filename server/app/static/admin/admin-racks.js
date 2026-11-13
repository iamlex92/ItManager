"use strict";
// «Стойки»: the server room as a row of racks drawn like the real ones, from the front and from the back. Devices look
// like what they are (a switch shows its ports, a server its drive bays, a UPS its display), are dragged onto free units,
// and the rear of a patch panel carries a label for each port: where the cable goes.
(() => {
  const { $, api, toast, fail, askText, typeOf, openCard, openTab, tabs, isAlarm, offLabel } = window.NetAdmin;

  const U = 24; // height of one unit, px
  const DEFAULT_H = { SWITCH: 1, ROUTER: 1, FIREWALL: 1, PATCH_PANEL: 1, ACCESS_POINT: 1, SERVER: 2, UPS: 3 };
  const NOT_RACKABLE = new Set(["RACK", "VIRTUAL", "SOCKET"]);
  const PORT_KINDS = new Set(["SWITCH", "ROUTER", "FIREWALL", "ACCESS_POINT", "PATCH_PANEL"]);
  const HEX = /^#[0-9a-fA-F]{6}$/;

  let devices = [], ports = new Map();
  let side = "front", selected = null, selectedPort = null, pick = null, dragging = null, focusRack = null, timer = 0;

  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  const heightOf = (d) => d.rack_h || DEFAULT_H[d.type] || 1;
  const sideOf = (d) => ((d.metadata || {}).rack_side === "front" || (d.metadata || {}).rack_side === "rear" ? d.metadata.rack_side : "both");
  const rackStyle = (r) => {
    const m = r.metadata || {};
    return { color: HEX.test(m.rack_color) ? m.rack_color : null, topFirst: m.rack_numbering === "top" };
  };
  const passive = (d) => d.type === "PATCH_PANEL" || d.type === "SOCKET";

  // ------------------------------------------------------------------ data

  async function load() {
    devices = await api("GET", "devices");
    const inRack = devices.filter((d) => d.rack_id && PORT_KINDS.has(d.type));
    const lists = await Promise.all(inRack.map((d) => api("GET", `devices/${d.id}/ports`).catch(() => [])));
    ports = new Map(inRack.map((d, i) => [d.id, lists[i].sort((a, b) => a.number - b.number)]));
    if (selected && !devices.some((d) => d.id === selected)) selected = null;
    render();
    clearInterval(timer);
    timer = setInterval(() => { if ($("tab-racks").hidden) clearInterval(timer); else if (!dragging) load().catch(() => {}); }, 30_000);
  }
  tabs.racks = load;
  // The card closed: nothing is selected in the rack any more, and the side panel comes back.
  $("card-dialog").addEventListener("close", () => { if (selected) { selected = null; selectedPort = null; render(); } });
  window.NetAdmin.openRacks = (id) => { focusRack = id || null; openTab("racks"); };

  async function patchDevice(d, body) {
    try {
      await api("PATCH", `devices/${d.id}`, body);
      await load();
      // A change made from the card of that device: the card follows.
      if ($("card-dialog").open && $("card-name").textContent === d.name) await openCard(d.id);
      return true;
    } catch (e) { fail(e); return false; }
  }

  // ------------------------------------------------------------------ geometry

  function rowOfTop(rack, d) {
    const units = rack.rack_units || 42;
    return rackStyle(rack).topFirst ? d.rack_u - 1 : units - (d.rack_u + heightOf(d) - 1);
  }

  /** The unit a device would start on when it is dropped with its top edge on the row of unit [u]. */
  function startFor(rack, u, h) { return rackStyle(rack).topFirst ? u : u - h + 1; }

  function fits(rack, start, h, ignoreId) {
    const units = rack.rack_units || 42;
    if (start < 1 || start + h - 1 > units) return false;
    return !devices.some((x) => x.rack_id === rack.id && x.rack_u != null && x.id !== ignoreId
      && x.rack_u < start + h && start < x.rack_u + heightOf(x));
  }

  async function mount(rack, d, start) {
    const h = heightOf(d);
    if (!fits(rack, start, h, d.id)) { toast("Не помещается: нужные юниты заняты или стойка закончилась", true); return; }
    pick = null;
    await patchDevice(d, { rack_id: rack.id, rack_u: start, rack_h: h });
  }

  // ------------------------------------------------------------------ the pieces of a device's face

  const led = (d) => el("span", `led${passive(d) || (!d.is_online && !isAlarm(d)) ? " off" : d.is_online ? "" : " down"}`);
  const plate = (d) => el("span", "plate", d.name);

  function portCells(list, extra) {
    const wrap = el("span", `rports${extra ? ` ${extra}` : ""}`);
    const rows = list.length > 12 ? 2 : 1;
    wrap.style.setProperty("--cols", String(Math.ceil(list.length / rows)));
    wrap.style.setProperty("--rows", String(rows));
    return wrap;
  }

  function frontPorts(d) {
    const list = ports.get(d.id) || [];
    if (!list.length) return null;
    const wrap = portCells(list, d.type === "PATCH_PANEL" ? "pp" : "");
    for (const p of list) {
      const linked = d.type === "PATCH_PANEL" ? !!p.connected_device_name : p.is_admin_up && p.is_oper_up;
      const i = el("i", !linked ? (p.is_admin_up || d.type === "PATCH_PANEL" ? "" : "x") : p.speed_mbps >= 1000 || d.type === "PATCH_PANEL" ? "u" : "f");
      i.title = `${p.name}${p.label ? ` → ${p.label}` : ""}${p.connected_device_name ? ` · ${p.connected_device_name}` : ""}`;
      wrap.append(i);
    }
    return wrap;
  }

  function bays(h) {
    const rows = Math.min(h, 3), wrap = el("span", "bays");
    wrap.style.setProperty("--rows", String(rows));
    for (let i = 0; i < rows * 4; i++) wrap.append(el("i"));
    return wrap;
  }

  function frontBody(d) {
    if (PORT_KINDS.has(d.type)) return [frontPorts(d)].filter(Boolean);
    if (d.type === "SERVER") return [bays(heightOf(d))];
    if (d.type === "UPS") return [el("span", "lcd", "100%")];
    return [];
  }

  /** The back of a patch panel: one cell per port, filled when a label or a cable says where it goes. Each is clickable. */
  function rearPorts(d) {
    const list = ports.get(d.id) || [];
    const wrap = portCells(list, "rear");
    for (const p of list) {
      const b = el("button", `rp${p.label ? " named" : p.connected_device_name ? " linked" : ""}${selectedPort === p.id ? " on" : ""}`, String(p.number));
      b.type = "button";
      b.title = `${p.name}: ${p.label || "не подписан"}${p.connected_device_name ? ` · ${p.connected_device_name}` : ""}`;
      b.addEventListener("click", (ev) => {
        ev.stopPropagation();
        selected = d.id; selectedPort = p.id; render();
        openCard(d.id);
        // The labels sit in the card, which is built a moment after it opens.
        setTimeout(() => focusLabel(p.id), 1200);
      });
      wrap.append(b);
    }
    return wrap;
  }

  function rearBody(d) {
    if (d.type === "PATCH_PANEL") return [rearPorts(d)];
    if (d.type === "UPS") {
      const o = el("span", "outlets");
      for (let i = 0; i < 6; i++) o.append(el("i"));
      return [o];
    }
    if (d.type === "SERVER") {
      const psu = el("span", "psus");
      psu.append(el("i", "psu"), el("i", "psu"), el("i", "lan"), el("i", "lan"));
      return [psu];
    }
    const vents = el("span", "vents");
    for (let i = 0; i < 6; i++) vents.append(el("i"));
    return [vents, el("i", "inlet")];
  }

  function deviceEl(rack, d) {
    const h = heightOf(d), s = sideOf(d);
    const hidden = (side === "front" && s === "rear") || (side === "rear" && s === "front");
    const e = el("div", `dev${d.id === selected ? " sel" : ""}${hidden ? " ghost" : ""}${d.type === "UPS" ? " ups" : ""}`);
    e.style.top = `${6 + rowOfTop(rack, d) * U + 1}px`;
    e.style.height = `${h * U - 2}px`;
    e.draggable = true;
    e.addEventListener("dragstart", (ev) => { dragging = d; ev.dataTransfer.effectAllowed = "move"; ev.dataTransfer.setData("text/plain", d.id); });
    e.addEventListener("dragend", () => { dragging = null; clearGhosts(); });
    e.addEventListener("click", () => { selected = d.id; selectedPort = null; pick = null; render(); openCard(d.id); });
    if (hidden) {
      e.append(el("span", "meta", `${d.name} · ${s === "front" ? "установлено спереди" : "установлено сзади"}`));
    } else {
      e.append(led(d), plate(d));
      if (h > 1 || d.type !== "PATCH_PANEL") e.append(el("span", "meta", [d.model, d.vendor && !d.model ? d.vendor : null].filter(Boolean).join(" ")));
      e.append(...(side === "front" ? frontBody(d) : rearBody(d)));
    }
    e.title = `${d.name}${d.model ? ` · ${d.model}` : ""} · U${d.rack_u}${h > 1 ? `–${d.rack_u + h - 1}` : ""}${d.ip ? ` · ${d.ip}` : ""}`;
    return e;
  }

  // ------------------------------------------------------------------ drag and drop

  function clearGhosts() { for (const g of document.querySelectorAll(".rack-ghost")) g.remove(); }

  function showGhost(frame, rack, u, d) {
    clearGhosts();
    const h = heightOf(d), start = startFor(rack, u, h), ok = fits(rack, start, h, d.id);
    const units = rack.rack_units || 42;
    const top = rackStyle(rack).topFirst ? start - 1 : units - (start + h - 1);
    const g = el("div", `rack-ghost ${ok ? "ok" : "bad"}`, `${d.name} → U${start}${h > 1 ? `–${start + h - 1}` : ""}`);
    g.style.top = `${6 + Math.max(0, top) * U}px`;
    g.style.height = `${h * U - 2}px`;
    frame.append(g);
  }

  // ------------------------------------------------------------------ one rack

  function rackEl(rack) {
    const units = rack.rack_units || 42, st = rackStyle(rack);
    const members = devices.filter((d) => d.rack_id === rack.id && d.rack_u != null);
    const used = members.reduce((n, d) => n + heightOf(d), 0);
    const down = members.filter(isAlarm).length;
    const box = el("section", `rack${rack.id === focusRack ? " focus" : ""}`);
    box.dataset.id = rack.id;

    const head = el("div", "rack-head");
    const t = el("div", "t");
    const name = el("b", null, rack.name);
    name.title = "Открыть карточку стойки";
    name.addEventListener("click", () => openCard(rack.id));
    t.append(name, el("span", "mono muted", `${units} U`));
    const stats = el("div", "mono muted stats", `занято ${used} · свободно ${Math.max(0, units - used)}${down ? ` · не отвечает ${down}` : ""}`);
    const meter = el("div", "meter");
    const fill = el("i");
    fill.style.width = `${Math.min(100, Math.round((used / units) * 100))}%`;
    meter.append(fill);
    const settings = el("details", "rack-set");
    settings.append(el("summary", null, "Настроить"));
    const color = el("input"); color.type = "color"; color.value = st.color || "#2c3640";
    color.addEventListener("change", () => patchDevice(rack, { metadata: { ...(rack.metadata || {}), rack_color: color.value } }));
    const num = el("select");
    num.append(new Option("Юнит 1 внизу", "bottom"), new Option("Юнит 1 вверху", "top"));
    num.value = st.topFirst ? "top" : "bottom";
    num.addEventListener("change", () => patchDevice(rack, { metadata: { ...(rack.metadata || {}), rack_numbering: num.value } }));
    const reset = el("button", null, "Сбросить цвет"); reset.type = "button";
    reset.addEventListener("click", () => { const m = { ...(rack.metadata || {}) }; delete m.rack_color; patchDevice(rack, { metadata: m }); });
    settings.append(el("label", null, "Цвет рамки "), color, reset, num);
    head.append(t, stats, meter, settings);

    const frame = el("div", "frame");
    frame.style.height = `${units * U + 12}px`;
    if (st.color) frame.style.setProperty("--rc", st.color);
    const covered = new Set();
    for (const d of members) for (let u = d.rack_u; u < d.rack_u + heightOf(d); u++) covered.add(u);
    const order = Array.from({ length: units }, (_, i) => (st.topFirst ? i + 1 : units - i));
    for (const u of order) {
      const row = el("div", "rrow");
      const free = !covered.has(u);
      row.append(el("div", "no", String(u)), el("div", `slot${free ? " free" : ""}`, free ? "свободно" : ""), el("div", "no", String(u)));
      row.addEventListener("dragover", (ev) => { if (dragging) { ev.preventDefault(); showGhost(frame, rack, u, dragging); } });
      row.addEventListener("drop", (ev) => {
        ev.preventDefault();
        const d = dragging; dragging = null; clearGhosts();
        if (d) mount(rack, d, startFor(rack, u, heightOf(d))).catch(fail);
      });
      row.addEventListener("click", () => {
        if (pick && free) mount(rack, pick, startFor(rack, u, heightOf(pick))).catch(fail);
        else if (!pick && free) toast("Выберите устройство в списке слева, затем щёлкните свободное место — или перетащите его сюда");
      });
      frame.append(row);
    }
    for (const d of members) frame.append(deviceEl(rack, d));
    frame.addEventListener("dragleave", (ev) => { if (!frame.contains(ev.relatedTarget)) clearGhosts(); });
    box.append(head, frame);
    return box;
  }

  // ------------------------------------------------------------------ side lists and the panel on the right

  function renderPool() {
    const free = devices.filter((d) => !d.rack_id && !NOT_RACKABLE.has(d.type)).sort((a, b) => a.name.localeCompare(b.name));
    const list = $("racks-pool");
    list.replaceChildren(...(free.length ? free.map((d) => {
      const c = el("div", `chip${pick && pick.id === d.id ? " on" : ""}`);
      c.draggable = true;
      c.append(led(d), plate(d), el("small", null, `${heightOf(d)}U`));
      c.title = "Перетащите на свободное место в стойке или выберите и щёлкните по месту";
      c.addEventListener("dragstart", (ev) => { dragging = d; ev.dataTransfer.effectAllowed = "move"; ev.dataTransfer.setData("text/plain", d.id); });
      c.addEventListener("dragend", () => { dragging = null; clearGhosts(); });
      c.addEventListener("click", () => { pick = pick && pick.id === d.id ? null : d; selected = null; render(); });
      return c;
    }) : [el("p", "muted small", "Все устройства уже в стойках или не подходят для стойки.")]));
    $("racks-free-count").textContent = String(free.length);
  }

  function renderRackList() {
    const racks = devices.filter((d) => d.type === "RACK").sort((a, b) => a.name.localeCompare(b.name));
    $("racks-list").replaceChildren(...racks.map((r) => {
      const used = devices.filter((d) => d.rack_id === r.id && d.rack_u != null).reduce((n, d) => n + heightOf(d), 0);
      const c = el("div", "chip");
      c.append(el("span", null, r.name), el("small", null, `${used}/${r.rack_units || 42} U`));
      c.addEventListener("click", () => { focusRack = r.id; scrollToFocus(); });
      return c;
    }));
  }

  function scrollToFocus() {
    for (const r of document.querySelectorAll("#racks-row .rack")) r.classList.toggle("focus", r.dataset.id === focusRack);
    const t = document.querySelector("#racks-row .rack.focus");
    if (t) t.scrollIntoView({ behavior: "smooth", inline: "center", block: "nearest" });
  }

  function focusLabel(portId) {
    const input = document.querySelector(`#card-overview input[data-port="${portId}"]`);
    if (input) { input.scrollIntoView({ block: "nearest" }); input.focus(); }
  }

  /** The panel on the right only explains the view: a device that is clicked opens its card, where the rack's own controls sit on top. */
  function renderPanel() {
    const box = $("racks-panel");
    $("racks-layout").classList.toggle("has-card", !!selected);
    box.replaceChildren(el("h4", null, "Как пользоваться"), el("p", "note", pick
      ? `«${pick.name}»: щёлкните по свободному месту в стойке.`
      : "Щёлкните по устройству в стойке: справа откроется его карточка, а над ней — положение в стойке (сторона, высота, «Убрать из стойки»). Новое устройство — перетащите из списка слева на свободное место."),
    el("p", "note", "«Сзади» показывает заднюю сторону: у патч-панели — порт за портом, с подписью, куда идёт кабель; щелчок по порту открывает карточку патч-панели с подписями."));
  }

  /** What the rack adds to a device's card: where it stands, its side and height, taking it out, neighbours and, for a patch
   *  panel, the label of each port. Shown at the top of the card, so the rack's controls and the card are one step. */
  async function rackBlock(d) {
    devices = await api("GET", "devices");
    const cur = devices.find((x) => x.id === d.id);
    if (!cur || !cur.rack_id || cur.rack_u == null) return null;
    d = cur;
    const rack = devices.find((x) => x.id === d.rack_id);
    if (!rack) return null;
    if (d.type === "PATCH_PANEL") ports.set(d.id, (await api("GET", `devices/${d.id}/ports`).catch(() => [])).sort((a, b) => a.number - b.number));
    const h = heightOf(d);
    const card = el("div", "card rack-block");
    const head = el("h3", null, `В стойке ${rack.name} · U${d.rack_u}${h > 1 ? `–${d.rack_u + h - 1}` : ""} (${h} U)`);
    const kv = el("div", "kv");
    const row = (k, v) => { kv.append(el("span", null, k), v); };
    const sideSel = el("select");
    sideSel.append(new Option("Обе стороны", "both"), new Option("Только спереди", "front"), new Option("Только сзади", "rear"));
    sideSel.value = sideOf(d);
    sideSel.addEventListener("change", () => patchDevice(d, { metadata: { ...(d.metadata || {}), rack_side: sideSel.value } }));
    row("Установка", sideSel);
    const height = el("select");
    for (let i = 1; i <= 12; i++) height.append(new Option(`${i} U`, String(i)));
    height.value = String(h);
    height.addEventListener("change", () => {
      const nh = Number(height.value);
      if (!fits(rack, d.rack_u, nh, d.id)) { toast("Выше юниты заняты: сначала освободите место", true); height.value = String(h); return; }
      patchDevice(d, { rack_h: nh });
    });
    row("Высота", height);
    const above = devices.filter((x) => x.rack_id === rack.id && x.rack_u != null && x.rack_u >= d.rack_u + h).sort((a, b) => a.rack_u - b.rack_u)[0];
    const below = devices.filter((x) => x.rack_id === rack.id && x.rack_u != null && x.rack_u + heightOf(x) <= d.rack_u).sort((a, b) => b.rack_u - a.rack_u)[0];
    row("Выше", el("span", null, above ? `${above.name} (U${above.rack_u})` : "свободно до верха"));
    row("Ниже", el("span", null, below ? `${below.name} (U${below.rack_u})` : "свободно до низа"));
    const acts = el("div", "row wrap");
    const show = el("button", null, "Показать стойку"); show.type = "button";
    show.addEventListener("click", () => { $("card-dialog").close(); window.NetAdmin.openRacks(rack.id); });
    const remove = el("button", "danger", "Убрать из стойки"); remove.type = "button";
    remove.addEventListener("click", async () => { selected = null; await patchDevice(d, { rack_id: null }); });
    acts.append(show, remove);
    card.append(head, kv, acts);
    if (d.type === "PATCH_PANEL") card.append(labelTable(d));
    return card;
  }
  window.NetAdmin.rackBlock = rackBlock;

  /** Per-port labels of a patch panel: where each cable goes. Saved when the field loses focus or Enter is pressed. */
  function labelTable(d) {
    const list = ports.get(d.id) || [];
    const card = el("div", "card");
    card.append(el("h4", null, "Подписи портов — куда идёт кабель"));
    if (!list.length) {
      card.append(el("p", "note", "У патч-панели нет портов в учёте. Добавьте их в карточке: вкладка «Порты» → «Изменить порты…»."));
      return card;
    }
    const table = el("div", "label-table");
    for (const p of list) {
      const input = el("input"); input.value = p.label || ""; input.placeholder = "куда идёт"; input.maxLength = 120;
      input.dataset.port = p.id;
      if (selectedPort === p.id) input.classList.add("on");
      const save = async () => {
        const v = input.value.trim();
        if (v === (p.label || "")) return;
        try {
          const out = await api("PATCH", `ports/${p.id}`, { label: v });
          p.label = out.label;
          toast(v ? `Порт ${p.name}: «${v}»` : `Порт ${p.name}: подпись снята`);
          render();
        } catch (e) { fail(e); input.value = p.label || ""; }
      };
      input.addEventListener("change", save);
      input.addEventListener("keydown", (ev) => { if (ev.key === "Enter") { ev.preventDefault(); input.blur(); } });
      table.append(el("span", "pn", String(p.number)), input, el("span", "muted small cn", p.connected_device_name || ""));
    }
    card.append(table, el("p", "note", "Подпись видна на задней стороне стойки как заполненный порт; серым — порт с кабелем из «Схемы сети», но без подписи."));
    return card;
  }

  // ------------------------------------------------------------------ everything

  function render() {
    const racks = devices.filter((d) => d.type === "RACK").sort((a, b) => a.name.localeCompare(b.name));
    $("racks-row").replaceChildren(...racks.map(rackEl));
    $("racks-row").classList.toggle("rear", side === "rear");
    $("racks-empty").hidden = racks.length > 0;
    for (const b of document.querySelectorAll("#racks-side-seg button")) b.classList.toggle("active", b.dataset.side === side);
    const downCount = devices.filter((d) => d.rack_id && isAlarm(d)).length;
    $("racks-summary").textContent = `${racks.length} ${racks.length === 1 ? "стойка" : "стоек"}${downCount ? ` · не отвечает ${downCount}` : ""}`;
    renderPool();
    renderRackList();
    renderPanel();
    if (focusRack) { scrollToFocus(); focusRack = null; }
  }

  for (const b of document.querySelectorAll("#racks-side-seg button")) {
    b.addEventListener("click", () => { side = b.dataset.side; render(); });
  }
  $("racks-print").addEventListener("click", () => {
    document.body.classList.add("printing-racks");
    window.addEventListener("afterprint", () => document.body.classList.remove("printing-racks"), { once: true });
    window.print();
  });
  $("racks-add").addEventListener("click", async () => {
    const name = await askText("Название стойки", "", { placeholder: "Например, RACK-A1", action: "Далее" });
    if (!name || !name.trim()) return;
    const units = await askText("Высота стойки, юнитов", "42", { action: "Создать" });
    if (units == null) return;
    const n = Number.parseInt(units, 10);
    if (!(n >= 1 && n <= 60)) { toast("Высота стойки — от 1 до 60 юнитов", true); return; }
    try { const r = await api("POST", "devices", { name: name.trim(), type: "RACK", rack_units: n }); focusRack = r.id; await load(); } catch (e) { fail(e); }
  });
})();
