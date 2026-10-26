# Развёртывание сервера NetAdmin на заводе

Пошаговая инструкция для ИТ-отдела. Сервер ставится одним из трёх способов:

| Способ | Где | Раздел |
|---|---|---|
| Пакет `.deb` | Сервер или виртуальная машина с Debian 12+ / Ubuntu 22.04+. Рекомендуется для постоянной работы. | 2 |
| Установщик Windows | Рабочий компьютер с Windows 10/11 или Windows Server 2016+, без Docker. | 3 |
| Docker Compose | Любой Linux с Docker. | 4 |

Сборки лежат на странице релиза [server-latest](https://github.com/iamlex92/ItManager/releases/tag/server-latest)
и обновляются после каждого изменения сервера в master.

Во всех способах сервер по умолчанию ничего не меняет на оборудовании: коммутаторы и мониторинг эмулируются,
`ALLOW_DEVICE_WRITES=false`. Подробнее в разделе «Режим только чтения».

## 1. Что нужно

- Debian/Ubuntu или Docker: 2 vCPU, 4 ГБ ОЗУ, 40 ГБ диска. Windows: любой рабочий компьютер, 2 ГБ свободной памяти и 2 ГБ диска.
- Сетевой доступ с сервера:
  - к VLAN управления коммутаторов (по умолчанию `10.99.0.0/24`) для SNMP (UDP 161) и ping;
  - к VLAN принтеров для SNMP;
  - к остальным подсетям для ping, если нужен контроль доступности.
- Сетевой доступ к серверу:
  - для телефонов: TCP 8000 из Wi-Fi ИТ-отдела или из VPN;
  - для коммутаторов, если нужны трапы: UDP 162.
- Для уведомлений в Telegram: исходящий HTTPS (443) до `api.telegram.org`, напрямую или через прокси.
- Постоянный IP-адрес сервера, например `10.99.0.50`.

## 2. Debian / Ubuntu: пакет .deb

Пакет ставит сервер в `/opt/netadmin`, службу systemd `netadmin` и берёт PostgreSQL из системы.

```sh
# скачать netadmin-agent_*.deb со страницы server-latest, затем:
sudo apt install ./netadmin-agent_*.deb
```

Установка сама:
- ставит PostgreSQL, если его нет, и создаёт в нём роль и базу `netadmin` (вход по локальному сокету, без пароля);
- создаёт `/etc/netadmin/netadmin.env` со случайными `API_TOKEN` и `SECRET_KEY` (права 640, root:netadmin);
- включает и запускает службу; копии базы складываются в `/var/lib/netadmin/backups`;
- в конце печатает адрес и ключ для приложения.

| Действие | Команда |
|---|---|
| Адрес и ключ для приложения | `sudo netadmin-agent info` |
| Настройки | `sudo nano /etc/netadmin/netadmin.env`, затем `sudo systemctl restart netadmin` |
| Состояние и журнал | `systemctl status netadmin`, `journalctl -u netadmin -f` |
| Копия базы сейчас | `sudo -u netadmin netadmin-agent backup` |
| Обновление | `sudo apt install ./netadmin-agent_<новая версия>.deb`: настройки и база остаются, миграции применяются при старте |
| Удаление | `sudo apt remove netadmin-agent`: база и копии остаются |

Служба работает от пользователя `netadmin`, не от root. Для трапов на порт 162 и для ping ей выданы только
нужные права (`CAP_NET_BIND_SERVICE`, `CAP_NET_RAW`). Трапы в этом варианте включены (`TRAP_LISTEN_PORT=162`).

## 3. Windows: установщик

`NetAdmin-Server-Setup.exe` со страницы server-latest. Нужны права администратора; Docker и Python не нужны.

Установщик:
- ставит программу в `C:\Program Files\NetAdmin`, вместе со своей базой PostgreSQL 16;
- данные, настройки, копии и журналы хранит в `C:\ProgramData\NetAdmin` (доступ только у администраторов и системы);
- регистрирует службу Windows «NetAdmin» с автозапуском; при первом запуске она создаёт базу (до минуты);
- открывает в брандмауэре TCP 8000 для доменной и частной сети (в общественной сети порт закрыт);
- в конце показывает адрес и ключ для приложения.

Встроенная база слушает только `127.0.0.1:54329`: с других компьютеров к ней не подключиться,
и она не мешает другому PostgreSQL на том же компьютере. Это тот же PostgreSQL, что и на сервере,
поэтому копию базы с Windows можно восстановить на Debian и наоборот (при том же `SECRET_KEY`).

| Действие | Как |
|---|---|
| Адрес и ключ для приложения | Пуск → NetAdmin → «Адрес и ключ для приложения» |
| Настройки | Пуск → NetAdmin → «Настройки сервера», затем перезапустить службу NetAdmin (`services.msc`) |
| Журналы | `C:\ProgramData\NetAdmin\logs` |
| Копия базы сейчас | в PowerShell от администратора: `& "C:\Program Files\NetAdmin\netadmin-agent.exe" backup` |
| Обновление | запустить новый установщик поверх: служба остановится, настройки и база останутся |
| Удаление | «Приложения» → NetAdmin Server: база, настройки и копии в `C:\ProgramData\NetAdmin` остаются |

Компьютер должен быть включён, пока сервером пользуются: при выключении или сне телефон увидит «Сервер недоступен».
Если компьютер в спящем режиме по ночам, копия базы сделается после пробуждения.

## 4. Docker Compose

Docker Engine и плагин Compose:

```sh
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # перелогиниться
```

Установка:

```sh
sudo mkdir -p /opt/netadmin && sudo chown $USER /opt/netadmin
cd /opt/netadmin
git clone https://github.com/iamlex92/ItManager.git
cd ItManager/server
cp .env.example .env
chmod 600 .env
```

Откройте `.env` в редакторе (`nano .env`) и задайте:

| Параметр | Что поставить |
|---|---|
| `API_TOKEN` | Длинный случайный ключ: `python3 -c "import secrets; print(secrets.token_urlsafe(32))"`. Этот же ключ вводится в приложении. |
| `POSTGRES_PASSWORD` | Свой пароль базы, до первого запуска. |
| `SECRET_KEY` | Ключ шифрования SNMP community и учётных данных в базе: `openssl rand -base64 32 \| tr '+/' '-_'`. Сохраните копию в менеджере паролей: без этого ключа зашифрованные значения и восстановленная из копии база не читаются. |
| `API_BIND` | IP сервера, на котором телефоны будут к нему обращаться (например `10.99.0.50`). `0.0.0.0` слушает на всех адресах. |
| `SEED_DEMO_DATA` | `false`: на заводе база начинается пустой, учёт собирается сканером сети. |
| `HAL_MODE` | Оставить `emulator`, пока драйвер реальных коммутаторов не проверен. |
| `ALLOW_DEVICE_WRITES` | **Оставить `false`.** |
| `MONITOR_MODE` | `real`, когда сервер стоит в сети завода: настоящие ping и SNMP-запросы только на чтение. До этого `emulator`. |
| `TRAP_COMMUNITY` | Community, с которым коммутаторы шлют трапы. |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` | Уведомления о событиях в Telegram, см. раздел 9. Пустые: уведомлений нет. |
| `BACKUP_INTERVAL_HOURS`, `BACKUP_KEEP_DAYS` | Как часто делать копию базы и сколько дней их хранить (по умолчанию раз в сутки, 14 дней). |

Файл `.env` содержит секреты. Не коммитьте его и не пересылайте в мессенджерах.
В пакетах для Debian и Windows те же параметры лежат в `netadmin.env` и уже заполнены безопасными значениями.

Запуск:

```sh
docker compose up -d --build
docker compose ps                     # db, api и backup в состоянии running/healthy
curl http://localhost:8000/api/v1/health
```

Сервер сам запускается после перезагрузки машины (`restart: unless-stopped`).

### Проверка после установки (любой способ)

`http://<адрес>:8000/api/v1/health` показывает режимы:
- `"hal_mode": "emulator"`;
- `"device_writes_enabled"`: в эмуляторе `true`, потому что изменения уходят только в эмулятор;
- `"monitor_mode"`;
- `"secrets_encrypted": true`, если задан `SECRET_KEY` (в пакетах задан всегда);
- `"telegram_alerts": true`, если настроены уведомления в Telegram.

## 5. Подключение телефона

1. В приложении откройте ⚙ (правый верхний угол).
2. Введите адрес сервера, например `10.99.0.50`; порт 8000 подставится сам.
3. Вставьте `API_TOKEN` и нажмите «Проверить соединение». Адрес и ключ показывает `netadmin-agent info`
   (Debian: `sudo netadmin-agent info`, Windows: Пуск → NetAdmin → «Адрес и ключ для приложения»).
4. Выключите демо-данные и сохраните.

Дальше «Устройства» → значок поиска → «Сканер сети»: укажите подсети и SNMP community только для чтения, затем «Сканировать» и «Добавить в учёт».

## 6. Доступ извне через VPN

Сервер не публикуется в интернет. Снаружи к нему подключаются только через заводской VPN.

- **Если VPN уже есть** (например на межсетевом экране), разрешите из VPN-подсети доступ к `IP_сервера:8000/tcp` и ни к чему больше.
- **Если VPN нет**, проще всего поднять WireGuard на шлюзе:
  1. Создайте отдельного пира для каждого телефона.
  2. В `AllowedIPs` на телефоне укажите только адрес сервера, например `10.99.0.50/32`.
  3. На телефоне установите приложение WireGuard и импортируйте конфиг по QR-коду.

Ограничьте доступ на самом сервере (пример для ufw; `10.8.0.0/24` это VPN, `10.10.10.0/24` это ИТ-отдел):

```sh
sudo ufw allow from 10.8.0.0/24 to any port 8000 proto tcp
sudo ufw allow from 10.10.10.0/24 to any port 8000 proto tcp
sudo ufw allow from 10.99.0.0/24 to any port 162 proto udp    # трапы от коммутаторов
sudo ufw allow OpenSSH
sudo ufw enable
```

Docker публикует порты в обход ufw. Поэтому ограничивайте доступ и параметром `API_BIND` (адрес внутреннего интерфейса), и правилами на межсетевом экране перед сервером.

## 7. Режим только чтения

| Что | Как защищено |
|---|---|
| Изменения на коммутаторах (VLAN, shutdown, перезапуск порта, write memory) | Пока `ALLOW_DEVICE_WRITES=false`, сервер отвечает 403 и ничего не отправляет. Драйвер реальных коммутаторов пока заглушка. |
| Мониторинг, сканер сети, загрузка портов | Только ping и SNMP GET/GETNEXT. Команд SET нет. |
| Сценарии AD и серверов | Выполняются в эмуляции, учётных данных AD на сервере нет. |
| Журнал | Каждое действие, включая заблокированные попытки, пишется в журнал аудита. |
| Секреты | SNMP community и учётные данные хранятся в базе зашифрованными (`SECRET_KEY`). API их не отдаёт, только признак «задано». В журнал аудита они не попадают. |

Включать `ALLOW_DEVICE_WRITES` можно только по отдельному решению, после проверки на одном коммутаторе в окно обслуживания.

## 8. Коммутаторы: SNMP и трапы

Нужна только read-only community, права на запись не нужны.

Cisco IOS:

```
snmp-server community <ro-community> RO <ACL-с-адресом-сервера>
snmp-server enable traps snmp linkdown linkup
snmp-server host 10.99.0.50 version 2c <trap-community>
lldp run
```

Eltex MES:

```
snmp-server server
snmp-server community <ro-community> ro
snmp-server host 10.99.0.50 traps version 2 <trap-community>
lldp run
```

Перед вводом команд согласуйте окно работ: это изменения конфигурации самих коммутаторов, и сервер их не делает.

## 9. Уведомления в Telegram

1. В Telegram напишите @BotFather → `/newbot`, задайте имя. Он пришлёт токен вида `123456:ABC...`.
2. Создайте группу ИТ-отдела (или возьмите существующую) и добавьте в неё бота.
3. Напишите в группу любое сообщение и откройте с компьютера
   `https://api.telegram.org/bot<токен>/getUpdates`. В ответе найдите `"chat":{"id":-100...}`: это `TELEGRAM_CHAT_ID`.
4. Впишите оба значения в настройки и перезапустите сервер: Docker: `.env` и `docker compose up -d`;
   Debian: `/etc/netadmin/netadmin.env` и `sudo systemctl restart netadmin`; Windows: «Настройки сервера» и перезапуск службы NetAdmin.
5. Проверка: в `health` должно быть `"telegram_alerts": true`, затем
   ```sh
   curl -X POST -H "Authorization: Bearer $API_TOKEN" http://localhost:8000/api/v1/notifications/telegram/test
   ```
   В группу придёт «Проверка связи NetAdmin». Если нет, ответ скажет почему (неверный токен, бот не в чате, нет сети).

Что приходит:
- события от уровня `TELEGRAM_MIN_SEVERITY` (по умолчанию предупреждения и критичные): упавший порт,
  недоступный коммутатор, сервер, МФУ или камера, кончающийся тонер;
- только если событие не закрылось за `TELEGRAM_DELAY_SECONDS` (60 секунд), чтобы мигающий порт не будил людей;
- «✅ Восстановлено», когда событие закрылось (выключается `TELEGRAM_NOTIFY_RESOLVED=false`);
- не больше `TELEGRAM_MAX_PER_MINUTE` сообщений в минуту, остальные считаются и упоминаются в следующем.

Серверу нужен исходящий HTTPS до `api.telegram.org`. Если интернет только через прокси, задайте `TELEGRAM_PROXY`.
Токен бота в журналы не пишется. Сообщения идут только наружу, на оборудование уведомления не влияют.

## 10. Резервные копии

Пакеты для Debian и Windows делают копии сами (служба NetAdmin, первая копия через минуту после запуска):

| | Debian | Windows |
|---|---|---|
| Папка | `/var/lib/netadmin/backups` | `C:\ProgramData\NetAdmin\backups` |
| Копия сейчас | `sudo -u netadmin netadmin-agent backup` | `netadmin-agent.exe backup` от администратора |
| Восстановление | `sudo systemctl stop netadmin`, `sudo -u netadmin netadmin-agent restore <файл>`, `sudo systemctl start netadmin` | остановить службу NetAdmin, `netadmin-agent.exe restore <файл>`, запустить службу |

Расписание и срок хранения: `BACKUP_INTERVAL_HOURS` и `BACKUP_KEEP_DAYS` в `netadmin.env`. Копируйте папку на другое
хранилище так же, как ниже для Docker (на Windows, например, заданием планировщика с `robocopy`).

Docker:

- Сервис `backup` делает `pg_dump` в каталог `backups/` рядом с `docker-compose.yml`. Файлы называются `netadmin-ГГГГММДД-ЧЧММСС.dump`.
- Копии старше `BACKUP_KEEP_DAYS` удаляются.
- Копируйте каталог `backups/` с сервера, например ночным заданием на файловое хранилище:
  ```sh
  # crontab -e
  30 3 * * * rsync -a /opt/netadmin/ItManager/server/backups/ backup@nas:/netadmin/
  ```
- Проверить, что копии делаются: `docker compose logs backup | tail`.
- Сделать копию прямо сейчас: `docker compose exec backup /bin/sh /backup.sh --once`.
- Восстановить из копии (останавливает API на время восстановления и спрашивает подтверждение):
  ```sh
  ./scripts/restore.sh backups/netadmin-20261025-030000.dump
  ```

База хранится в томе Docker `pgdata`. Она переживает пересборку, обновление и `docker compose down`. Удаляет её только `docker compose down -v`; эту команду на заводе не используйте.

## 11. Обновление

Debian и Windows: поставить новый пакет поверх (разделы 2 и 3). Настройки и база сохраняются, копия базы
делается через минуту после старта новой версии; для спокойствия сделайте её и перед обновлением.

Docker:

```sh
cd /opt/netadmin/ItManager/server
docker compose exec backup /bin/sh /backup.sh --once   # копия перед обновлением
git pull
docker compose up -d --build
curl http://localhost:8000/api/v1/health
```

Миграции базы применяются автоматически при старте сервера, в любом варианте.

## 12. Если что-то не так

| Симптом | Что проверить |
|---|---|
| Приложение: «Сервер недоступен» | Телефон в Wi-Fi завода или VPN включён; с телефона открывается `http://IP:8000/api/v1/health`; `API_BIND` и межсетевой экран. |
| «Сервер отклонил API-токен» | `API_TOKEN` в настройках и в приложении совпадают; после правки перезапустить сервер (раздел 9, шаг 4). |
| Сканер не находит имена и связи | Совпадает ли community, открыт ли UDP 161 от сервера до VLAN управления, включён ли LLDP. |
| API не стартует: `SecretsUnavailable` | `SECRET_KEY` в настройках не тот, которым шифровали базу (или пустой). Верните прежний ключ. |
| Не приходят уведомления в Telegram | `telegram_alerts` в `health`; тестовое сообщение (раздел 9) скажет причину; `docker compose logs api \| grep -i telegram`. |
| Windows: служба NetAdmin не запускается | Журналы в `C:\ProgramData\NetAdmin\logs`: `netadmin-service.*.log` (сервер) и `postgresql.log` (база). Порт 8000 или 54329 занят другой программой? |
| Debian: служба не запускается | `journalctl -u netadmin -n 100`; работает ли PostgreSQL: `systemctl status postgresql`. |
| Логи | Docker: `docker compose logs -f api`; Debian: `journalctl -u netadmin -f`; Windows: `C:\ProgramData\NetAdmin\logs`. |
