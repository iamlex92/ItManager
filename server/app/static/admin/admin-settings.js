"use strict";
// «Настройки сервера»: everything that used to be edited in netadmin.env, as a form. The server describes the
// settings itself (GET server-settings), so this page only draws them. Saved values act after a restart.
(() => {
  const { $, api, toast, fail, tabs, ask, askText } = window.NetAdmin;
  const el = (tag, props = {}, ...kids) => {
    const e = Object.assign(document.createElement(tag), props);
    e.append(...kids);
    return e;
  };

  const SOURCE = { ui: "задано здесь", file: "из netadmin.env", default: "по умолчанию" };
  let edits = {};
  let resets = new Set();
  let credentials = [];

  const showError = (e) => { $("settings-error").hidden = !e; $("settings-error").textContent = e ? e.message : ""; };
  const dirty = () => {
    const n = Object.keys(edits).length + resets.size;
    $("settings-save").disabled = !n;
    $("settings-dirty").hidden = !n;
    $("settings-dirty").textContent = n ? `Не сохранено: ${n}` : "";
  };

  async function load() {
    showError(null);
    const [s, creds] = await Promise.all([api("GET", "server-settings"), api("GET", "credentials").catch(() => [])]);
    credentials = creds;
    drawJobs();
    edits = {};
    resets = new Set();
    dirty();
    render(s);
  }
  tabs.settings = load;

  const JOBS = { ping: "Проверка устройств (ping)", traffic: "Трафик портов", backup: "Копия базы",
    sync: "Отправка на резервный сервер", retention: "Очистка журнала" };
  async function drawJobs() {
    try {
      const st = await api("GET", "status");
      $("settings-jobs-card").hidden = false;
      $("settings-jobs").replaceChildren(...st.jobs.map((j) => el("p", { className: j.error ? "error small" : "small",
        textContent: `${JOBS[j.name] || j.name}: ` + (!j.enabled ? "не работает" : j.error ? `ошибка — ${j.error}`
          : j.last_at ? `${when(j.last_at)}` : "ещё ничего не сделала") })));
    } catch { $("settings-jobs-card").hidden = true; }
  }

  function render(s) {
    $("settings-pending").hidden = !s.restart_pending;
    $("settings-restart").disabled = !s.can_restart;
    $("settings-restart").title = s.can_restart ? "Перезапустить службу, чтобы сохранённое начало действовать"
      : "Сервер запущен не как служба: перезапустите его вручную";
    $("settings-groups").replaceChildren(...s.groups.map((g) => el("div", { className: "card" },
      el("h3", { textContent: g.title }),
      g.note ? el("p", { className: "muted small", textContent: g.note }) : "",
      ...g.fields.map(row),
      g.title === "Резервный сервер" ? syncBox() : "")));
    $("settings-toc").replaceChildren(...[...$("settings-groups").children].map((card) => {
      const a = el("button", { type: "button", className: "toc-item", textContent: card.querySelector("h3").textContent });
      a.addEventListener("click", () => card.scrollIntoView({ behavior: "smooth", block: "start" }));
      return a;
    }));
    applySearch();
  }

  // The search hides the groups and rows that do not match; a group whose title matches stays whole.
  function applySearch() {
    const q = $("settings-search").value.trim().toLowerCase();
    for (const card of $("settings-groups").children) {
      const title = card.querySelector("h3").textContent.toLowerCase();
      let any = !q || title.includes(q);
      for (const r of card.querySelectorAll(".setting")) {
        const hit = !q || title.includes(q) || r.textContent.toLowerCase().includes(q);
        r.hidden = !hit;
        any ||= hit;
      }
      card.hidden = !any;
    }
  }
  $("settings-search").addEventListener("input", applySearch);
  // Leaving with unsaved values asks first.
  window.addEventListener("beforeunload", (ev) => {
    if (Object.keys(edits).length || resets.size) { ev.preventDefault(); ev.returnValue = ""; }
  });

  // What the last sync did, and a way to run one now; filled in after the page is drawn.
  const when = (iso) => (iso ? new Date(iso).toLocaleString("ru-RU") : "ещё не было");
  function syncBox() {
    const box = el("div", { className: "sync-box" }, el("p", { className: "muted small", textContent: "Синхронизация: загрузка…" }));
    const draw = (st) => {
      const lines = [];
      if (st.role === "backup") {
        lines.push(`Этот сервер резервный: последняя база с основного — ${when(st.received_at)}` +
          (st.received_created_at ? ` (снята ${when(st.received_created_at)})` : ""));
        if (st.receive_error) lines.push(`Последняя попытка не удалась: ${st.receive_error}`);
      } else {
        lines.push(st.role === "main" ? `Отправка на резервный сервер каждые ${st.interval_minutes} мин.` : "Отправка по расписанию выключена.");
        lines.push(`Последняя отправка: ${when(st.pushed_at)}` +
          (st.pushed_at ? ` · ${st.push_rows} строк, ${Math.max(1, Math.round(st.push_bytes / 1024))} КБ` : ""));
        if (st.push_error) lines.push(`Ошибка: ${st.push_error}`);
      }
      const extra = [];
      if (st.role === "backup") {
        const promote = el("button", { type: "button", className: "danger", textContent: "Сделать этот сервер основным",
          title: "Основной сервер потерян: этот перестаёт принимать базу и разрешает изменения" });
        promote.addEventListener("click", async () => {
          if (!await ask("Этот сервер перестанет принимать базу с основного и разрешит изменения. Продолжить?")) return;
          try { await api("POST", "sync/promote"); toast("Сервер стал основным. Перезапустите его, чтобы заработали опрос и ping"); load(); }
          catch (e) { fail(e); }
        });
        const reset = el("button", { type: "button", textContent: "Принимать от другого сервера",
          title: "Если основной сервер заменён: этот забудет прежний источник и примет пакет от нового" });
        reset.addEventListener("click", async () => {
          if (!await ask("Забыть прежний основной сервер? Следующий пакет от любого сервера будет принят.")) return;
          try { draw(await api("POST", "sync/reset-source")); toast("Источник забыт"); } catch (e) { fail(e); }
        });
        extra.push(promote, reset);
        if (st.source_id) lines.push(`Источник: ${st.source_id.slice(0, 8)}…`);
      }
      const now = el("button", { type: "button", textContent: "Синхронизировать сейчас" });
      now.hidden = st.role === "backup" || !st.backup_server_url;
      now.title = "Отправить базу на резервный сервер сейчас; его данные заменятся этими";
      now.addEventListener("click", async () => {
        now.disabled = true;
        now.textContent = "Отправляю…";
        try { draw(await api("POST", "sync/push")); toast("База отправлена на резервный сервер"); }
        catch (e) { fail(e); api("GET", "sync").then(draw).catch(() => {}); }
      });
      const check = el("button", { type: "button", textContent: "Проверить связь",
        title: "Дойдёт ли база до резервного сервера: связь, токен, приём, ключ шифрования. Ничего не отправляет" });
      check.hidden = st.role === "backup" || !st.backup_server_url;
      const verdict = el("div", { className: "sync-check" });
      check.addEventListener("click", async () => {
        check.disabled = true;
        try {
          const r = await api("POST", "sync/check");
          verdict.replaceChildren(...r.checks.map((c) => el("p", { className: c.ok ? "small" : "error small", textContent: `${c.ok ? "✓" : "✗"} ${c.name}: ${c.text}` })));
        } catch (e) { fail(e); }
        check.disabled = false;
      });
      extra.unshift(check);
      box.replaceChildren(...lines.map((t) => el("p", { className: t.startsWith("Ошибка") || t.startsWith("Последняя попытка") ? "error small" : "small", textContent: t })), now, ...extra, verdict);
    };
    api("GET", "sync").then(draw).catch((e) => box.replaceChildren(el("p", { className: "error small", textContent: e.message })));
    return box;
  }

  function row(f) {
    const line = el("div", { className: "setting" });
    const mark = () => line.classList.toggle("changed", f.key in edits || resets.has(f.key));
    const set = (value) => { edits[f.key] = value; resets.delete(f.key); mark(); dirty(); };
    const label = el("label", { htmlFor: `set-${f.key}` }, f.label,
      el("span", { className: "tag", textContent: SOURCE[f.source] || "" }),
      f.restart_required ? el("span", { className: "tag", textContent: "· после перезапуска" }) : "");
    line.append(label, input(f, set));
    const help = el("div", { className: "help muted small", textContent: f.help || "" });
    if (f.source === "ui") {
      help.append(" ", el("a", { href: "#", textContent: "вернуть из netadmin.env", onclick: (ev) => {
        ev.preventDefault(); delete edits[f.key]; resets.add(f.key); mark(); dirty();
      } }));
    }
    if (help.textContent) line.append(help);
    return line;
  }

  function input(f, set) {
    const id = `set-${f.key}`;
    if (f.kind === "bool") {
      return el("input", { id, type: "checkbox", checked: !!f.value, onchange: (ev) => set(ev.target.checked) });
    }
    if (f.kind === "choice" || f.kind === "credential") {
      const options = f.kind === "choice" ? f.choices
        : [{ value: "", label: "Не выбрана" },
          ...credentials.filter((c) => c.kind === f.credential_kind).map((c) => ({ value: c.name, label: c.username ? `${c.name} (${c.username})` : c.name }))];
      if (f.kind === "credential" && f.value && !options.some((o) => o.value === f.value)) {
        options.push({ value: f.value, label: `${f.value} — нет в «Учётных данных»` });
      }
      const sel = el("select", { id, onchange: (ev) => set(ev.target.value) }, ...options.map((o) => new Option(o.label, o.value)));
      sel.value = f.value ?? "";
      return sel;
    }
    if (f.kind === "secret") {
      return el("input", { id, type: "password", autocomplete: "new-password",
        placeholder: f.is_set ? "задано — пусто, чтобы не менять" : "не задано",
        oninput: (ev) => { if (ev.target.value) set(ev.target.value); else { delete edits[f.key]; dirty(); } } });
    }
    if (f.kind === "pem") {
      const area = el("textarea", { id, rows: 4, className: "mono small", value: f.value ?? "",
        placeholder: "-----BEGIN CERTIFICATE-----", oninput: (ev) => set(ev.target.value) });
      // Read the certificate the controller presents, show who it is, and take it only if the fingerprint fits.
      const fetchBtn = el("button", { type: "button", textContent: "Получить с контроллера", onclick: async () => {
        try {
          const url = typeof edits.ad_ldap_url === "string" ? edits.ad_ldap_url : "";
          const c = await api("POST", "server-settings/controller-certificate", { url });
          const ok = await ask(`Сертификат контроллера:\n\nКому: ${c.subject}\nКем выдан: ${c.issuer}${c.self_signed ? " (самоподписанный)" : ""}\n`
            + `Действует до: ${new Date(c.not_after).toLocaleDateString("ru-RU")}\n\nОтпечаток SHA-256:\n${c.sha256}\n\n`
            + "Сверьте отпечаток на контроллере:\nopenssl x509 -in /var/lib/samba/private/tls/cert.pem -noout -fingerprint -sha256\n\nДоверять этому сертификату?");
          if (!ok) return;
          area.value = c.pem;
          set(c.pem);
          toast("Сертификат вставлен — нажмите «Сохранить»");
        } catch (e) { fail(e); }
      } });
      return el("div", { className: "stack" }, area, fetchBtn);
    }
    const number = f.kind === "int" || f.kind === "float";
    return el("input", { id, type: number ? "number" : "text", step: f.kind === "float" ? "any" : "1",
      value: f.value ?? "", oninput: (ev) => set(number && ev.target.value !== "" ? Number(ev.target.value) : ev.target.value) });
  }

  $("settings-save").addEventListener("click", async () => {
    showError(null);
    try {
      const s = await api("PUT", "server-settings", { values: edits, reset: [...resets], operator: window.NetAdmin.OPERATOR });
      edits = {};
      resets = new Set();
      dirty();
      render(s);
      toast(s.restart_pending ? "Сохранено. Изменения начнут действовать после перезапуска сервера." : "Сохранено");
    } catch (e) { showError(e); }
  });

  $("settings-restart").addEventListener("click", async () => {
    if (!await ask("Перезапустить сервер? Он будет недоступен 10–30 секунд, приложение и консоль переподключатся сами.")) return;
    try {
      await api("POST", "server-settings/restart");
      toast("Сервер перезапускается…");
      // Back when it answers again.
      for (let i = 0; i < 60; i++) {
        await new Promise((r) => setTimeout(r, 2000));
        const up = await fetch("/api/v1/health").then((r) => r.ok).catch(() => false);
        if (up && i > 0) { toast("Сервер снова работает"); await load(); window.NetAdmin.showMode?.(); return; }
      }
      fail(new Error("Сервер не ответил за 2 минуты: проверьте службу NetAdmin"));
    } catch (e) { showError(e); }
  });
})();
