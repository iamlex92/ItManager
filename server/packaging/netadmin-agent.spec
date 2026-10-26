# PyInstaller build of the standalone server (one folder, no Python needed on the target):
#   cd server && pyinstaller --noconfirm --distpath build/dist --workpath build/work packaging/netadmin-agent.spec
# The same spec builds the Linux binary for the .deb and the Windows .exe for the installer.
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files, collect_submodules

server = Path(SPECPATH).parent
alembic = server / "alembic"

datas = [(str(alembic / "env.py"), "alembic"), (str(alembic / "script.py.mako"), "alembic")]
datas += [(str(p), "alembic/versions") for p in sorted((alembic / "versions").glob("*.py"))]
datas += [(str(p), str(Path("app/static") / p.parent.relative_to(server / "app" / "static")))
          for p in (server / "app" / "static").rglob("*") if p.is_file()]
# pysnmp reads its MIB modules from the package folder on disk, so they ship as files too.
datas += collect_data_files("pysnmp", include_py_files=True)

hiddenimports = (
    collect_submodules("app")
    + collect_submodules("uvicorn")
    + collect_submodules("pysnmp")
    + collect_submodules("asyncpg")
    + ["alembic.ddl.postgresql"]
)

a = Analysis(
    [str(server / "packaging" / "agent_main.py")],
    pathex=[str(server)],
    datas=datas,
    hiddenimports=hiddenimports,
    excludes=["tkinter", "pytest", "IPython"],
)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="netadmin-agent", console=True, upx=False)
coll = COLLECT(exe, a.binaries, a.datas, name="netadmin-agent", upx=False)
