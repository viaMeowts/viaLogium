# viaLogium — полный справочник параметров (для Discord)

Готовый текст для вставки в Discord.

---

## 📌 Общий формат

Команда поиска/отката:
`/vl <search|rollback|restore|preview rollback|preview restore> <параметры>`

Параметры пишутся так:
`ключ:значение ключ:значение ...`

Пример:
`/vl search action:block-break source:PlayerName range:10 after:2h`

---

## ✅ Все поддерживаемые ключи параметров

1. `action:<id>`
2. `source:<источник>`
3. `range:<число|@global>`
4. `object:<id|#tag>`
5. `world:<dimension_id>`
6. `before:<время>`
7. `after:<время>`
8. `rolledback:true|false`

---

## 🔎 Параметр `action:` — все ID действий

- `block-break`
- `block-place`
- `block-change`
- `item-insert`
- `item-remove`
- `item-pick-up`
- `item-drop`
- `entity-kill`
- `player-kill`
- `player-join`
- `player-leave`
- `entity-change`
- `frame-insert`
- `frame-remove`
- `entity-mount`
- `entity-dismount`
- `totem-pop`
- `villager-trade`

Пример:
`/vl search action:entity-kill after:6h`

---

## 👤 Параметр `source:` — все типы источников

### Источник-игрок
- `source:PlayerName`

### Источник-система/механика (через `@`)
- `@fire`
- `@gravity`
- `@explosion`
- `@player`
- `@broke`
- `@decay`
- `@dry`
- `@wet`
- `@melt`
- `@frost_walker`
- `@redstone`
- `@fluid`
- `@fill`
- `@drain`
- `@drip`
- `@snow`
- `@remove`
- `@trample`
- `@extinguish`
- `@insert`
- `@interact`
- `@consume`
- `@grow`
- `@snow_golem`
- `@sponge`
- `@portal`
- `@command`
- `@projectile`
- `@vehicle`
- `@equip`
- `@rotate`
- `@shear`
- `@dye`
- `@statue`
- `@conversion`
- `@copper_golem`
- `@wax`
- `@reanimate`
- `@hopper`
- `@hopper_minecart`
- `@unknown`

Пример:
`/vl search source:@hopper after:2h range:12`

---

## 📦 Параметр `object:`

Можно указывать:
- конкретный ID: `object:minecraft:chest`
- тег: `object:#minecraft:logs`

Работает для блоков/предметов/сущностей.

---

## 🌍 Параметр `world:`

ID измерения, например:
- `world:minecraft:overworld`
- `world:minecraft:the_nether`
- `world:minecraft:the_end`

---

## 📏 Параметр `range:`

- `range:<число>` — радиус вокруг вашей позиции
- `range:@global` — поиск по всему серверу

Примеры:
- `range:5`
- `range:30`
- `range:@global`

---

## ⏱ Параметры времени `after:` и `before:`

Поддерживаемые суффиксы:
- `s` — секунды
- `m` — минуты
- `h` — часы
- `d` — дни
- `w` — недели

Можно комбинировать:
- `after:30m`
- `after:2h30m`
- `before:1d`

---

## ♻️ Параметр `rolledback:`

- `rolledback:true` — только уже откатанные
- `rolledback:false` — только не откатанные

---

## ❗ Отрицание (`!`) — где работает

Поддерживается для:
- `action`
- `source`
- `object`
- `world`

Примеры:
- `action:!block-place`
- `source:!PlayerName`
- `source:!@hopper`
- `object:!minecraft:stone`
- `world:!minecraft:the_end`

---

## 🧪 Готовые «полные» примеры

1) Кто ломал/менял рядом за 2 часа, кроме воронок:
`/vl search action:block-break action:block-change source:!@hopper source:!@hopper_minecart range:20 after:2h`

2) Действия игрока в аду за сутки:
`/vl search source:PlayerName world:minecraft:the_nether after:1d`

3) По сундукам по всему серверу (только неоткатанные):
`/vl search object:minecraft:chest range:@global rolledback:false`

4) Откат только по конкретному игроку в зоне:
`/vl preview rollback source:PlayerName range:15 after:45m`

---

Если нужно, сделаю ещё версию «cheat sheet» в одну таблицу без пояснений (только ключ → значения → пример).