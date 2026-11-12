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
    $("vlan-rows").replaceChildren(...vlans.map((v) => {
      const tr = document.createElement("tr");
      const td = (el) => { const c = document.createElement("td"); c.append(el); tr.append(c); return el; };
      const input = (type, value) => { const el = document.createElement("input"); el.type = type; el.value = value ?? ""; return td(el); };
      td(document.createTextNode(String(v.vlan_id)));
      const name = input("text", v.name); name.maxLength = 50;
      const subnet = input("text", v.subnet_cidr);
      const color = input("color", v.color_hex);
      const desc = input("text", v.description);
      const actions = document.createElement("td");
      const del = document.createElement("button");
      del.type = "button"; del.textContent = "Удалить"; del.className = "danger";
      actions.append(del); tr.append(actions);
      for (const el of [name, subnet, color, desc]) {
        el.addEventListener("change", async () => {
          try {
            vlanError(null);
            await api("PATCH", `vlans/${v.vlan_id}`, {
              name: name.value.trim() || v.name, subnet_cidr: subnet.value.trim() || null, color_hex: color.value, description: desc.value.trim() || null,
            });
            toast(`VLAN ${v.vlan_id} сохранён`);
          } catch (e) { vlanError(e); }
        });
      }
      del.addEventListener("click", async () => {
        if (!await ask(`Удалить VLAN ${v.vlan_id} (${v.name}) из справочника?`)) return;
        try { vlanError(null); await api("DELETE", `vlans/${v.vlan_id}`); await loadVlans(); } catch (e) { vlanError(e); }
      });
      return tr;
    }));
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
