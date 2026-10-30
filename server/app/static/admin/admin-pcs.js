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
  let platform = "windows";
  const ASK = {
    windows: {
      cleanup: "Удалить временные файлы (C:\\Windows\\Temp, Temp пользователей) и кэш загрузок обновлений Windows",
      reboot: "Перезагрузить компьютер сейчас. Несохранённая работа пользователя пропадёт",
    },
    astra: {
      cleanup: "Очистить кэш пакетов, журналы старше 14 дней и файлы /tmp старше недели",
      reboot: "Перезагрузить компьютер через минуту (пользователь увидит предупреждение)",
      update: "Установить обновления пакетов (astra-update) в фоне. Перезагрузка — отдельно",
    },
  };
  const CONFIRMED = new Set(["cleanup", "reboot", "update", "admin_add", "admin_remove"]);

  function setPlatform(p) {
    platform = p || "windows";
    document.querySelectorAll("#pc-actions [data-only]").forEach((b) => { b.hidden = b.dataset.only !== platform; });
    $("pc-console-title").textContent = platform === "astra" ? "Консоль bash (от root)" : "Консоль PowerShell";
    $("pc-command").placeholder = platform === "astra" ? "ip -br addr" : "Get-NetIPConfiguration";
  }

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
    setPlatform("windows");
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
      if (r.platform && r.platform !== platform) setPlatform(r.platform);
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
    if (action === "info" && r.platform === "astra") {
      $("pc-user").textContent = d.user || "никто не вошёл";
      out.replaceChildren(sim, astraInfo(d));
    } else if (action === "assist") {
      out.replaceChildren(sim, d.method === "msra" ? msraHelp(d) : vncHelp(d));
    } else if (action === "admin_add" || action === "admin_remove") {
      toast(d.output || "Готово");
      run("info");
    } else if (action === "info") {
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
      out.replaceChildren(sim, d.length ? table([platform === "astra" ? "Папка" : "Папка на C:\\", "Размер, ГБ"], d.map((f) => [f.name, String(f.size_gb)]))
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
      const prompt = platform === "astra" ? `root@${host}#` : `PS ${host}>`;
      $("pc-console-out").textContent = `${r.simulated ? "[эмуляция]\n" : ""}${prompt} ${$("pc-command").dataset.last}\n${d.output || ""}`;
    } else {
      out.replaceChildren(sim, el("pre", { className: "output", textContent: d.output || JSON.stringify(d, null, 2) }));
      if (action === "reboot") { $("pc-user").textContent = ""; }
    }
  }

  function astraInfo(d) {
    const up = d.uptime_minutes != null ? `${Math.floor(d.uptime_minutes / 1440)} дн. ${Math.floor((d.uptime_minutes % 1440) / 60)} ч ${d.uptime_minutes % 60} мин` : null;
    const who = el("input", { placeholder: "ECOTEHS\\ivanova", autocomplete: "off" });
    return el("div", { className: "info" },
      section("Система", kv([["Компьютер", d.computer], ["ОС", d.os], ["Ядро", `${d.build || "—"} (${d.architecture || "?"})`],
        ["Аптайм", up], ["Пользователь", d.user ? `${d.user} (${d.display})` : "никто не вошёл"], ["Процессор", d.cpu],
        ["ОЗУ", d.ram_gb != null ? `${d.ram_gb} ГБ` : null]])),
      section("Администраторы (sudo)",
        d.admins.length ? table(["Пользователь", ""], d.admins.map((u) => [u,
          el("button", { type: "button", className: "danger", textContent: "Снять", onclick: () => admins("admin_remove", u) })]))
          : el("p", { className: "muted", textContent: "Через консоль никому не выдавалось; группа astra-admin пуста" }),
        el("form", { className: "row", onsubmit: (ev) => { ev.preventDefault(); if (who.value.trim()) admins("admin_add", who.value.trim()); } },
          who, el("button", { type: "submit", textContent: "Выдать sudo" })),
        el("p", { className: "muted small", textContent: "Пишется в /etc/sudoers.d/netadmin-admins и проверяется visudo. Группа astra-admin не меняется. При включённом МКЦ администратору может понадобиться и уровень целостности (pdpl-user -i 63)." })),
      section("Готовность", kv([["Удалённая помощь", d.x11vnc ? "x11vnc установлен" : "нет x11vnc: sudo apt install x11vnc"],
        ["Групповые политики", d.samba_gpupdate ? "samba-gpupdate есть" : "нет samba-gpupdate (python3-samba)"]])));
  }

  function admins(action, user) {
    const q = action === "admin_add" ? `Выдать sudo пользователю ${user} на ${host}?` : `Снять sudo с ${user} на ${host}?`;
    if (!confirm(q)) return;
    run(action, user, true);
  }

  function copyRow(text) {
    return el("div", { className: "row" }, el("code", { className: "mono grow", textContent: text }),
      el("button", { type: "button", textContent: "Копировать", onclick: () => navigator.clipboard.writeText(text).then(() => toast("Скопировано"), () => toast("Не удалось скопировать", true)) }));
  }

  function msraHelp(d) {
    const cmd = new Blob([`@echo off\r\nstart "" msra.exe /offerra ${d.host}\r\n`], { type: "application/octet-stream" });
    return section("Удалённая помощь Windows",
      el("p", { textContent: "Запустите на своём компьютере (Windows, вы — в списке помощников политики):" }),
      copyRow(d.command),
      el("div", { className: "row" }, el("a", { className: "button", href: URL.createObjectURL(cmd), download: `Помощь ${d.host}.cmd`, textContent: "Скачать ярлык (.cmd)" })),
      el("p", { className: "muted small", textContent: "Пользователь увидит запрос и разрешит подключение. Если msra отвечает, что предлагать помощь нельзя, нужны шаблоны групповой политики:" }),
      el("ul", {}, ...d.needs.map((n) => el("li", { className: "small", textContent: n }))));
  }

  function vncHelp(d) {
    return section("Удалённая помощь Astra Linux",
      el("p", { textContent: `На ${d.host} запущен VNC для экрана ${d.user} (${d.display}). Подключитесь в течение ${d.expires_minutes} мин — пользователь увидит окно и должен нажать «Yes».` }),
      kv([["Адрес", copyRow(d.address)], ["Пароль (одноразовый)", copyRow(d.password)], ["Режим", d.view_only ? "только просмотр" : "управление"]]),
      el("div", { className: "row" },
        el("a", { className: "button primary", href: d.url, textContent: "Открыть в VNC-клиенте" }),
        d.view_only ? "" : el("button", { type: "button", textContent: "Заново, только просмотр", onclick: () => run("assist", "view") })),
      el("p", { className: "muted small", textContent: "Любой VNC-клиент: TigerVNC, RealVNC, Remmina. Соединение одно; после отключения x11vnc закрывается. Трафик VNC не шифруется — только внутри сети. Пароль администратора в окне повышения прав вы вводите сами: на Astra оно открывается на том же экране." }));
  }

  function kill(name) {
    if (!confirm(`Завершить все процессы ${name} на ${host}? Несохранённые данные в них пропадут.`)) return;
    run("kill", name, true);
  }

  $("pc-actions").addEventListener("click", (ev) => {
    const b = ev.target.closest("button[data-pc]");
    if (!b || !host) return;
    const action = b.dataset.pc;
    const ask = ASK[platform][action];
    if (ask && !confirm(`${ask} на ${host}?`)) return;
    run(action, null, CONFIRMED.has(action));
  });
  // What the computer really applied (gpresult), drawn by admin-gpo.js.
  $("pc-gpresult").addEventListener("click", () => { if (host) window.NetAdmin.showAppliedGpo(host, $("pc-result")); });
  $("pc-open-form").addEventListener("submit", (ev) => { ev.preventDefault(); openPc($("pc-host").value).catch(fail); });
  $("pc-console").addEventListener("submit", (ev) => {
    ev.preventDefault();
    const cmd = $("pc-command").value.trim();
    if (!cmd || !host) return;
    if (!confirm(`Выполнить на ${host} ${platform === "astra" ? "от root" : "от имени администратора домена"}:\n\n${cmd}`)) return;
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

  // From the device card: the computer's page with remote help started.
  window.NetAdmin.assistPc = (name) => {
    window.NetAdmin.openTab("pcs");
    $("pc-host").value = name;
    openPc(name).then(() => run("assist")).catch(fail);
  };

  // From the device card: the computer's page, opened at once.
  window.NetAdmin.openPc = (name) => {
    window.NetAdmin.openTab("pcs");
    $("pc-host").value = name;
    openPc(name).catch(fail);
  };
})();
