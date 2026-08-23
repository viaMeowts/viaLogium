# viaLogium — краткая памятка (Discord)

## ⚡ viaLogium в 10 строк
- Все команды: `/vl ...`
- Быстрый просмотр по блоку: `/vl i` (или `/vl inspect`), затем клик по блоку
- Поиск: `/vl search <параметры>`
- Страницы: `/vl page <номер>`
- Поиск рядом: `/vl near`
- Безопасный откат: `/vl preview rollback <параметры>` → `/vl preview apply`
- Отмена предпросмотра: `/vl preview cancel`
- Возврат после отката: `/vl restore <параметры>`
- Полезно: `/vl status`, `/vl player <ник>`
- Время в фильтрах: `after:30m`, `after:2h`, `before:1d`

## ✅ Готовые примеры
- Кто ломал рядом: `/vl search action:block-break range:10 after:1h`
- Действия игрока: `/vl search source:PlayerName after:12h`
- Только неоткаченные: `/vl search source:PlayerName after:12h rolledback:false`

## ⚠️ Если ошибка
- `No cached search` → сначала `/vl search ...`, потом `/vl page ...`
- `No results` → увеличьте `range` или `after`
- Нет прав → обратиться к администрации
