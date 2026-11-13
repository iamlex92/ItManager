"use strict";
// «Сроки и лицензии»: what is about to run out (warranties, licenses, certificates, other deadlines), the licenses
// themselves with their keys and the computers they stand on, and the other dated things to remember.
(() => {
  const { $, api, toast, fail, ask, openCard, tabs } = window.NetAdmin;

  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  const fmtDate = (iso) => (iso ? new Date(`${iso}T00:00:00`).toLocaleDateString("ru-RU") : "—");
  const KIND = { per_device: "на компьютер", per_user: "на пользователя", organization: "на организацию", subscription: "подписка", other: "другое" };
  const DKIND = { certificate: "сертификат", battery: "батарея", domain: "домен", contract: "договор", other: "другое" };
  const LEVEL = { expired: "просрочено", week: "меньше недели", month: "меньше месяца", soon: "скоро", ok: "" };
  const daysText = (n) => (n < 0 ? `просрочено на ${-n} дн.` : n === 0 ? "сегодня" : `через ${n} дн.`);

  let part = "soon", licenses = [], deadlines = [], devices = [], within = 365;

  function table(heads) {
    const t = el("table", "grid");
    const head = el("thead"), tr = el("tr");
    for (const h of heads) tr.append(el("th", null, h));
    head.append(tr);
    t.append(head, el("tbody"));
    return t;
  }
  const td = (tr, content, cls) => {
    const c = el("td", cls);
    if (content instanceof Node) c.append(content); else c.textContent = content ?? "—";
    tr.append(c);
    return c;
  };
  const button = (text, onClick, cls) => { const b = el("button", cls, text); b.type = "button"; b.addEventListener("click", onClick); return b; };

  // ------------------------------------------------------------------ a small form dialog

  function formDialog(title, build, onSave, { wide = false } = {}) {
    return new Promise((done) => {
      const dlg = el("dialog", wide ? "wide-dialog" : "");
      const form = el("form", "dialog");
      form.method = "dialog";
      form.append(el("h2", null, title));
      const fields = build(form);
      const err = el("p", "error"); err.hidden = true;
      const save = el("button", "primary", "Сохранить"); save.type = "submit";
      const cancel = button("Отмена", () => { dlg.close(); done(false); });
      const row = el("div", "row"); row.append(el("div", "spacer"), cancel, save);
      form.append(err, row);
      form.addEventListener("submit", async (ev) => {
        ev.preventDefault();
        err.hidden = true;
        save.disabled = true;
        try { await onSave(fields); dlg.close(); done(true); } catch (e) { err.textContent = e.message || String(e); err.hidden = false; save.disabled = false; }
      });
      dlg.addEventListener("cancel", () => done(false));
      dlg.addEventListener("close", () => dlg.remove());
      dlg.append(form);
      document.body.append(dlg);
      dlg.showModal();
    });
  }
  function field(form, label, input, hint) {
    const l = el("label");
    l.append(label, input);
    if (hint) l.append(el("small", "muted", hint));
    form.append(l);
    return input;
  }
  const input = (type, value = "", extra = {}) => Object.assign(el("input"), { type, value: value ?? "", ...extra });
  function select(options, value) {
    const s = el("select");
    for (const [v, t] of options) s.append(new Option(t, v));
    s.value = value ?? options[0][0];
    return s;
  }

  // ------------------------------------------------------------------ expirations

  async function loadSoon() {
    const items = await api("GET", `lifecycle/expirations?within_days=${within}`);
    const box = $("lc-soon");
    const t = table(["Срок", "Что", "Осталось", ""]);
    const body = t.tBodies[0];
    for (const i of items) {
      const tr = el("tr", `lvl-${i.level}`);
      td(tr, fmtDate(i.due));
      td(tr, i.title);
      if (i.device_id) { tr.classList.add("clickable"); tr.addEventListener("click", () => openCard(i.device_id)); }
      td(tr, daysText(i.days_left));
      td(tr, LEVEL[i.level] || "", "lvl-text");
      body.append(tr);
    }
    if (!items.length) {
      const r = el("tr"), c = el("td", "muted", "В ближайшее время ничего не истекает. Сроки появятся, когда вы заполните гарантию у устройств, лицензии и «Прочие сроки».");
      c.colSpan = 4; r.append(c); body.append(r);
    }
    box.replaceChildren(el("div", "table-wrap"));
    box.firstChild.append(t);
  }

  // ------------------------------------------------------------------ licenses

  async function loadLicenses() {
    [licenses, devices] = await Promise.all([api("GET", "licenses"), api("GET", "devices")]);
    const byId = new Map(devices.map((d) => [d.id, d]));
    const t = table(["Программа", "Тип", "Места", "Действует до", "Ключ", "Поставщик", ""]);
    const body = t.tBodies[0];
    for (const l of licenses) {
      const tr = el("tr");
      const name = td(tr, el("span", null, l.software));
      if (l.vendor) name.append(el("small", "muted block", l.vendor));
      td(tr, KIND[l.kind] || l.kind);
      const seats = l.seats == null ? `${l.used}` : `${l.used} из ${l.seats}`;
      td(tr, seats, l.over ? "bad" : "");
      td(tr, l.valid_until ? fmtDate(l.valid_until) : "бессрочная");
      const keyCell = td(tr, "");
      if (l.has_key) {
        const shown = el("code", "muted", l.key_hint);
        keyCell.append(shown, " ",
          button("Показать", async (ev) => {
            const b = ev.currentTarget;
            try {
              const { key } = await api("POST", `licenses/${l.id}/reveal`);
              shown.textContent = key; shown.classList.remove("muted"); b.textContent = "Копировать";
              b.onclick = async () => { try { await navigator.clipboard.writeText(key); toast("Ключ скопирован"); } catch { toast("Не удалось скопировать: выделите и скопируйте вручную", true); } };
              setTimeout(() => { shown.textContent = l.key_hint; shown.classList.add("muted"); b.textContent = "Показать"; b.onclick = null; }, 20_000);
            } catch (e) { fail(e); }
          }, "ghost small"));
      } else keyCell.textContent = "—";
      td(tr, l.supplier);
      const act = td(tr, "");
      act.append(button("Изменить", () => editLicense(l, byId)), " ", button("Удалить", async () => {
        if (!(await ask(`Удалить лицензию «${l.software}»?\nКлюч и привязка к компьютерам будут удалены.`, { action: "Удалить", danger: true }))) return;
        try { await api("DELETE", `licenses/${l.id}`); await loadLicenses(); } catch (e) { fail(e); }
      }, "danger"));
      body.append(tr);
    }
    if (!licenses.length) { const r = el("tr"), c = el("td", "muted", "Лицензий пока нет. «+ Лицензия»: Windows, Office, 1С, антивирус, САПР, подписки."); c.colSpan = 7; r.append(c); body.append(r); }
    const wrap = el("div", "table-wrap"); wrap.append(t);
    $("lc-licenses-list").replaceChildren(wrap);
  }

  const SUGGEST = ["Windows", "Microsoft Office", "1С:Предприятие", "Kaspersky Endpoint Security", "nanoCAD", "AutoCAD", "КОМПАС-3D", "Astra Linux", "Adobe Acrobat", "WinRAR"];

  function editLicense(lic, byId) {
    const isNew = !lic;
    const seatsOf = new Set(lic ? lic.device_ids : []);
    return formDialog(isNew ? "Новая лицензия" : `Лицензия: ${lic.software}`, (form) => {
      const dl = el("datalist"); dl.id = "lc-software-list";
      for (const s of SUGGEST) dl.append(new Option(s));
      form.append(dl);
      const f = {};
      f.software = field(form, "Программа", input("text", lic?.software, { required: true, maxLength: 120 }));
      f.software.setAttribute("list", "lc-software-list");
      f.vendor = field(form, "Производитель", input("text", lic?.vendor, { maxLength: 100 }));
      const row1 = el("div", "row"); form.append(row1);
      f.kind = select(Object.entries(KIND), lic?.kind || "per_device");
      f.seats = input("number", lic?.seats ?? "", { min: 1, max: 100000 });
      const kl = el("label", "grow"); kl.append("Как лицензировано", f.kind);
      const sl = el("label"); sl.append("Количество мест", f.seats);
      row1.append(kl, sl);
      f.key = field(form, "Ключ", el("textarea"), isNew ? null : lic.has_key ? "Сейчас задан. Оставьте пустым, чтобы не менять; впишите новый, чтобы заменить." : "Ключ не задан.");
      f.key.rows = 2; f.key.maxLength = 4000; f.key.autocomplete = "off";
      const clear = lic?.has_key ? el("label", "check") : null;
      if (clear) { f.clear = Object.assign(el("input"), { type: "checkbox" }); clear.append(f.clear, " Удалить сохранённый ключ"); form.append(clear); }
      const row2 = el("div", "row"); form.append(row2);
      f.valid = input("date", lic?.valid_until || "");
      f.bought = input("date", lic?.purchased_on || "");
      const vl = el("label"); vl.append("Действует до (пусто — бессрочная)", f.valid);
      const bl = el("label"); bl.append("Куплена", f.bought);
      row2.append(vl, bl);
      f.supplier = field(form, "Поставщик", input("text", lic?.supplier, { maxLength: 100 }));
      f.contract = field(form, "Договор или счёт", input("text", lic?.contract, { maxLength: 100 }));
      f.notes = field(form, "Заметка", el("textarea")); f.notes.rows = 2; f.notes.value = lic?.notes || "";

      // The computers it stands on.
      const search = input("search", "", { placeholder: "Найти компьютер" });
      const list = el("div", "check-list");
      const pcs = devices.filter((d) => ["WORKSTATION", "SERVER", "VIRTUAL", "PRINTER", "UNKNOWN"].includes(d.type)).sort((a, b) => a.name.localeCompare(b.name));
      const used = el("small", "muted");
      const redraw = () => {
        const q = search.value.trim().toLowerCase();
        list.replaceChildren(...pcs.filter((d) => !q || d.name.toLowerCase().includes(q) || (d.assigned_to || "").toLowerCase().includes(q)).map((d) => {
          const l = el("label", "check");
          const cb = Object.assign(el("input"), { type: "checkbox", checked: seatsOf.has(d.id) });
          cb.addEventListener("change", () => { if (cb.checked) seatsOf.add(d.id); else seatsOf.delete(d.id); used.textContent = `Выбрано: ${seatsOf.size}`; });
          l.append(cb, ` ${d.name}`, el("span", "muted small", d.assigned_to ? ` · ${d.assigned_to}` : ""));
          return l;
        }));
        used.textContent = `Выбрано: ${seatsOf.size}`;
      };
      search.addEventListener("input", redraw);
      form.append(el("h3", null, "Где установлена"), search, list, used);
      redraw();
      f.getDevices = () => [...seatsOf];
      return f;
    }, async (f) => {
      const body = {
        software: f.software.value.trim(), vendor: f.vendor.value.trim() || null, kind: f.kind.value,
        seats: f.seats.value ? Number(f.seats.value) : null, valid_until: f.valid.value || null, purchased_on: f.bought.value || null,
        supplier: f.supplier.value.trim() || null, contract: f.contract.value.trim() || null, notes: f.notes.value.trim() || null,
        device_ids: f.getDevices(),
      };
      if (isNew) { if (f.key.value.trim()) body.key = f.key.value.trim(); }
      else if (f.clear && f.clear.checked) body.key = "";
      else if (f.key.value.trim()) body.key = f.key.value.trim();
      if (isNew) await api("POST", "licenses", body); else await api("PATCH", `licenses/${lic.id}`, body);
      await loadLicenses();
    }, { wide: true });
  }

  // ------------------------------------------------------------------ other deadlines

  async function loadDeadlines() {
    [deadlines, devices] = await Promise.all([api("GET", "deadlines?include_done=true"), api("GET", "devices")]);
    const byId = new Map(devices.map((d) => [d.id, d]));
    const t = table(["Срок", "Что", "Вид", "Устройство", "Заметка", ""]);
    const body = t.tBodies[0];
    for (const d of deadlines) {
      const tr = el("tr", d.is_done ? "muted" : "");
      td(tr, fmtDate(d.due_date));
      td(tr, d.title);
      td(tr, DKIND[d.kind] || d.kind);
      td(tr, d.device_id && byId.get(d.device_id) ? byId.get(d.device_id).name : "—");
      td(tr, d.notes);
      const act = td(tr, "");
      act.append(button(d.is_done ? "Вернуть" : "Готово", async () => {
        try { await api("PATCH", `deadlines/${d.id}`, { is_done: !d.is_done }); await loadDeadlines(); } catch (e) { fail(e); }
      }), " ", button("Изменить", () => editDeadline(d)), " ", button("Удалить", async () => {
        try { await api("DELETE", `deadlines/${d.id}`); await loadDeadlines(); } catch (e) { fail(e); }
      }, "danger"));
      body.append(tr);
    }
    if (!deadlines.length) { const r = el("tr"), c = el("td", "muted", "Других сроков нет. «+ Срок»: батарея ИБП, домен, сертификат сайта, договор обслуживания."); c.colSpan = 6; r.append(c); body.append(r); }
    const wrap = el("div", "table-wrap"); wrap.append(t);
    $("lc-deadlines-list").replaceChildren(wrap);
  }

  function editDeadline(d) {
    const isNew = !d;
    return formDialog(isNew ? "Новый срок" : "Срок", (form) => {
      const f = {};
      f.title = field(form, "Что", input("text", d?.title, { required: true, maxLength: 150, placeholder: "Замена батареи ИБП UPS-01" }));
      const row = el("div", "row"); form.append(row);
      f.kind = select(Object.entries(DKIND), d?.kind || "other");
      f.due = input("date", d?.due_date || "", { required: true });
      const kl = el("label", "grow"); kl.append("Вид", f.kind);
      const dl = el("label"); dl.append("Срок", f.due);
      row.append(kl, dl);
      f.device = select([["", "— без устройства —"], ...devices.filter((x) => x.type !== "SOCKET").sort((a, b) => a.name.localeCompare(b.name)).map((x) => [x.id, x.name])], d?.device_id || "");
      field(form, "Устройство", f.device);
      f.notes = field(form, "Заметка", el("textarea")); f.notes.rows = 2; f.notes.value = d?.notes || "";
      return f;
    }, async (f) => {
      const body = { title: f.title.value.trim(), kind: f.kind.value, due_date: f.due.value, device_id: f.device.value || null, notes: f.notes.value.trim() || null };
      if (isNew) await api("POST", "deadlines", body); else await api("PATCH", `deadlines/${d.id}`, body);
      await loadDeadlines();
    });
  }

  // ------------------------------------------------------------------ the tab

  function show(p) {
    part = p;
    for (const b of document.querySelectorAll("#lc-seg button")) b.classList.toggle("active", b.dataset.part === p);
    for (const [id, key] of [["lc-soon-box", "soon"], ["lc-licenses", "licenses"], ["lc-deadlines", "deadlines"]]) $(id).hidden = key !== p;
    const run = { soon: loadSoon, licenses: loadLicenses, deadlines: loadDeadlines }[p];
    run().catch(fail);
  }
  for (const b of document.querySelectorAll("#lc-seg button")) b.addEventListener("click", () => show(b.dataset.part));
  $("lc-within").addEventListener("change", () => { within = Number($("lc-within").value); loadSoon().catch(fail); });
  $("lc-license-new").addEventListener("click", async () => { devices = devices.length ? devices : await api("GET", "devices"); editLicense(null); });
  $("lc-deadline-new").addEventListener("click", async () => { devices = devices.length ? devices : await api("GET", "devices"); editDeadline(null); });
  tabs.lifecycle = () => show(part);
  // Small helpers other modules build their forms from.
  Object.assign(window.NetAdmin, { formDialog, field, input, select, el });
})();
