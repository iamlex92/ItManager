"use strict";
// The «VLAN и подсети» tab: the server's own dictionary of VLANs (names, subnets, colours); nothing is sent to switches.
// Split out of admin.js; builds on it through window.NetAdmin.
(() => {
  const { $, api, toast, fail, ask, emptyRow, fieldError, tabs } = window.NetAdmin;

  const vlanError = (e) => { $("vlan-error").hidden = !e; $("vlan-error").textContent = e ? e.message : ""; };

  $("vlan-cleanup").addEventListener("click", async () => {
    try {
      const found = await api("POST", "vlans/cleanup");
      if (!found.count) { toast("Неиспользуемых VLAN нет: каждый есть на порту или у него задана подсеть или описание"); return; }
      const names = found.vlans.map((v) => (v.name && v.name !== `VLAN ${v.vlan_id}` ? `${v.vlan_id} «${v.name}»` : String(v.vlan_id)));
      const list = names.length > 40 ? `${names.slice(0, 40).join(", ")} и ещё ${names.length - 40}` : names.join(", ");
      if (!await ask(`Удалить неиспользуемые VLAN: ${found.count}?\nИх нет ни на одном порту, подсеть и описание не заданы. VLAN 1 остаётся. Если один из них нужен как запись плана нумерации, задайте ему описание, и его не тронут.\n${list}`, { action: "Удалить" })) return;
      const done = await api("POST", "vlans/cleanup?dry_run=false");
      toast(`Удалено VLAN: ${done.count}`);
      await loadVlans();
    } catch (e) { fail(e); }
  });

  async function loadVlans() {
    const vlans = await api("GET", "vlans");
    if (!vlans.length) {
      $("vlan-rows").replaceChildren(emptyRow(6, "VLAN пока нет. Добавьте первый в строке ниже или импортируйте конфигурацию коммутатора (Порты… → Импорт конфига)."));
      return;
    }
    $("vlan-rows").replaceChildren(...vlans.map((v) => vlanRow(v)));
  }

  const mk = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  const btn = (text, cls, onClick) => { const b = mk("button", cls, text); b.type = "button"; b.addEventListener("click", onClick); return b; };

  /** A VLAN as a line to read; «Изменить» turns it into a form with its own «Сохранить» and «Отмена». */
  function vlanRow(v, editing = false) {
    const tr = document.createElement("tr");
    const td = (...kids) => { const c = document.createElement("td"); c.append(...kids); tr.append(c); return c; };
    td(mk("b", "mono", String(v.vlan_id)));
    const swap = (edit) => tr.replaceWith(vlanRow(v, edit));
    if (!editing) {
      td(v.name);
      td(mk("span", "mono", v.subnet_cidr || "—"));
      const chip = mk("span", "vlan-chip"); chip.style.background = v.color_hex; chip.title = v.color_hex;
      td(chip);
      td(mk("span", v.description ? "" : "muted", v.description || "—"));
      const actions = td(btn("Изменить", null, () => swap(true)), btn("Удалить", "danger", async () => {
        if (!await ask(`Удалить VLAN ${v.vlan_id} (${v.name}) из справочника?`)) return;
        try { vlanError(null); await api("DELETE", `vlans/${v.vlan_id}`); await loadVlans(); } catch (e) { vlanError(e); }
      }));
      actions.className = "row-actions";
      return tr;
    }
    tr.className = "editing";
    const input = (type, value, label) => { const i = document.createElement("input"); i.type = type; i.value = value ?? ""; i.setAttribute("aria-label", label); td(i); return i; };
    const name = input("text", v.name, "Название"); name.maxLength = 50;
    const subnet = input("text", v.subnet_cidr, "Подсеть"); subnet.placeholder = "10.10.20.0/24";
    const color = input("color", v.color_hex, "Цвет");
    const desc = input("text", v.description, "Описание");
    const save = async () => {
      const cidr = subnet.value.trim();
      if (cidr && !CIDR.test(cidr)) { vlanError(new Error("Подсеть пишется так: 10.10.20.0/24")); subnet.focus(); return; }
      try {
        vlanError(null);
        const saved = await api("PATCH", `vlans/${v.vlan_id}`, {
          name: name.value.trim() || v.name, subnet_cidr: cidr || null, color_hex: color.value, description: desc.value.trim() || null,
        });
        Object.assign(v, saved);
        toast(`VLAN ${v.vlan_id} сохранён`);
        swap(false);
      } catch (e) { vlanError(e); }
    };
    const actions = td(btn("Сохранить", "primary", save), btn("Отмена", null, () => { vlanError(null); swap(false); }));
    actions.className = "row-actions";
    tr.addEventListener("keydown", (ev) => {
      if (ev.key === "Enter") { ev.preventDefault(); save(); }
      if (ev.key === "Escape") { ev.stopPropagation(); vlanError(null); swap(false); }
    });
    setTimeout(() => name.focus(), 0);
    return tr;
  }

  const CIDR = /^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\/(3[0-2]|[12]?\d)$/;
  $("vlan-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const id = Number.parseInt($("vlan-id").value, 10);
    const subnet = $("vlan-subnet").value.trim();
    const problems = [
      ["vlan-subnet", subnet && !CIDR.test(subnet) ? "Подсеть пишется так: 10.10.20.0/24" : null],
      ["vlan-name", $("vlan-name").value.trim() ? null : "Укажите название"],
      ["vlan-id", id >= 1 && id <= 4094 ? null : "Номер от 1 до 4094"],
    ];
    for (const [f, m] of problems) fieldError(f, m);
    if (problems.some(([, m]) => m)) return;
    try {
      vlanError(null);
      await api("POST", "vlans", {
        vlan_id: Number.parseInt($("vlan-id").value, 10), name: $("vlan-name").value.trim(),
        subnet_cidr: $("vlan-subnet").value.trim() || null, color_hex: $("vlan-color").value, description: $("vlan-desc").value.trim() || null,
      });
      $("vlan-form").reset();
      await loadVlans();
    } catch (e) { vlanError(e); }
  });

  tabs.vlans = loadVlans;
})();
