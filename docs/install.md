# Installation

1. Set up a [Fabric Server](https://fabricmc.net/wiki/tutorial:installing_minecraft_fabric_server) for 1.17
2. Install [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api)
3. Install [Fabric Language Kotlin](https://www.curseforge.com/minecraft/mc-mods/fabric-language-kotlin/)
4. Install [ViaLogium](https://www.curseforge.com/minecraft/mc-mods/vialogium)
5. Run the server
6. Adjust [config](config.md) as needed

Run into any issues? Join our [Discord](https://discord.gg/UxHnDWr) for support!

## Other Databases
ViaLogium supports other databases like MySQL, PostgreSQL and H2 with the help of the [ViaLogium Databases](https://www.curseforge.com/minecraft/mc-mods/vialogium-databases) extension.

### H2
H2 is another flat-file database like the default sqlite that may yield faster results but is more experimental.

Add the following to the bottom of your ViaLogium config file:

```toml
[database_extensions]
database = "H2"
```

### MySQL
MySQL requires running a separate MySQL database and more setup than just plug and play SQLite, but can support much larger databases at faster speeds.
It also supports MySQL based databases like MariaDB.

Add the following to the bottom of your ViaLogium config file:

```toml
[database_extensions]
database = "MYSQL"
url = ""
username = ""
password = ""
properties = []
```

`url`: Must be URL of database with `/<database_name>` appended. An example URL would be `localhost/vialogium`. You can optionally add port information such as `localhost:3000/vialogium`

### MariaDB (quick start)

MariaDB is the easiest external SQL backend to run for viaLogium.

1. Create DB and user:

```sql
CREATE DATABASE vialogium CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'vialogium'@'%' IDENTIFIED BY 'change_me';
GRANT ALL PRIVILEGES ON vialogium.* TO 'vialogium'@'%';
FLUSH PRIVILEGES;
```

2. Configure viaLogium:

```toml
[database_extensions]
database = "MARIADB"
url = "127.0.0.1:3306/vialogium"
username = "vialogium"
password = "change_me"
properties = {}
maxPoolSize = 10
connectionTimeout = 60000
maxLifetime = 1800000
```

3. Restart the server.

Notes:
- `url` format is `host:port/database`.
- For Docker/database on another machine, open port `3306` and allow the server IP/user.
- If you need immediate recovery from DB issues, set `database = "SQLITE"` temporarily.

### PostgreSQL
PostgreSQL requires running a separate PostgreSQL database and more setup than just plug and play SQLite, but can support much larger databases at faster speeds. It is more experimental the MySQL but may yield faster performance.

Add the following to the bottom of your ViaLogium config file:

```toml
[database_extensions]
database = "POSTGRESQL"
url = ""
username = ""
password = ""
properties = []
```

`url`: Must be URL of database with `/<database_name>` appended. An example URL would be `localhost/vialogium`. You can optionally add port information such as `localhost:3000/vialogium`

## Connector properties

For some databases, such as MySQL/MariaDB, you can provide connector properties via key-value map.

```toml
properties = { serverTimezone = "UTC", useServerPrepStmts = "true" }
```
