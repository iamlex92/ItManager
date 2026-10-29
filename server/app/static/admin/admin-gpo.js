"use strict";
// «Групповые политики»: every GPO with its state and links, where it applies, its registry settings, and the
// changes GPMC would make — state, links, settings, new and deleted GPOs — plus gpupdate on its computers.
// Every change asks «Да/Нет» and is in the audit log.
(() => {
  const { $, api, toast, fail, tabs } = window.NetAdmin;
  const el = (tag, props = {}, ...kids) => {
    const e = Object.assign(document.createElement(tag), props);
    e.append(...kids);
    return e;
  };
  const OPERATOR = () => window.NetAdmin.OPERATOR;
  const STATUS = {
    AllSettingsEnabled: "Включена",
    UserSettingsDisabled: "Отключены параметры пользователя",
    ComputerSettingsDisabled: "Отключены параметры компьютера",
    AllSettingsDisabled: "Отключена",
  };
  const TYPES = ["String", "ExpandString", "DWord", "QWord", "MultiString"];
  const active = (g) => g.status !== "AllSettingsDisabled" && g.links.some((l) => l.enabled);

  let list = [];
  let ous = [];
  let status = null;
  let picked = null;
  let applyTimer = 0;

  const write = (method, path, body) => api(method, path, { ...body, confirm: true, operator: OPERATOR() });

  async function load() {
    status = await api("GET", "gpo");
    list = status.gpos;
    $("gpo-mode").textContent = status.mode === "real"
      ? `${status.host} · ${status.writes_enabled ? "изменения разрешены" : "только просмотр"}`
      : "эмуляция · данные ненастоящие";
    $("gpo-mode").classList.toggle("warn", status.mode !== "real" || !status.writes_enabled);
    $("gpo-new").hidden = !status.can_create;
    ous = await api("GET", "gpo/ous").catch(() => []);
    renderList();
    if (picked) openGpo(picked).catch(fail);
  }
  tabs.gpo = load;

  function renderList() {
    const q = $("gpo-search").value.trim().toLowerCase();
    const f = $("gpo-filter").value;
    const rows = list.filter((g) => (!q || g.name.toLowerCase().includes(q))
      && (!f || (f === "active") === active(g)));
    $("gpo-rows").replaceChildren(...rows.map((g) => {
      const links = g.links.length ? `${g.links.filter((l) => l.enabled).length} из ${g.links.length}` : "нет";
      const tr = el("tr", { className: g.id === picked ? "picked" : "", onclick: () => openGpo(g.id).catch(fail) },
        el("td", {}, el("strong", { textContent: g.name }), g.description ? el("div", { className: "muted small", textContent: g.description }) : ""),
        el("td", {}, el("span", { className: `badge ${active(g) ? "ok" : ""}`, textContent: active(g) ? "Действует" : "Не действует" }),
          g.status !== "AllSettingsEnabled" ? el("div", { className: "muted small", textContent: STATUS[g.status] || g.status }) : ""),
        el("td", { textContent: links }));
      return tr;
    }));
    if (!rows.length) $("gpo-rows").replaceChildren(el("tr", {}, el("td", { colSpan: 3, className: "muted", textContent: "Ничего не найдено" })));
  }
  $("gpo-search").addEventListener("input", renderList);
  $("gpo-filter").addEventListener("change", renderList);

  // ---------------------------------------------------------------- one GPO

  async function openGpo(id) {
    picked = id;
    renderList();
    const box = $("gpo-detail");
    box.replaceChildren(el("p", { className: "muted", textContent: "Загрузка…" }));
    const d = await api("GET", `gpo/${id}`);
    if (picked !== id) return;
    render(d);
  }

  const canWrite = () => status && status.writes_enabled;

  async function change(question, method, path, body) {
    if (!canWrite()) { toast("Изменения выключены на сервере: «Настройки сервера» → «Разрешить изменения»", true); return; }
    if (question && !confirm(question)) return;
    try {
      const d = await write(method, path, body);
      toast("Изменено");
      if (d) render(d);
      const fresh = await api("GET", "gpo");
      list = fresh.gpos;
      renderList();
    } catch (e) { fail(e); }
  }

  function render(d) {
    const g = d.gpo;
    const box = $("gpo-detail");
    const sel = el("select", { disabled: !canWrite(), onchange: (ev) => change(`Сменить состояние «${g.name}» на «${STATUS[ev.target.value]}»?`,
      "PUT", `gpo/${g.id}/status`, { status: ev.target.value }) },
      ...Object.entries(STATUS).map(([v, t]) => new Option(t, v)));
    sel.value = g.status;
    box.replaceChildren(
      el("div", { className: "row wrap" },
        el("div", { className: "grow" }, el("h2", { textContent: g.name }),
          el("div", { className: "muted small", textContent: [g.description, g.modified ? `изменена ${new Date(g.modified).toLocaleString("ru-RU")}` : "", g.wmi_filter ? `WMI-фильтр: ${g.wmi_filter}` : ""].filter(Boolean).join(" · ") })),
        el("button", { type: "button", textContent: "Отчёт", onclick: () => report(g).catch(fail) }),
        status.can_create ? el("button", { type: "button", className: "danger", textContent: "Удалить", disabled: !canWrite(), onclick: () => remove(g) }) : ""),
      d.simulated ? el("p", { className: "muted small", textContent: "Эмуляция: настоящий домен не затрагивается. Включается в «Настройки сервера» → «Компьютеры домена»." }) : "",
      el("label", { className: "row" }, "Состояние ", sel),
      links(g), registry(g, d.registry), security(d.security_filter), computers(g));
  }

  function links(g) {
    const rows = g.links.map((l) => el("tr", {},
      el("td", { textContent: l.target_name, title: l.target }),
      el("td", {}, el("input", { type: "checkbox", checked: l.enabled, disabled: !canWrite(), title: "Связь включена",
        onchange: (ev) => change(`${ev.target.checked ? "Включить" : "Выключить"} связь с ${l.target_name}?`, "PATCH", `gpo/${g.id}/links`, { target: l.target, enabled: ev.target.checked }) })),
      el("td", {}, el("input", { type: "checkbox", checked: l.enforced, disabled: !canWrite(), title: "Принудительно: действует и там, где наследование заблокировано",
        onchange: (ev) => change(`${ev.target.checked ? "Сделать принудительной" : "Снять принудительность"} связь с ${l.target_name}?`, "PATCH", `gpo/${g.id}/links`, { target: l.target, enforced: ev.target.checked }) })),
      el("td", {}, el("button", { type: "button", textContent: "Убрать", disabled: !canWrite(),
        onclick: () => change(`Убрать связь «${g.name}» с ${l.target_name}? Политика перестанет действовать там.`, "POST", `gpo/${g.id}/links/remove`, { target: l.target }) }))));
    const target = el("select", {}, ...ous.filter((o) => !g.links.some((l) => l.target.toLowerCase() === o.dn.toLowerCase())).map((o) => new Option(o.name, o.dn)));
    const enforced = el("input", { type: "checkbox" });
    return el("div", { className: "gpo-section" },
      el("h3", { textContent: "Где действует (связи)" }),
      g.links.length ? el("div", { className: "table-wrap" }, el("table", { className: "grid" },
        el("thead", {}, el("tr", {}, ...["Подразделение", "Включена", "Принудительно", ""].map((h) => el("th", { textContent: h })))),
        el("tbody", {}, ...rows))) : el("p", { className: "muted", textContent: "Политика ни с чем не связана и не действует." }),
      canWrite() && target.options.length ? el("div", { className: "row wrap" }, target,
        el("label", { className: "check" }, enforced, " принудительно"),
        el("button", { type: "button", textContent: "Связать", onclick: () => change(`Связать «${g.name}» с ${target.selectedOptions[0].textContent}?`,
          "POST", `gpo/${g.id}/links`, { target: target.value, enforced: enforced.checked }) })) : "");
  }

  function registry(g, items) {
    const key = el("input", { placeholder: "HKLM\\Software\\Policies\\…" });
    const name = el("input", { placeholder: "Параметр" });
    const type = el("select", {}, ...TYPES.map((t) => new Option(t, t)));
    const value = el("input", { placeholder: "Значение" });
    const fill = (r) => { key.value = r.key; name.value = r.name; type.value = r.type; value.value = r.value ?? ""; value.focus(); };
    const rows = items.map((r) => el("tr", {},
      el("td", { className: "mono small", textContent: r.key }), el("td", { textContent: r.name }), el("td", { textContent: r.type }),
      el("td", { className: "mono", textContent: r.state === "Delete" ? "(удаляется)" : r.value ?? "" }),
      el("td", {}, el("div", { className: "row" },
        el("button", { type: "button", textContent: "Изменить", disabled: !canWrite(), onclick: () => fill(r) }),
        el("button", { type: "button", textContent: "Удалить", disabled: !canWrite(),
          onclick: () => change(`Удалить параметр ${r.name} из «${g.name}»?`, "POST", `gpo/${g.id}/registry/remove`, { key: r.key, name: r.name }) })))));
    return el("div", { className: "gpo-section" },
      el("h3", { textContent: "Параметры (административные шаблоны, реестр)" }),
      items.length ? el("div", { className: "table-wrap" }, el("table", { className: "grid" },
        el("thead", {}, el("tr", {}, ...["Раздел", "Параметр", "Тип", "Значение", ""].map((h) => el("th", { textContent: h })))),
        el("tbody", {}, ...rows))) : el("p", { className: "muted", textContent: "Параметров реестра нет (могут быть другие: сценарии, безопасность — см. «Отчёт»)." }),
      canWrite() ? el("form", { className: "gpo-form", onsubmit: (ev) => {
        ev.preventDefault();
        change(`Записать ${name.value} = ${value.value} в «${g.name}»?`, "PUT", `gpo/${g.id}/registry`,
          { key: key.value, name: name.value, type: type.value, value: value.value });
      } }, el("label", {}, "Раздел", key), el("label", {}, "Параметр", name), el("label", {}, "Тип", type), el("label", {}, "Значение", value),
      el("button", { type: "submit", className: "primary", textContent: "Записать" })) : "");
  }

  function security(items) {
    return el("div", { className: "gpo-section" },
      el("h3", { textContent: "Фильтр безопасности (к кому применяется)" }),
      el("p", { textContent: items.length ? items.map((i) => i.name).join(", ") : "Никому: политика не применяется ни к кому" }));
  }

  function computers(g) {
    const out = el("div");
    const sec = el("div", { className: "gpo-section" },
      el("div", { className: "row wrap" }, el("h3", { className: "grow", textContent: "Компьютеры, на которые действует" }),
        el("button", { type: "button", textContent: "Показать", onclick: () => loadScope(g, out).catch(fail) })),
      out);
    return sec;
  }

  async function loadScope(g, out) {
    out.replaceChildren(el("p", { className: "muted", textContent: "Ищу компьютеры в подразделениях политики…" }));
    const s = await api("GET", `gpo/${g.id}/computers`);
    const boxes = s.computers.map((c) => ({ c, box: el("input", { type: "checkbox", checked: c.enabled !== false }) }));
    const progress = el("p", { className: "muted" });
    out.replaceChildren(
      el("p", { className: "muted small", textContent: `${s.computers.length} компьютеров${s.filtered ? " (с учётом фильтра безопасности)" : ""}. По связям, наследованию и фильтру; WMI-фильтры не учитываются. Что ПК применил на самом деле — «Политики на ПК» в «Компьютеры домена».` }),
      s.computers.length ? el("div", { className: "table-wrap" }, el("table", { className: "grid" },
        el("thead", {}, el("tr", {}, ...["", "Компьютер", "Подразделение"].map((h) => el("th", { textContent: h })))),
        el("tbody", {}, ...boxes.map(({ c, box }) => el("tr", {}, el("td", {}, box), el("td", { textContent: c.name }),
          el("td", { className: "muted small", textContent: c.ou || "" })))))) : "",
      s.computers.length && canWrite() ? el("button", { type: "button", className: "primary", textContent: "Применить сейчас (gpupdate /force)",
        onclick: async () => {
          const chosen = boxes.filter((b) => b.box.checked).map((b) => b.c.name);
          if (!chosen.length || !confirm(`Обновить групповые политики на ${chosen.length} компьютерах сейчас?`)) return;
          try {
            const job = await write("POST", `gpo/${g.id}/apply`, { computers: chosen.length === boxes.length ? null : chosen });
            pollApply(job.id, progress);
          } catch (e) { fail(e); }
        } }) : "",
      progress);
  }

  async function pollApply(id, progress) {
    clearTimeout(applyTimer);
    const job = await api("GET", `gpo/apply/${id}`);
    progress.textContent = job.status === "running" ? `Применяется: ${job.done} из ${job.total}…`
      : `Готово: успешно ${job.rows.filter((r) => r.ok).length} из ${job.total}.${job.simulated ? " (эмуляция)" : ""}`;
    if (job.status === "running") { applyTimer = setTimeout(() => pollApply(id, progress).catch(fail), 1500); return; }
    const failed = job.rows.filter((r) => !r.ok);
    if (failed.length) progress.append(el("div", { className: "error", textContent: failed.map((r) => `${r.host}: ${r.error}`).join("; ") }));
  }

  async function report(g) {
    const blob = await api("GET", `gpo/${g.id}/report`);
    const url = URL.createObjectURL(blob);
    $("gpo-report-title").textContent = `Отчёт: ${g.name}`;
    $("gpo-report-frame").src = url;
    $("gpo-report-dialog").showModal();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  }
  $("gpo-report-close").addEventListener("click", () => $("gpo-report-dialog").close());

  function remove(g) {
    const typed = prompt(`Удалить политику «${g.name}» из домена? Это нельзя отменить (кроме резервной копии GPO). Введите имя политики, чтобы подтвердить:`);
    if (typed === null) return;
    if (typed.trim() !== g.name) { toast("Имя не совпало — политика не удалена", true); return; }
    write("POST", `gpo/${g.id}/delete`, {}).then(async () => {
      toast("Политика удалена");
      picked = null;
      $("gpo-detail").replaceChildren(el("p", { className: "muted", textContent: "Политика удалена." }));
      await load();
    }).catch(fail);
  }

  $("gpo-new").addEventListener("click", async () => {
    if (!canWrite()) { toast("Изменения выключены на сервере", true); return; }
    const name = prompt("Имя новой групповой политики:");
    if (!name || !name.trim()) return;
    try {
      const d = await write("POST", "gpo", { name: name.trim() });
      toast("Политика создана. Свяжите её с подразделением, чтобы она начала действовать.");
      picked = d.gpo.id;
      await load();
    } catch (e) { fail(e); }
  });

  // On a domain computer's page: what it really applied.
  window.NetAdmin.showAppliedGpo = async (host, target) => {
    target.replaceChildren(el("p", { className: "muted", textContent: `Спрашиваю ${host} (gpresult)…` }));
    try {
      const r = await api("GET", `gpo/computer/${encodeURIComponent(host)}`);
      target.replaceChildren(
        el("p", { className: "muted small", textContent: `Применено ${r.read_at || ""}${r.simulated ? " · эмуляция" : ""}` }),
        el("div", { className: "table-wrap" }, el("table", { className: "grid" },
          el("thead", {}, el("tr", {}, ...["Политика", "Применена", "Откуда"].map((h) => el("th", { textContent: h })))),
          el("tbody", {}, ...r.gpos.map((g) => el("tr", {}, el("td", { textContent: g.name }),
            el("td", { textContent: g.applied ? "да" : g.denied ? "нет: нет доступа" : g.filtered ? "нет: отфильтрована" : "нет" }),
            el("td", { className: "muted small", textContent: g.link || "" })))))));
    } catch (e) { target.replaceChildren(el("p", { className: "error-box", textContent: e.message })); }
  };
})();
