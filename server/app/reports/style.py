# ruff: noqa: E501  -- CSS text: its lines are kept whole so the stylesheet reads as a stylesheet.
"""The look of the report: one light stylesheet for the screen and for A4 paper."""

CSS = """
:root { --ink:#1d2733; --muted:#5b6776; --line:#d9dfe6; --soft:#f4f6f9; --accent:#1d4f91; --ok:#1b7f3b; --warn:#a15c00; --bad:#b3261e; }
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body { margin: 0; background: #e9edf2; color: var(--ink); font: 14px/1.55 "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; }
.page { max-width: 1120px; margin: 0 auto; background: #fff; padding: 40px 48px 56px; box-shadow: 0 2px 18px rgba(20,30,50,.12); }
.topnav { position: sticky; top: 0; z-index: 5; background: #14233a; padding: 8px 16px; display: flex; flex-wrap: wrap; gap: 4px 14px; }
.topnav a { color: #cfe0ff; text-decoration: none; font-size: 13px; }
.topnav a:hover { color: #fff; text-decoration: underline; }
.topnav .print { margin-left: auto; color: #fff; font-size: 12px; }
h1 { font-size: 30px; line-height: 1.2; margin: 0 0 4px; color: var(--accent); }
h2 { font-size: 22px; margin: 44px 0 14px; padding: 6px 0 6px 12px; border-left: 6px solid var(--accent); background: var(--soft); }
h2, h3 { scroll-margin-top: 64px; }
h3 { font-size: 16px; margin: 26px 0 8px; color: var(--accent); }
h4 { font-size: 14px; margin: 18px 0 6px; }
p { margin: 6px 0 10px; }
.sub { color: var(--muted); margin: 0 0 18px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.cover { border-bottom: 3px solid var(--accent); padding-bottom: 18px; margin-bottom: 22px; }
.meta { display: flex; flex-wrap: wrap; gap: 4px 28px; color: var(--muted); font-size: 13px; }
.meta b { color: var(--ink); }
.tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; margin: 16px 0 6px; }
.tile { border: 1px solid var(--line); border-radius: 8px; padding: 10px 14px; background: var(--soft); }
.tile b { display: block; font-size: 26px; line-height: 1.1; color: var(--accent); }
.tile span { font-size: 12.5px; color: var(--muted); }
.toc { columns: 2; column-gap: 32px; padding: 0; margin: 10px 0 0; list-style: none; }
.toc li { break-inside: avoid; margin: 0 0 8px; }
.toc a { color: var(--accent); font-weight: 600; text-decoration: none; }
.toc small { display: block; color: var(--muted); }
.attn { list-style: none; padding: 0; margin: 8px 0; }
.attn li { padding: 7px 12px; margin: 0 0 6px; border-radius: 6px; border: 1px solid var(--line); border-left-width: 6px; background: #fff; break-inside: avoid; }
.attn li.bad { border-left-color: var(--bad); background: #fdf2f1; }
.attn li.warn { border-left-color: #e0a100; background: #fff9e8; }
.attn a { color: inherit; }
.ok-box { padding: 10px 14px; border: 1px solid #b7dfc4; background: #eef8f1; border-radius: 6px; color: var(--ok); font-weight: 600; }
.note { padding: 9px 13px; border: 1px solid #c8d6ee; background: #eef3fb; border-radius: 6px; color: #25426b; font-size: 13px; margin: 10px 0; }
table.t { width: 100%; border-collapse: collapse; margin: 8px 0 14px; font-size: 12.8px; }
.t th { background: #e8edf4; text-align: left; padding: 6px 8px; border-bottom: 2px solid #b9c4d2; font-weight: 600; vertical-align: bottom; }
.t td { padding: 5px 8px; border-bottom: 1px solid var(--line); vertical-align: top; }
.t tr:nth-child(even) td { background: #fafbfc; }
.t td.n, .t th.n { text-align: right; white-space: nowrap; }
.t td.nw { white-space: nowrap; }
.mono { font-family: Consolas, "Courier New", monospace; font-size: 12px; }
.b { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11.5px; font-weight: 600; border: 1px solid transparent; white-space: nowrap; }
.b.ok { background: #e3f4e8; color: var(--ok); border-color: #b7dfc4; }
.b.bad { background: #fdeceb; color: var(--bad); border-color: #f0bcb8; }
.b.warn { background: #fff3d6; color: var(--warn); border-color: #ecd08f; }
.b.off { background: #eceff3; color: #5b6776; border-color: #d3d9e0; }
.b.info { background: #e6eefb; color: #25426b; border-color: #c3d3ee; }
.bar { display: block; width: 130px; height: 9px; }
.chip { display: inline-block; width: 11px; height: 11px; border-radius: 3px; vertical-align: -1px; margin-right: 6px; border: 1px solid rgba(0,0,0,.25); }
.topo { width: 100%; height: auto; border: 1px solid var(--line); border-radius: 8px; background: #fcfdfe; margin: 8px 0; }
.racks { display: flex; flex-wrap: wrap; gap: 26px; align-items: flex-start; }
.rackbox { flex: 0 1 330px; break-inside: avoid; }
.rackside { flex: 1 1 380px; }
.rackbox svg { width: 100%; height: auto; }
.swhead { display: flex; flex-wrap: wrap; gap: 8px 18px; align-items: baseline; }
.swblock { break-inside: auto; margin-bottom: 18px; }
.keys { font-family: Consolas, monospace; font-size: 12px; }
pre.doc { white-space: pre-wrap; background: var(--soft); border: 1px solid var(--line); border-radius: 6px; padding: 10px 12px; font: 12.5px/1.5 Consolas, monospace; }
.legend { display: flex; flex-wrap: wrap; gap: 4px 16px; font-size: 12px; color: var(--muted); margin: 4px 0 10px; }
footer.end { margin-top: 40px; padding-top: 10px; border-top: 1px solid var(--line); color: var(--muted); font-size: 12px; }
@page { size: A4; margin: 15mm 13mm 16mm; @bottom-center { content: "Отчёт по инфраструктуре · стр. " counter(page) " из " counter(pages); font-size: 9pt; color: #5b6776; } }
@page wide { size: A4 landscape; margin: 13mm 11mm 15mm; @bottom-center { content: "Отчёт по инфраструктуре · стр. " counter(page) " из " counter(pages); font-size: 9pt; color: #5b6776; } }
@media print {
  body { background: #fff; font-size: 11pt; }
  .page { box-shadow: none; padding: 0; max-width: none; }
  .topnav { display: none; }
  h2 { break-before: page; break-after: avoid; margin-top: 0; }
  h2.first { break-before: auto; }
  h3, h4 { break-after: avoid; }
  table.t { font-size: 9.5pt; }
  .t thead { display: table-header-group; }
  .t tr { break-inside: avoid; }
  section.wide { page: wide; }
  a { color: inherit; text-decoration: none; }
  * { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
}
@media (max-width: 720px) { .page { padding: 20px 14px 36px; } .toc { columns: 1; } }
"""
