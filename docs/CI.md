# Когда запускается CI

Приватный репозиторий получает 2000 минут GitHub Actions в месяц (Windows считается x2, macOS x10).
Поэтому тяжёлое запускается только когда нужно.

| Событие | Что запускается | Где |
|---|---|---|
| Pull request, менялись `app/**` или gradle | юнит-тесты + сборка debug APK | Linux |
| Pull request, менялись `app/**` (не черновик) | smoke-тест на эмуляторе | Linux |
| Pull request, менялся `server/**` | pytest + ruff; сборка и проверка установки `.deb` | Linux |
| Merge в master | **ничего** (PR уже проверен) | |
| Тег `v*` или ручной запуск (Actions → Run workflow) | сборка и публикация APK (`android-latest`, подписанный `android-release`), установщики `.deb` + Windows `.exe` (`server-latest`), smoke | Linux + Windows |

Дополнительно:
- Новый push в тот же PR отменяет предыдущий незавершённый запуск.
- PR, которые правят только `*.md` или файлы вне `app/` / `server/`, CI не запускают.
- Windows-сборка установщика (самая дорогая) на PR не идёт, только при релизе.
- Кэши pip и Gradle включены; на PR кэш Gradle только читается.

Выпустить релиз: `git tag v0.11.0 && git push origin v0.11.0` или Actions → нужный workflow → Run workflow.
