"use strict";
// Domain computers: what Connect-DomainPC.ps1 did from an admin's PC, done by the server over WinRM.
// Builds on admin.js through window.NetAdmin. Changes ask «Да/Нет» first and are all in the audit log.
(() => {
  const { $, api, toast, fail, tabs } = window.NetAdmin;
  const el = (tag, props = {}, ...kids) => {
    const e = Object.assign(document.createElement(tag), props);
    e.append(...kids);
    return e;
  };
  const table = (head, rows) => el("div", { className: "table-wrap" }, el("table", { className: "grid" },
    el("thead", {}, el("tr", {}, ...head.map((h) => el("th", { textContent: h })))),
    el("tbody", {}, ...rows.map((r) => el("tr", {}, ...r.map((c) => el("td", {}, c ?? "—")))))));
  const kv = (rows) => el("div", { className: "kv" }, ...rows.flatMap(([k, v]) => [el("span", { textContent: k }), el("span", {}, v ?? "—")]));
  const section = (title, ...body) => el("div", { className: "card" }, el("h3", { textContent: title }), ...body);

  let host = null;
  let status = null;
  const ASK = {
    cleanup: "Удалить временные файлы (C:\\Windows\\Temp, Temp пользователей) и кэш загрузок обновлений Windows",
    reboot: "Перезагрузить компьютер сейчас. Несохранённая работа пользователя пропадёт",
    gpupdate: null,
  };

  async function loadStatus() {
    status = await api("GET", "pcs/status");
    const mode = status.mode === "real" ? "WinRM" : "эмуляция";
    const writes = status.writes_enabled ? "изменения разрешены" : "только просмотр";
    $("pc-mode").textContent = `${mode} · ${writes}${status.mode === "real" && !status.login_configured ? " · нет учётной записи" : ""}`;
    $("pc-mode").classList.toggle("warn", status.mode !== "real" || !status.login_configured);
    $("keys-start").disabled = false;
  }
  tabs.pcs = loadStatus;

  // ---------------------------------------------------------------- one computer

  async function openPc(name) {
    host = name.trim();
    if (!host) return;
    $("pc-title").textContent = host;
    $("pc-user").textContent = "";
    $("pc-actions").hidden = false;
    $("pc-console").hidden = false;
    $("pc-console-out").hidden = true;
    $("keys-result").hidden = true;
    $("pc-result").replaceChildren();
    await run("info");
  }

  async function run(action, arg, confirmed = false) {
    const target = host;
    const busy = el("p", { className: "muted", textContent: `Выполняется на ${target}…` });
    if (action !== "command") $("pc-result").replaceChildren(busy);
    try {
      const r = await api("POST", `pcs/${encodeURIComponent(target)}/${action}`, {
        arg: arg ?? null, confirm: confirmed, operator: window.NetAdmin.OPERATOR,
      });
      if (target !== host) return;
      if (!r.ok) {
        $("pc-result").replaceChildren(el("p", { className: "error-box", textContent: r.error || "Компьютер не ответил" }));
        return;
      }
      render(action, r);
    } catch (e) {
      if (e.code === "confirmation_required") return; // asked below
      $("pc-result").replaceChildren(el("p", { className: "error-box", textContent: e.message }));
    }
  }

  function render(action, r) {
    const d = r.data;
    const sim = r.simulated ? el("p", { className: "muted small", textContent: "Эмуляция: данные ненастоящие. Включается в «Ещё → Настройки сервера» → «Компьютеры домена»." }) : "";
    const out = $("pc-result");
    if (action === "info") {
      $("pc-user").textContent = d.user || "нет активного сеанса";
      const up = d.uptime_minutes != null ? `${Math.floor(d.uptime_minutes / 1440)} дн. ${Math.floor((d.uptime_minutes % 1440) / 60)} ч ${d.uptime_minutes % 60} мин` : null;
      out.replaceChildren(sim, el("div", { className: "info" },
        section("Система", kv([["Компьютер", d.computer], ["ОС", `${d.os || "—"} (${d.architecture || "?"})`], ["Сборка", d.build],
          ["Аптайм", up], ["Пользователь", d.user || "нет активного сеанса"], ["Процессор", d.cpu], ["ОЗУ", d.ram_gb != null ? `${d.ram_gb} ГБ` : null]])),
        section("Лицензия Windows", kv([["Ключ BIOS", d.windows_bios_key || "отсутствует (не вшит в BIOS)"],
          ["Ключ ОС", d.windows_registry_key || "не найден в реестре"]])),
        section("MS Office", el("div", {}, !d.office_installed ? "Не установлен"
          : d.office_keys.length ? d.office_keys.map((k) => `*****-${k}`).join(", ") : "Ключи не найдены (возможно, Office 365)")),
        section("Лицензии 1С:Предприятие", d.one_c.length
          ? table(["Файл", "Дата"], d.one_c.map((f) => [f.path || f.name, f.date]))
          : el("span", { className: "muted", textContent: "Программные лицензии (.lic) не найдены: USB-ключ HASP или сервер лицензий" }))));
    } else if (action === "disks") {
      out.replaceChildren(sim, table(["Диск", "Метка", "Свободно, ГБ", "Всего, ГБ", "Занято"],
        d.map((v) => [v.drive, v.label || "", String(v.free_gb), String(v.total_gb),
          v.total_gb ? `${Math.round((1 - v.free_gb / v.total_gb) * 100)} %` : ""])));
    } else if (action === "folders") {
      out.replaceChildren(sim, d.length ? table(["Папка на C:\\", "Размер, ГБ"], d.map((f) => [f.name, String(f.size_gb)]))
        : el("p", { className: "muted", textContent: "Папок больше 0,1 ГБ нет" }));
    } else if (action === "processes") {
      out.replaceChildren(sim, table(["PID", "Процесс", "Память, МБ", ""], d.map((p) => [String(p.id), p.name, String(p.ram_mb),
        el("button", { type: "button", className: "danger", textContent: "Завершить", onclick: () => kill(p.name) })])));
    } else if (action === "kill") {
      toast(d.stopped ? `Завершено процессов ${d.name}: ${d.stopped}` : `Процесс ${d.name} не найден`);
      run("processes");
    } else if (action === "cleanup") {
      out.replaceChildren(sim, el("p", { textContent: `Очистка завершена: освобождено ${d.freed_mb} МБ, свободно на C: ${d.free_gb} ГБ.` }));
    } else if (action === "command") {
      $("pc-console-out").hidden = false;
      $("pc-console-out").textContent = `${r.simulated ? "[эмуляция]\n" : ""}PS ${host}> ${$("pc-command").dataset.last}\n${d.output || ""}`;
    } else {
      out.replaceChildren(sim, el("pre", { className: "output", textContent: d.output || JSON.stringify(d, null, 2) }));
      if (action === "reboot") { $("pc-user").textContent = ""; }
    }
  }

  function kill(name) {
    if (!confirm(`Завершить все процессы ${name} на ${host}? Несохранённые данные в них пропадут.`)) return;
    run("kill", name, true);
  }

  $("pc-actions").addEventListener("click", (ev) => {
    const b = ev.target.closest("button[data-pc]");
    if (!b || !host) return;
    const action = b.dataset.pc;
    if (action in ASK && ASK[action] && !confirm(`${ASK[action]} на ${host}?`)) return;
    run(action, null, action === "cleanup" || action === "reboot");
  });
  $("pc-open-form").addEventListener("submit", (ev) => { ev.preventDefault(); openPc($("pc-host").value).catch(fail); });
  $("pc-console").addEventListener("submit", (ev) => {
    ev.preventDefault();
    const cmd = $("pc-command").value.trim();
    if (!cmd || !host) return;
    if (!confirm(`Выполнить на ${host} от имени администратора домена:\n\n${cmd}`)) return;
    $("pc-command").dataset.last = cmd;
    run("command", cmd, true);
  });

  // ---------------------------------------------------------------- subnet sweep

  $("pc-sweep-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const prefix = $("pc-prefix").value.trim();
    if (!prefix) return;
    const list = $("pc-sweep-list");
    $("pc-sweep-note").textContent = `Сканирование ${prefix}… это займёт до минуты.`;
    list.replaceChildren();
    try {
      const hits = await api("POST", "pcs/sweep", { prefix });
      $("pc-sweep-note").textContent = hits.length ? `Откликнулись: ${hits.length}. Щёлкните, чтобы открыть.` : "Активных узлов не найдено.";
      list.replaceChildren(...hits.map((h) => el("li", {
        onclick: () => { $("pc-host").value = h.name || h.ip; openPc(h.name || h.ip).catch(fail); },
      }, el("strong", { textContent: h.ip }), "  ", h.name || "DNS не ответил", h.device_name ? el("span", { className: "muted small", textContent: ` · в учёте: ${h.device_name}` }) : "")));
    } catch (e) { $("pc-sweep-note").textContent = e.message; }
  });

  // ---------------------------------------------------------------- key inventory

  $("keys-source").addEventListener("change", () => { $("keys-prefix-label").hidden = $("keys-source").value !== "subnet"; });
  let keysTimer = 0;
  $("keys-start").addEventListener("click", async () => {
    const source = $("keys-source").value;
    const body = { source, prefix: source === "subnet" ? $("keys-prefix").value.trim() : null, operator: window.NetAdmin.OPERATOR };
    try {
      $("keys-start").disabled = true;
      const job = await api("POST", "pcs/keys", body);
      pollKeys(job.id);
    } catch (e) { $("keys-start").disabled = false; $("keys-status").textContent = e.message; }
  });

  async function pollKeys(id) {
    clearTimeout(keysTimer);
    const job = await api("GET", `pcs/keys/${id}`);
    $("keys-status").textContent = job.status === "running"
      ? `${job.stage}: ${job.done} из ${job.online || job.total}…`
      : job.status === "failed" ? `Ошибка: ${job.error}`
        : `Готово: компьютеров ${job.total}, включено ${job.online}, собрано ${job.rows.filter((r) => r.ok).length}.${job.simulated ? " (эмуляция)" : ""}`;
    if (job.status === "running") { keysTimer = setTimeout(() => pollKeys(id).catch(fail), 1500); return; }
    $("keys-start").disabled = false;
    showKeys(job);
  }

  function showKeys(job) {
    const box = $("keys-result");
    box.hidden = false;
    $("pc-title").textContent = "Инвентаризация ключей";
    $("pc-user").textContent = "";
    $("pc-actions").hidden = true;
    $("pc-console").hidden = true;
    $("pc-result").replaceChildren();
    host = null;
    box.replaceChildren(
      el("div", { className: "row" },
        el("span", { className: "muted", textContent: `Опрошено ПК: ${job.rows.filter((r) => r.ok).length}` }),
        el("div", { className: "spacer" }),
        el("button", { className: "primary", textContent: "Скачать CSV для Excel", onclick: () => downloadCsv(job).catch(fail) })),
      table(["Имя ПК", "Пользователь", "ОС", "Windows (BIOS)", "Windows (реестр)", "MS Office", "Лицензии 1С"],
        job.rows.map((r) => r.ok
          ? [r.computer || r.host, r.user || "нет сеанса", r.os, r.windows_bios_key || "Нет", r.windows_registry_key || "Нет", r.office, r.one_c]
          : [r.host, el("span", { className: "error", textContent: r.error }), "", "", "", "", ""])));
  }

  async function downloadCsv(job) {
    const blob = await api("GET", `pcs/keys/${job.id}/csv`);
    const url = URL.createObjectURL(blob);
    const stamp = new Date(job.started_at).toISOString().slice(0, 16).replace("T", "_").replace(":", "-");
    el("a", { href: url, download: `Keys_Inventory_${stamp}.csv` }).click();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  }

  // From the device card: the computer's page, opened at once.
  window.NetAdmin.openPc = (name) => {
    window.NetAdmin.openTab("pcs");
    $("pc-host").value = name;
    openPc(name).catch(fail);
  };
})();
