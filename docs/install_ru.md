# Установка viaLogium и подключение базы данных

Гайд для viaLogium 1.2.1 на Minecraft 26.3 (Fabric). Подходит и для одного сервера, и для сети серверов за Velocity.

## 1. Что нужно

| Что | Версия | Куда |
|---|---|---|
| Fabric Loader | 0.19.5 или новее | сервер |
| Fabric API | 0.161.0+26.3 | `mods/` |
| Fabric Language Kotlin | 1.13.9+kotlin.2.3.10 | `mods/` |
| viaPanel | 3.1.0 или новее | `mods/` |
| viaLogium | 1.2.1 | `mods/` |
| Java | 25 | на машине сервера |

viaLogium – серверный мод, игрокам на клиенте ничего ставить не нужно. Драйверы SQLite, H2, MySQL, MariaDB и PostgreSQL уже внутри jar-файла.

## 2. Установка мода

1. Остановите сервер.
2. Положите jar-файлы из таблицы выше в папку `mods/`.
3. Запустите сервер один раз. Появится `config/vialogium.toml`.
4. Если база по умолчанию (SQLite) устраивает – всё, логирование уже работает. Проверьте командой `/vl status`: строка `Logging: OK` значит, что записи пишутся.

Для сети серверов дополнительно откройте `config/viaPanel/viaPanel.toml` и задайте `server_id` – ровно то имя, под которым сервер указан в `velocity.toml` (например, `lobby`, `arrakis`). По этому имени viaLogium отличает записи разных серверов в общей базе.

## 3. Какую базу выбрать

| База | Когда выбирать |
|---|---|
| `SQLITE` (по умолчанию) | Один сервер. Файл `vialogium.sqlite` лежит в папке мира, настраивать ничего не надо |
| `H2` | Один сервер, если SQLite почему-то не подходит. Тоже файл в папке мира |
| `POSTGRESQL` | Несколько серверов с общей историей (сеть Meridiana), большие объёмы. **Рекомендуется для сети** |
| `MARIADB` / `MYSQL` | То же, что PostgreSQL, если на хостинге уже есть MariaDB/MySQL |

Файловые базы (SQLite, H2) нельзя сделать общими для нескольких серверов. Для сети нужна серверная база – PostgreSQL, MariaDB или MySQL.

## 4. PostgreSQL (рекомендуется для сети)

### 4.1. Установка PostgreSQL

Linux (Debian/Ubuntu):

```bash
sudo apt install postgresql
sudo systemctl enable --now postgresql
```

Windows: установщик с https://www.postgresql.org/download/windows/, при установке запомните пароль пользователя `postgres`.

Docker:

```bash
docker run -d --name vialogium-db --restart unless-stopped \
  -e POSTGRES_USER=vialogium -e POSTGRES_PASSWORD=сложный_пароль -e POSTGRES_DB=vialogium \
  -p 127.0.0.1:5432:5432 -v vialogium-data:/var/lib/postgresql/data postgres:16
```

С Docker шаг 4.2 не нужен – пользователь и база уже созданы.

### 4.2. База и пользователь

```bash
sudo -u postgres psql
```

```sql
CREATE USER vialogium WITH PASSWORD 'сложный_пароль';
CREATE DATABASE vialogium OWNER vialogium ENCODING 'UTF8';
\q
```

Таблицы viaLogium создаст сам при первом запуске, выдавать дополнительные права не нужно: владелец базы может всё.

### 4.3. Если база на другой машине

По умолчанию PostgreSQL слушает только `localhost`. Если серверы Minecraft на другой машине:

1. В `postgresql.conf`: `listen_addresses = '*'` (или конкретный адрес).
2. В `pg_hba.conf` добавьте строку для каждого сервера Minecraft:
   ```
   host  vialogium  vialogium  10.0.0.5/32  scram-sha-256
   ```
3. `sudo systemctl restart postgresql`.
4. Откройте порт 5432 в файрволе **только** для адресов серверов Minecraft. Не открывайте базу в интернет.

### 4.4. Настройка viaLogium

В `config/vialogium.toml` на **каждом** сервере сети:

```toml
[database_extensions]
database = "POSTGRESQL"
url = "127.0.0.1:5432/vialogium"
username = "vialogium"
password = "env:VIALOGIUM_DB_PASSWORD"
properties = {}
maxPoolSize = 6
connectionTimeout = 10000
maxLifetime = 1800000
```

- `url` – `адрес:порт/имя_базы`, без `jdbc:postgresql://` (если всё-таки написать полный адрес, viaLogium его поймёт).
- `password` – можно написать пароль прямо, но лучше не хранить его в файле, который лежит в git. Варианты:
  - `env:ИМЯ` – взять из переменной окружения. Например, в `start.sh`: `export VIALOGIUM_DB_PASSWORD='сложный_пароль'`; в `.bat`: `set VIALOGIUM_DB_PASSWORD=сложный_пароль`.
  - `file:/путь/к/файлу` – взять первую строку файла, например `file:/etc/meridiana/vialogium.pass` (права `600`).
  - То же работает для `username`.
- `maxPoolSize` – соединений у **одного** сервера. Одно уходит на запись, остальные на поиск. Сложите `maxPoolSize` всех серверов сети: сумма должна быть меньше `max_connections` PostgreSQL (по умолчанию 100). Для сети из 4–5 серверов хватит 6.
- `properties` – дополнительные параметры драйвера, например `properties = { sslmode = "require" }` для подключения по SSL.

### 4.5. Проверка

1. Запустите сервер. В логе должно быть:
   ```
   [viaLogium] Database: PostgreSQL, 1 write thread, 5 read threads
   [viaLogium] Tables ready
   ```
2. Поставьте и сломайте блок, затем `/vl status` – `Logging: OK`, `records` растёт.
3. `/vl inspect` и клик по блоку – видно ваше действие.
4. В базе: `SELECT server, count(*) FROM actions GROUP BY server;` – у каждого сервера свои записи.

## 5. MariaDB / MySQL

```sql
CREATE DATABASE vialogium CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'vialogium'@'%' IDENTIFIED BY 'сложный_пароль';
GRANT ALL PRIVILEGES ON vialogium.* TO 'vialogium'@'%';
FLUSH PRIVILEGES;
```

```toml
[database_extensions]
database = "MARIADB"   # или "MYSQL" для MySQL 8
url = "127.0.0.1:3306/vialogium"
username = "vialogium"
password = "env:VIALOGIUM_DB_PASSWORD"
properties = {}
maxPoolSize = 6
connectionTimeout = 10000
maxLifetime = 1800000
```

Вместо `'%'` лучше указать адреса серверов Minecraft. У MariaDB/MySQL поле с данными предмета (`extra_data`) ограничено 64 КБ: очень большие содержимые (шалкер с книгами) не запишутся, в логе будет `Skipping action log: extra_data too large`. У PostgreSQL такого ограничения нет.

## 6. SQLite (один сервер)

Ничего настраивать не нужно. Полезное:

- `[database] location = "./vialogium-db"` – хранить файл не в папке мира, а отдельно (удобно, если мир часто пересоздаётся).
- Пока на сервере включён `/save-off` (например, во время бэкапа), запись в SQLite/H2 ставится на паузу, чтобы в бэкап попал целый файл. Действия копятся в очереди и записываются после `/save-on`. На PostgreSQL/MariaDB/MySQL пауз нет.

## 7. Сеть серверов (Velocity)

- Все серверы указывают на одну базу (`url` одинаковый).
- Имя сервера берётся из `server_id` viaPanel. Переопределить только для viaLogium: `[database] serverId = "arrakis"`.
- `/vl search`, `/vl inspect`, `/vl near` по умолчанию показывают только этот сервер. `server:arrakis` – другой сервер, `server:!lobby` – все, кроме лобби, `server:all` – вся сеть.
- `/vl rollback`, `/vl restore`, `/vl preview` работают только с этим сервером. Откатывайте на том сервере, где были действия.
- Одновременно на сервере идёт только один откат или восстановление: второй получит сообщение «уже идёт откат» и не начнётся.
- `autoPurgeDays` удаляет только записи своего сервера, поэтому срок хранения на каждом сервере свой.

## 8. Автоочистка старых записей

```toml
[database]
autoPurgeDays = 90            # хранить 90 дней; -1 = не удалять
autoPurgeIntervalHours = 24   # как часто проверять
```

Очистка идёт при запуске и затем раз в `autoPurgeIntervalHours` часов, небольшими порциями по 5000 записей, поэтому не блокирует базу и не мешает логированию. Ручная очистка: `/vl purge before:30d` и подтверждение ключом.

## 9. Обновление с 1.1.0

Просто замените jar. При первом запуске viaLogium сам:

- создаст индекс `actions_server_time_idx` (сервер + время) – на большой базе это может занять несколько минут, в логе будет `Creating index ...`;
- удалит лишние индексы `actions_xyz_idx` и `actions_server_idx`, которые замедляли каждую запись.

Если в сети один сервер уже обновлён, а другие ещё на 1.1.0 – это не страшно, формат данных не менялся.

## 10. Если что-то не работает

| Симптом | Что делать |
|---|---|
| Сервер не стартует, в логе `Unable to initialize database` | Проверьте `url`, логин, пароль. Проверка вручную: `psql -h 127.0.0.1 -U vialogium vialogium` |
| `environment variable ... is not set` | Переменная из `password = "env:..."` не задана в скрипте запуска |
| `Connection refused` | База не запущена или слушает другой адрес/порт (п. 4.3) |
| `/vl status` показывает `DEGRADED` | База недоступна. Действия копятся в памяти и запишутся, когда база вернётся; ничего не теряется, пока очередь не упрётся в `maxQueueSize` |
| `Dropping action the database rejects` | Одна конкретная запись не подходит базе (например, слишком длинное имя от мода). Отбрасывается только она, остальная пачка пишется |
| Поиск медленный | `/vl status` покажет число записей. Включите `autoPurgeDays`, сузьте поиск параметрами `range:`, `after:` |
| MySQL: `Public Key Retrieval is not allowed` | Включите SSL на MySQL или добавьте `properties = { allowPublicKeyRetrieval = "true" }` |
| Нужно быстро поднять сервер без базы | Временно `database = "SQLITE"` |
