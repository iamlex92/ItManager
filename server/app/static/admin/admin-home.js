"use strict";
// The start screen: what needs attention right now. Down devices and open events come first, then what was done lately.
// Everything on it opens something: a device card, a section.
(() => {
  const { $, api, tabs, openTab, openCard, typeOf } = window.NetAdmin;
  let timer = 0;

  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  const ago = (iso) => {
    if (!iso) return "ответа не было";
    const min = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
    if (min < 1) return "только что";
    if (min < 60) return `${min} мин назад`;
    if (min < 1440) return `${Math.round(min / 60)} ч назад`;
    return `${Math.round(min / 1440)} дн назад`;
  };
  const LED = { CRITICAL: "down", WARNING: "warn", INFO: "up" };

  function tile(value, label, tone, go) {
    const b = el("button", `tile ${tone || ""}`);
    b.type = "button";
    b.append(el("span", "tile-n", String(value)), el("span", "tile-l", label));
    b.addEventListener("click", () => openTab(go));
    return b;
  }

  function row(led, title, sub, when, onClick) {
    const li = el("li", onClick ? "feed-row clickable" : "feed-row");
    li.append(Object.assign(el("i", `led ${led}`), {}), el("span", "feed-t", title));
    if (sub) li.append(el("span", "muted small feed-s", sub));
    if (when) li.append(el("span", "muted small feed-w", when));
    if (onClick) {
      li.tabIndex = 0;
      li.addEventListener("click", onClick);
      li.addEventListener("keydown", (ev) => { if (ev.key === "Enter") onClick(); });
    }
    return li;
  }

  function fill(list, rows, empty) {
    list.replaceChildren(...(rows.length ? rows : [el("li", "feed-empty muted", empty)]));
  }

  async function load() {
    const [s, soon, tasks] = await Promise.all([api("GET", "summary"), api("GET", "lifecycle/expirations?within_days=60").catch(() => []), api("GET", "tasks").catch(() => [])]);
    const { devices: d, alerts: a } = s;
    const downCount = d.total - d.online;
    $("home-tiles").replaceChildren(
      tile(`${d.online} из ${d.total}`, d.quiet_offline ? `устройств в сети · ещё ${d.quiet_offline} выключено (не авария)` : "устройств в сети", downCount ? "" : "ok", "devices"),
      tile(downCount, "не отвечают", downCount ? "bad" : "ok", "devices"),
      tile(a.open, a.critical ? `открытых событий, из них ${a.critical} критичных` : "открытых событий", a.critical ? "bad" : a.open ? "warn" : "ok", "alerts"),
    );
    fill($("home-down"), d.down.map((x) => row("down", x.name, [typeOf({ type: x.type }).label, x.ip].filter(Boolean).join(" · "), ago(x.last_seen_at), () => openCard(x.id))),
      "Все устройства отвечают");
    fill($("home-alerts"), a.items.map((x) => row(LED[x.severity] || "warn", x.device, x.message, ago(x.created_at), () => openCard(x.device_id))),
      "Открытых событий нет");
    // Tasks that are late, due today or due this week; the rest wait in their own section.
    const due = tasks.filter((t) => t.days_left != null && t.days_left <= 7).slice(0, 8);
    fill($("home-tasks"), due.map((t) => row(t.days_left < 0 ? "down" : t.days_left === 0 ? "warn" : "up", t.title,
      t.days_left < 0 ? `просрочено на ${-t.days_left} дн.` : t.days_left === 0 ? "сегодня" : `через ${t.days_left} дн.`,
      new Date(`${t.due_date}T00:00:00`).toLocaleDateString("ru-RU"), () => openTab("tasks"))), "Срочных задач нет");
    if (window.NetAdmin.refreshTasksBadge) window.NetAdmin.refreshTasksBadge();
    const LV = { expired: "down", week: "down", month: "warn", soon: "warn", ok: "up" };
    fill($("home-expiring"), soon.slice(0, 8).map((x) => row(LV[x.level] || "warn", x.title, x.days_left < 0 ? `просрочено на ${-x.days_left} дн.` : x.days_left === 0 ? "сегодня" : `через ${x.days_left} дн.`,
      new Date(`${x.due}T00:00:00`).toLocaleDateString("ru-RU"), () => (x.device_id ? openCard(x.device_id) : openTab("lifecycle")))), "В ближайшие 60 дней ничего не истекает");
    const urgent = soon.filter((x) => x.level === "expired" || x.level === "week").length;
    $("lc-count").textContent = String(urgent);
    $("lc-count").hidden = !urgent;
    fill($("home-recent"), s.recent.map((x) => row(x.exit_code ? "down" : "up", x.title, x.by, ago(x.at), () => openTab("audit"))),
      "Действий пока не было");
    $("home-updated").textContent = `обновлено ${new Date().toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" })}`;
  }

  tabs.home = () => {
    clearInterval(timer);
    timer = setInterval(() => { if ($("tab-home").hidden) clearInterval(timer); else load().catch(() => {}); }, 30_000);
    return load();
  };
  for (const b of document.querySelectorAll("#tab-home [data-go]")) b.addEventListener("click", () => openTab(b.dataset.go));
})();
