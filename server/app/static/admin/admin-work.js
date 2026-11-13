"use strict";
// «Задачи» (once or repeating, with a date and an optional device) and «Документы» (the plant's own instructions,
// passports and notes, written in a small subset of Markdown). Both can point at a device and show up in its card.
(() => {
  const { $, api, toast, fail, ask, openCard, openTab, tabs, formDialog, field, input, select, el } = window.NetAdmin;

  const fmtDate = (iso) => (iso ? new Date(`${iso}T00:00:00`).toLocaleDateString("ru-RU") : "");
  const REPEAT = { none: "не повторять", daily: "каждый день", weekly: "каждую неделю", monthly: "каждый месяц", yearly: "каждый год" };
  const UNIT = { daily: ["день", "дня", "дней"], weekly: ["неделю", "недели", "недель"], monthly: ["месяц", "месяца", "месяцев"], yearly: ["год", "года", "лет"] };
  const CAT = { instruction: "инструкция", passport: "паспорт", scheme: "схема", note: "заметка" };
  const button = (text, onClick, cls) => { const b = el("button", cls, text); b.type = "button"; b.addEventListener("click", onClick); return b; };

  function repeatText(t) {
    if (t.repeat === "none") return "";
    if (t.repeat_every === 1) return REPEAT[t.repeat];
    const u = UNIT[t.repeat], n = t.repeat_every, m = n % 10, h = n % 100;
    const word = h >= 11 && h <= 14 ? u[2] : m === 1 ? u[0] : m >= 2 && m <= 4 ? u[1] : u[2];
    return `каждые ${n} ${word}`;
  }
  const whenText = (t) => {
    if (t.days_left == null) return "";
    const d = fmtDate(t.due_date);
    return t.days_left < 0 ? `${d} · просрочено на ${-t.days_left} дн.` : t.days_left === 0 ? `${d} · сегодня` : `${d} · через ${t.days_left} дн.`;
  };

  // ------------------------------------------------------------------ tasks

  let devices = [];
  async function loadDevices() { if (!devices.length) devices = await api("GET", "devices"); return devices; }

  async function loadTasks() {
    const tasks = await api("GET", `tasks${$("task-show-done").checked ? "?include_done=true" : ""}`);
    const groups = [["Просрочено", (t) => !t.is_done && t.days_left != null && t.days_left < 0, "bad"], ["Сегодня", (t) => !t.is_done && t.days_left === 0],
      ["На этой неделе", (t) => !t.is_done && t.days_left != null && t.days_left >= 1 && t.days_left <= 7], ["Позже", (t) => !t.is_done && t.days_left != null && t.days_left > 7],
      ["Без срока", (t) => !t.is_done && t.days_left == null], ["Выполнено", (t) => t.is_done]];
    const box = el("div");
    for (const [title, test, cls] of groups) {
      const items = tasks.filter(test);
      if (!items.length) continue;
      const sec = el("section", "task-group");
      sec.append(el("h3", cls || "", `${title} · ${items.length}`));
      for (const t of items) sec.append(taskRow(t));
      box.append(sec);
    }
    if (!tasks.length) box.append(el("p", "muted", "Задач нет. Впишите дело сверху: «проверить батарею ИБП», раз в квартал, например."));
    $("tasks-list").replaceChildren(box);
    refreshBadge();
  }

  function taskRow(t) {
    const row = el("div", `task-row${t.is_done ? " done" : ""}`);
    const cb = Object.assign(el("input"), { type: "checkbox", checked: t.is_done, title: t.is_done ? "Вернуть в работу" : "Выполнено" });
    cb.addEventListener("change", async () => {
      try {
        if (t.is_done) await api("POST", `tasks/${t.id}/reopen`);
        else { const out = await api("POST", `tasks/${t.id}/complete`); if (out.next) toast(`Следующая: ${fmtDate(out.next.due_date)}`); }
        await loadTasks();
      } catch (e) { fail(e); cb.checked = t.is_done; }
    });
    const main = el("div", "task-main");
    const title = el("span", "task-title", t.title);
    main.append(title);
    const meta = [whenText(t), repeatText(t)].filter(Boolean).join(" · ");
    if (meta) main.append(el("small", t.days_left != null && t.days_left < 0 && !t.is_done ? "bad block" : "muted block", meta));
    if (t.notes) main.append(el("small", "muted block", t.notes));
    row.append(cb, main);
    if (t.device_id) { const link = button(t.device_name || "устройство", () => openCard(t.device_id), "link"); row.append(link); }
    row.append(button("Изменить", () => editTask(t), "ghost small"), button("×", async () => {
      if (!(await ask(`Удалить задачу «${t.title}»?`, { action: "Удалить", danger: true }))) return;
      try { await api("DELETE", `tasks/${t.id}`); await loadTasks(); } catch (e) { fail(e); }
    }, "ghost small"));
    return row;
  }

  async function editTask(t) {
    await loadDevices();
    return formDialog("Задача", (form) => {
      const f = {};
      f.title = field(form, "Что сделать", input("text", t.title, { required: true, maxLength: 200 }));
      const row = el("div", "row"); form.append(row);
      f.due = input("date", t.due_date || "");
      f.repeat = select(Object.entries(REPEAT), t.repeat);
      f.every = input("number", t.repeat_every, { min: 1, max: 60 });
      const dl = el("label"); dl.append("Срок", f.due);
      const rl = el("label", "grow"); rl.append("Повторять", f.repeat);
      const el2 = el("label"); el2.append("Каждые (раз)", f.every);
      row.append(dl, rl, el2);
      f.device = select([["", "— без устройства —"], ...devices.filter((d) => d.type !== "SOCKET").sort((a, b) => a.name.localeCompare(b.name)).map((d) => [d.id, d.name])], t.device_id || "");
      field(form, "Устройство", f.device);
      f.notes = field(form, "Заметка", el("textarea")); f.notes.rows = 3; f.notes.value = t.notes || "";
      return f;
    }, async (f) => {
      await api("PATCH", `tasks/${t.id}`, { title: f.title.value.trim(), due_date: f.due.value || null, repeat: f.repeat.value, repeat_every: Number(f.every.value) || 1, device_id: f.device.value || null, notes: f.notes.value.trim() || null });
      await loadTasks();
    });
  }

  $("task-add").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    try {
      await api("POST", "tasks", { title: $("task-title").value.trim(), due_date: $("task-date").value || null, repeat: $("task-repeat").value });
      $("task-title").value = ""; $("task-date").value = ""; $("task-repeat").value = "none";
      await loadTasks();
    } catch (e) { fail(e); }
  });
  $("task-show-done").addEventListener("change", () => loadTasks().catch(fail));

  async function refreshBadge() {
    try {
      const s = await api("GET", "tasks/summary");
      const n = s.overdue + s.today;
      $("tasks-count").textContent = String(n);
      $("tasks-count").hidden = !n;
    } catch { /* the badge simply stays as it was */ }
  }
  window.NetAdmin.refreshTasksBadge = refreshBadge;
  tabs.tasks = loadTasks;

  // ------------------------------------------------------------------ documents: a small, safe Markdown

  /** Headings, lists, **bold**, `code`, [links](https://…), fenced code and paragraphs. Everything goes in as text. */
  function inline(text) {
    const frag = document.createDocumentFragment();
    const re = /(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\((?:https?:\/\/|mailto:)[^)\s]+\))/g;
    let last = 0, m;
    while ((m = re.exec(text))) {
      if (m.index > last) frag.append(text.slice(last, m.index));
      const tok = m[0];
      if (tok.startsWith("**")) frag.append(el("strong", null, tok.slice(2, -2)));
      else if (tok.startsWith("`")) frag.append(el("code", null, tok.slice(1, -1)));
      else {
        const [, label, url] = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(tok);
        const a = el("a", null, label); a.href = url; a.target = "_blank"; a.rel = "noopener noreferrer";
        frag.append(a);
      }
      last = m.index + tok.length;
    }
    if (last < text.length) frag.append(text.slice(last));
    return frag;
  }
  function renderMd(src) {
    const root = el("div", "md");
    const lines = String(src || "").replace(/\r/g, "").split("\n");
    let para = [], list = null, code = null;
    const flushPara = () => { if (para.length) { const p = el("p"); p.append(inline(para.join(" "))); root.append(p); para = []; } };
    const flushList = () => { list = null; };
    for (const line of lines) {
      if (code) { if (line.startsWith("```")) { root.append(el("pre", null, code.join("\n"))); code = null; } else code.push(line); continue; }
      if (line.startsWith("```")) { flushPara(); flushList(); code = []; continue; }
      const h = /^(#{1,3})\s+(.*)$/.exec(line);
      if (h) { flushPara(); flushList(); const e = el(`h${h[1].length + 1}`); e.append(inline(h[2])); root.append(e); continue; }
      const b = /^\s*[-*]\s+(.*)$/.exec(line), n = /^\s*\d+[.)]\s+(.*)$/.exec(line);
      if (b || n) {
        flushPara();
        const tag = b ? "ul" : "ol";
        if (!list || list.tagName.toLowerCase() !== tag) { list = el(tag); root.append(list); }
        const li = el("li"); li.append(inline((b || n)[1])); list.append(li);
        continue;
      }
      if (!line.trim()) { flushPara(); flushList(); continue; }
      flushList();
      para.push(line.trim());
    }
    if (code) root.append(el("pre", null, code.join("\n")));
    flushPara();
    return root;
  }
  window.NetAdmin.renderMarkdown = renderMd;

  // ------------------------------------------------------------------ documents

  let docs = [], current = null, editing = false;

  async function loadDocs(keep) {
    const q = $("doc-search").value.trim(), cat = $("doc-cat").value;
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (cat) params.set("category", cat);
    docs = await api("GET", `documents${params.toString() ? `?${params}` : ""}`);
    const ul = $("doc-list");
    ul.replaceChildren(...docs.map((d) => {
      const li = el("li", current && current.id === d.id ? "on" : "");
      li.append(el("b", null, `${d.pinned ? "★ " : ""}${d.title}`), el("small", "muted", `${CAT[d.category] || d.category}${d.device_name ? ` · ${d.device_name}` : ""}`), el("small", "muted excerpt", d.excerpt));
      li.addEventListener("click", () => openDoc(d.id));
      return li;
    }));
    if (!docs.length) ul.append(el("li", "muted", q || cat ? "Ничего не найдено." : "Документов пока нет. «+ Документ»: инструкция, паспорт серверной, схема."));
    if (!keep && !current) $("doc-view").replaceChildren(el("p", "muted", "Выберите документ слева или создайте новый."));
  }

  async function openDoc(id) {
    current = await api("GET", `documents/${id}`);
    editing = false;
    drawDoc();
    for (const li of $("doc-list").children) li.classList.remove("on");
    const i = docs.findIndex((d) => d.id === id);
    if (i >= 0) $("doc-list").children[i].classList.add("on");
  }

  function drawDoc() {
    const d = current, box = $("doc-view");
    if (!d) { box.replaceChildren(el("p", "muted", "Выберите документ слева или создайте новый.")); return; }
    const head = el("div", "doc-head");
    head.append(el("h2", null, d.title));
    const bar = el("div", "row wrap");
    bar.append(el("span", "tag", CAT[d.category] || d.category));
    if (d.device_id) bar.append(button(d.device_name || "устройство", () => openCard(d.device_id), "link"));
    bar.append(el("small", "muted", `изменён ${new Date(d.updated_at).toLocaleString("ru-RU")}`), el("div", "spacer"),
      button(d.pinned ? "Открепить" : "Закрепить", async () => { current = await api("PATCH", `documents/${d.id}`, { pinned: !d.pinned }); await loadDocs(true); drawDoc(); }),
      button("Печать", () => { document.body.classList.add("printing-doc"); window.addEventListener("afterprint", () => document.body.classList.remove("printing-doc"), { once: true }); window.print(); }),
      button("Изменить", () => { editing = true; drawDoc(); }, "primary"),
      button("Удалить", async () => {
        if (!(await ask(`Удалить документ «${d.title}»?`, { action: "Удалить", danger: true }))) return;
        try { await api("DELETE", `documents/${d.id}`); current = null; await loadDocs(); drawDoc(); } catch (e) { fail(e); }
      }, "danger"));
    head.append(bar);
    box.replaceChildren(head, editing ? editor(d) : renderMd(d.body));
  }

  function editor(d) {
    const form = el("form", "doc-edit");
    const title = input("text", d.title, { maxLength: 200, required: true });
    const cat = select(Object.entries(CAT), d.category);
    const dev = select([["", "— без устройства —"], ...devices.filter((x) => x.type !== "SOCKET").sort((a, b) => a.name.localeCompare(b.name)).map((x) => [x.id, x.name])], d.device_id || "");
    const body = el("textarea", "mono"); body.rows = 22; body.value = d.body; body.maxLength = 200000;
    const row = el("div", "row wrap");
    const tl = el("label", "grow"); tl.append("Название", title);
    const cl = el("label"); cl.append("Вид", cat);
    const dl = el("label"); dl.append("Устройство", dev);
    row.append(tl, cl, dl);
    const save = el("button", "primary", "Сохранить"); save.type = "submit";
    const cancel = button("Отмена", () => { editing = false; drawDoc(); });
    const bar = el("div", "row"); bar.append(el("div", "spacer"), cancel, save);
    form.append(row, el("small", "muted", "Заголовки: # ## ###; списки: - или 1.; **жирный**, `код`, [ссылка](https://…); код — между строками ```."), body, bar);
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      try {
        current = await api("PATCH", `documents/${d.id}`, { title: title.value.trim(), category: cat.value, device_id: dev.value || null, body: body.value });
        editing = false; await loadDocs(true); drawDoc();
      } catch (e) { fail(e); }
    });
    return form;
  }

  $("doc-new").addEventListener("click", async () => {
    try {
      await loadDevices();
      const d = await api("POST", "documents", { title: "Новый документ", category: "note", body: "" });
      current = d; editing = true; await loadDocs(true); drawDoc();
    } catch (e) { fail(e); }
  });
  let timer = 0;
  $("doc-search").addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(() => loadDocs(true).catch(fail), 250); });
  $("doc-cat").addEventListener("change", () => loadDocs(true).catch(fail));
  tabs.docs = async () => { await loadDevices(); await loadDocs(); drawDoc(); };

  // ------------------------------------------------------------------ in a device's card

  window.NetAdmin.linkedWork = async (deviceId) => {
    const [t, d] = await Promise.all([api("GET", `tasks?device_id=${deviceId}`), api("GET", `documents?device_id=${deviceId}`)]);
    if (!t.length && !d.length) return null;
    const card = el("div", "card");
    if (t.length) {
      card.append(el("h3", null, `Задачи · ${t.length}`));
      for (const x of t.slice(0, 5)) card.append(button(`${x.title}${x.due_date ? ` · ${fmtDate(x.due_date)}` : ""}`, () => { $("card-dialog").close(); openTab("tasks"); }, "link block"));
    }
    if (d.length) {
      card.append(el("h3", null, `Документы · ${d.length}`));
      for (const x of d.slice(0, 5)) card.append(button(x.title, async () => { $("card-dialog").close(); openTab("docs"); setTimeout(() => openDoc(x.id).catch(fail), 300); }, "link block"));
    }
    return card;
  };
})();
