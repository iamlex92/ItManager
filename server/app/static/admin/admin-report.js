"use strict";
// «Отчёт»: the whole inventory as a readable page (opens in a tab, prints to PDF) or an Excel workbook. Sections and
// a preset are chosen first; full license keys are asked for separately and are written to the journal.
(() => {
  const { $, api, toast, fail, ask } = window.NetAdmin;

  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  let opts = null;

  async function open() {
    opts = opts || await api("GET", "report/options");
    const dlg = el("dialog", "report-dialog");
    const form = el("form", "dialog");
    form.method = "dialog";
    form.append(el("h2", null, "Отчёт по инфраструктуре"));
    form.append(el("p", "muted small", "Всё, что хранит программа, на эту минуту: счётчики, схема сети, VLAN, коммутаторы, стойки, серверы, лицензии. Открывается как страница, печатается в PDF; таблицы можно скачать в Excel."));

    const presetRow = el("div", "seg");
    const boxes = new Map();
    const options = el("div", "report-sections");
    for (const s of opts.sections) {
      const l = el("label", "report-sec");
      const c = el("input"); c.type = "checkbox"; c.checked = true;
      boxes.set(s.id, c);
      l.append(c, el("span", null, s.title), el("small", "muted", s.about));
      options.append(l);
    }
    const mask = el("input"); mask.type = "checkbox";
    const keys = el("input"); keys.type = "checkbox";
    const docs = el("input"); docs.type = "checkbox";
    const period = el("select");
    for (const [v, t] of [[7, "7 дней"], [30, "30 дней"], [90, "90 дней"], [365, "год"]]) period.append(new Option(t, String(v), false, v === 30));
    let preset = "full";
    const apply = (id) => {
      preset = id;
      const p = opts.presets.find((x) => x.id === id);
      for (const [sid, c] of boxes) c.checked = p.sections.includes(sid);
      mask.checked = p.mask;
      if (p.mask) keys.checked = false;
      for (const b of presetRow.children) b.classList.toggle("active", b.dataset.id === id);
    };
    for (const p of opts.presets) {
      const b = el("button", null, p.title); b.type = "button"; b.dataset.id = p.id;
      b.addEventListener("click", () => apply(p.id));
      presetRow.append(b);
    }
    const check = (input, text, hint) => {
      const l = el("label", "report-opt");
      l.append(input, el("span", null, text));
      if (hint) l.append(el("small", "muted", hint));
      return l;
    };
    const per = el("label", "report-opt"); per.append(el("span"), el("span", null, "События и журнал за период:"), period);
    form.append(el("h3", null, "Что включить"), presetRow, options, el("h3", null, "Параметры"),
      check(mask, "Для обмена вовне: сократить адреса, убрать MAC и учётные записи", "Адреса показываются как 10.10.•.•"),
      check(docs, "Полные тексты документов", "Иначе только список документов"),
      check(keys, "Полные ключи лицензий", "По умолчанию видны только последние 4 знака. Отчёт с ключами хранить как пароль; это пишется в журнал"),
      per);
    // Reports the server keeps: saved by the schedule or right now; each says what changed since the one before.
    const keptBox = el("div", "report-kept");
    const SCHEDULE = { off: "по расписанию не сохраняются", weekly: "сохраняются раз в неделю", monthly: "сохраняются раз в месяц" };
    const grab = async (name) => {
      const blob = await api("GET", `report/saved/${encodeURIComponent(name)}`);
      const a = el("a"); a.href = URL.createObjectURL(blob); a.download = name; a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 60000);
    };
    const drawKept = async () => {
      const k = await api("GET", "report/saved");
      const kids = [el("h3", null, "Сохранённые на сервере")];
      if (!k.folder) {
        kids.push(el("p", "muted small", "Папка для отчётов не задана: «Настройки сервера» → «Отчёт по расписанию» (или папка резервных копий)."));
      } else {
        kids.push(el("p", "muted small", `Отчёты ${SCHEDULE[k.schedule] || ""}. Папка на сервере: ${k.folder}. В каждом написано, что изменилось с предыдущего.`));
        const list = el("div", "report-kept-list");
        for (const it of k.items.slice(0, 12)) {
          const row = el("div", "row");
          row.append(el("span", "mono", new Date(it.at).toLocaleString("ru-RU", { dateStyle: "short", timeStyle: "short" })), el("div", "spacer"));
          for (const f of it.files) {
            const b = el("button", null, f.kind === "xlsx" ? "Excel" : "HTML"); b.type = "button";
            b.addEventListener("click", () => grab(f.name).catch(fail));
            row.append(b);
          }
          list.append(row);
        }
        if (!k.items.length) list.append(el("p", "muted small", "Пока ни одного."));
        const now = el("button", null, "Сохранить на сервере сейчас"); now.type = "button";
        now.addEventListener("click", async () => {
          now.disabled = true;
          try { await api("POST", "report/saved", {}); toast("Отчёт сохранён на сервере"); await drawKept(); } catch (e) { fail(e); now.disabled = false; }
        });
        kids.push(list, now);
      }
      keptBox.replaceChildren(...kids);
    };
    drawKept().catch(() => {});
    form.append(keptBox);
    const err = el("p", "error"); err.hidden = true;
    const row = el("div", "row");
    const mk = (text, cls, fn) => { const b = el("button", cls, text); b.type = "button"; b.addEventListener("click", () => run(fn)); return b; };
    const cancel = el("button", null, "Закрыть"); cancel.type = "button"; cancel.addEventListener("click", () => dlg.close());
    row.append(cancel, el("div", "spacer"), mk("Скачать Excel", null, "xlsx"), mk("Скачать HTML", null, "html"), mk("Открыть отчёт", "primary", "view"));
    form.append(err, row);

    async function run(kind) {
      err.hidden = true;
      const sections = [...boxes].filter(([, c]) => c.checked).map(([id]) => id);
      if (!sections.length) { err.textContent = "Отметьте хотя бы один раздел."; err.hidden = false; return; }
      if (keys.checked && !(await ask("В отчёт попадут полные ключи лицензий.\nДержите его так же осторожно, как пароли. Это действие запишется в журнал. Продолжить?", { danger: true }))) return;
      const body = { preset, sections, period_days: Number(period.value), mask: mask.checked, with_keys: keys.checked, confirm_keys: keys.checked, docs_full: docs.checked };
      const buttons = row.querySelectorAll("button");
      buttons.forEach((b) => { b.disabled = true; });
      // A tab opened at once, while the click still counts: the report is put into it when it is built.
      const tab = kind === "view" ? window.open("", "_blank") : null;
      try {
        if (kind === "view") {
          const r = await api("POST", "report/preview", body);
          if (tab) tab.location.href = r.url; else window.open(r.url, "_blank");
        } else {
          const blob = await api("POST", "report/download", { ...body, format: kind });
          const stamp = new Date().toISOString().slice(0, 16).replace("T", "_").replace(":", "-");
          const a = el("a"); a.href = URL.createObjectURL(blob); a.download = `Infrastructure_Report_${stamp}.${kind}`; a.click();
          setTimeout(() => URL.revokeObjectURL(a.href), 60000);
          toast("Отчёт скачан");
        }
      } catch (e) { if (tab) tab.close(); err.textContent = e.message || String(e); err.hidden = false; }
      buttons.forEach((b) => { b.disabled = false; });
    }

    apply("full");
    dlg.append(form);
    dlg.addEventListener("close", () => dlg.remove());
    document.body.append(dlg);
    dlg.showModal();
  }

  $("report-open").addEventListener("click", () => open().catch(fail));
  window.NetAdmin.openReport = () => open().catch(fail);
})();
