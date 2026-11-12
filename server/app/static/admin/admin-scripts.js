"use strict";
// The «Скрипты» tab: custom scripts that show up in the app, with parameters, a dry run and a real run.
// Split out of admin.js; builds on it through window.NetAdmin.
(() => {
  const { $, api, fail, OPERATOR, tabs, ask, askText } = window.NetAdmin;

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
    $("script-runs-on").value = s ? s.runs_on || "emulator" : "emulator";
    scriptWhere();
    $("script-critical").checked = s ? s.critical : false;
    $("script-body").value = s ? s.body : "";
    $("params").replaceChildren(...(s ? s.params : []).map(paramRow));
    $("script-delete").hidden = !s;
    $("script-test").hidden = !s;
    $("script-run").hidden = !s || (s.runs_on || "emulator") === "emulator";
    $("script-output").hidden = true;
    $("script-msg").textContent = "";
    renderScriptList();
  }

  // What the chosen language and place mean, under the form.
  const WHERE = {
    emulator: () => "Только показ: при запуске виден текст с подставленными параметрами, ничего не выполняется.",
    pc: (sh) => ({
      powershell: "PowerShell — на Windows по WinRM от учётной записи домена (до 8000 символов; время — «Тайм-аут команды WinRM» в настройках сервера).",
      cmd: "cmd — на Windows по WinRM (до 4000 символов). Значения параметров — без & | < > ^ % и кавычек.",
      python: "Python — на Windows (нужен py или python в PATH) или на Astra Linux (python3 от root).",
      bash: "bash — на Astra Linux по SSH от root (sudo).",
    })[sh],
    server: (sh) => (sh === "python" || sh === "bash"
      ? "На сервере NetAdmin от пользователя службы, до 5 минут. Python — тот же, что у сервера."
      : "На сервере NetAdmin запускаются только Python и bash."),
  };
  function scriptWhere() {
    const runsOn = $("script-runs-on").value;
    $("script-where").textContent = WHERE[runsOn]($("script-shell").value) + (runsOn === "pc" ? " Компьютер спрашивается при каждом запуске." : "");
  }
  $("script-runs-on").addEventListener("change", scriptWhere);
  $("script-shell").addEventListener("change", scriptWhere);

  async function askParams(s) {
    const params = {};
    const list = (s.runs_on === "pc" ? [{ name: "host", label: "Компьютер", required: true, placeholder: "PC-BUH-01" }] : []).concat(s.params);
    for (const p of list) {
      const v = await askText(`${p.label}${p.required ? "" : " (необязательно)"}`, p.placeholder || "", { action: "Далее" });
      if (v === null) return null;
      if (v.trim()) params[p.name] = v.trim();
    }
    return params;
  }

  function showRun(r) {
    const out = $("script-output");
    out.hidden = false;
    out.textContent = `${r.simulated ? "" : `Код выхода: ${r.exit_code}\n`}${r.stdout}${r.stderr ? `\n\n${r.stderr}` : ""}`;
  }

  $("script-run").addEventListener("click", async () => {
    if (!editing) return;
    const params = await askParams(editing);
    if (!params) return;
    const where = editing.runs_on === "pc" ? `на ${params.host}` : "на сервере NetAdmin";
    if (!await ask(`Запустить «${editing.title}» ${where}? Скрипт выполнится по-настоящему.`)) return;
    $("script-msg").textContent = "Выполняется…";
    try {
      showRun(await api("POST", `runbooks/${editing.runbook_id}/run`, { params, confirm: true, operator: OPERATOR }));
      $("script-msg").textContent = "";
    } catch (e) { $("script-msg").textContent = ""; fail(e); }
  });

  $("script-new").addEventListener("click", () => editScript(null));
  $("param-add").addEventListener("click", () => $("params").appendChild(paramRow({})));

  $("script-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const body = {
      title: $("script-title").value.trim(),
      description: $("script-description").value.trim(),
      shell: $("script-shell").value,
      runs_on: $("script-runs-on").value,
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
    if (!editing || !await ask(`Удалить скрипт «${editing.title}»?`)) return;
    try { await api("DELETE", `scripts/${editing.id}`); editing = null; $("script-form").hidden = true; await loadScripts(); }
    catch (e) { fail(e); }
  });

  $("script-test").addEventListener("click", async () => {
    if (!editing) return;
    const params = await askParams(editing);
    if (!params) return;
    try {
      showRun(await api("POST", `runbooks/${editing.runbook_id}/run`, { params, confirm: true, dry_run: true, operator: OPERATOR }));
    } catch (e) { fail(e); }
  });

  tabs.scripts = loadScripts;
})();
