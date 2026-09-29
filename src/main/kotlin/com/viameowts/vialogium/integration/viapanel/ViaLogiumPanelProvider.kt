package com.viameowts.vialogium.integration.viapanel

import com.viameowts.viapanel.api.ViaPanelApi
import com.viameowts.viapanel.api.ViaPanelProvider
import com.viameowts.viapanel.api.ViaPanelSection
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor

private const val ADMIN_PERMISSION_LEVEL = 3

object ViaLogiumPanelProvider : ViaPanelProvider {
    private const val HEADER = "#FFC64C"
    private const val MAIN_TEXT = "#D9D0D5"
    private const val HIGHLIGHT = "#FCDE9D"
    private const val SUCCESS = "#98FB98"
    private const val ERROR = "#FF5555"

    private const val ON_BULLET = "▸"
    private const val OFF_BULLET = "•"

    @Volatile
    private var panelLanguage: String = "en"

    private fun colorStyle(hex: String): Style = Style.EMPTY.withColor(TextColor.parseColor(hex).result().orElse(null))

    private fun styled(text: String, hex: String): MutableComponent = Component.literal(text).setStyle(colorStyle(hex))

    private fun isRu(): Boolean {
        val global = runCatching { ViaPanelApi.getGlobalLanguage() }.getOrNull()
        val effective = global ?: panelLanguage
        return effective.equals("ru", ignoreCase = true)
    }

    private fun localize(en: String, ru: String): String = if (isRu()) ru else en

    override fun modId(): String = "vialogium"

    override fun modDisplayName(): Component = styled("viaLogium", HIGHLIGHT)

    override fun panelTitle(): Component = styled(localize("viaLogium Settings", "Настройки viaLogium"), HEADER)

    override fun hasPermission(source: CommandSourceStack): Boolean =
        Permissions.check(source, "vialogium.viapanel", ADMIN_PERMISSION_LEVEL)

    override fun configClass(): Class<*> = ViaLogiumPanelConfig::class.java

    override fun configInstance(): Any = ViaLogiumPanelConfig

    override fun sections(): List<ViaPanelSection> = listOf(
        ViaPanelSection(
            "search",
            styled(localize("Search", "Поиск"), HEADER),
            listOf("pageSize", "purgePermissionLevel", "maxRange", "nearRadius", "timeZone"),
        ),
        ViaPanelSection(
            "database",
            styled(localize("Database Queue", "Очередь базы данных"), HEADER),
            listOf(
                "queueTimeoutMin",
                "queueCheckDelaySec",
                "autoPurgeDays",
                "batchSize",
                "batchDelay",
                "criticalQueueSize",
                "criticalBatchSize",
                "criticalBatchDelay",
                "emergencyQueueSize",
                "emergencyBatchSize",
                "emergencyBatchDelay",
                "adaptiveQueueTuning",
                "dropNonEssentialInCritical",
                "criticalExplosionKeepEvery",
                "emergencyExplosionKeepEvery",
                "emergencyDropNonPlayerBlockActions",
                "sqliteUseWal",
                "sqliteSynchronousNormal",
                "sqliteTempStoreMemory",
                "sqliteCacheSizeKb",
                "sqliteMmapSizeMb",
                "sqliteBusyTimeoutMs",
                "networkMaxPages",
                "rollbackActionsPerTick",
                "previewActionsPerTick",
            ),
        ),
        ViaPanelSection(
            "networking",
            styled(localize("Networking", "Сеть"), HEADER),
            listOf("networking"),
        ),
        ViaPanelSection(
            "colors",
            styled(localize("Colors", "Цвета"), HEADER),
            listOf(
                "primary",
                "primaryVariant",
                "secondary",
                "secondaryVariant",
                "light",
                "actionPositive",
                "actionNegative",
            ),
        ),
    )

    override fun fieldDisplayName(fieldName: String): Component = styled(
        when (fieldName) {
            "pageSize" -> localize("Page Size", "Размер страницы")

            "purgePermissionLevel" -> localize("Purge Permission Level", "Уровень прав для purge")

            "maxRange" -> localize("Max Range", "Максимальный радиус")

            "nearRadius" -> localize("Near Radius", "Радиус near")

            "timeZone" -> localize("Time Zone", "Часовой пояс")

            "queueTimeoutMin" -> localize("Queue Timeout (min)", "Таймаут очереди (мин)")

            "queueCheckDelaySec" -> localize("Queue Check Delay (sec)", "Пауза проверки очереди (сек)")

            "autoPurgeDays" -> localize("Auto Purge Days", "Дней до авто-purge")

            "batchSize" -> localize("Batch Size", "Размер батча")

            "batchDelay" -> localize("Batch Delay", "Задержка батча")

            "criticalQueueSize" -> localize("Critical Queue Size", "Критический размер очереди")

            "criticalBatchSize" -> localize("Critical Batch Size", "Критический размер батча")

            "criticalBatchDelay" -> localize("Critical Batch Delay", "Критическая задержка батча")

            "emergencyQueueSize" -> localize("Emergency Queue Size", "Аварийный размер очереди")

            "emergencyBatchSize" -> localize("Emergency Batch Size", "Аварийный размер батча")

            "emergencyBatchDelay" -> localize("Emergency Batch Delay", "Аварийная задержка батча")

            "adaptiveQueueTuning" -> localize("Adaptive Queue Tuning", "Адаптивная настройка очереди")

            "dropNonEssentialInCritical" -> localize(
                "Drop Non-Essential In Critical",
                "Отбрасывать второстепенное в критическом режиме",
            )

            "criticalExplosionKeepEvery" -> localize(
                "Critical Explosion Keep Every",
                "В критическом: сохранять каждый N взрыв",
            )

            "emergencyExplosionKeepEvery" -> localize(
                "Emergency Explosion Keep Every",
                "В аварийном: сохранять каждый N взрыв",
            )

            "emergencyDropNonPlayerBlockActions" -> localize(
                "Emergency Drop Non-Player Blocks",
                "В аварийном: сбрасывать неигровые блок-действия",
            )

            "sqliteUseWal" -> localize("SQLite WAL", "SQLite WAL")

            "sqliteSynchronousNormal" -> localize("SQLite Sync Normal", "SQLite sync NORMAL")

            "sqliteTempStoreMemory" -> localize("SQLite Temp Store Memory", "SQLite temp_store=MEMORY")

            "sqliteCacheSizeKb" -> localize("SQLite Cache Size (KB)", "Размер кэша SQLite (КБ)")

            "sqliteMmapSizeMb" -> localize("SQLite mmap Size (MB)", "Размер mmap SQLite (МБ)")

            "sqliteBusyTimeoutMs" -> localize("SQLite Busy Timeout (ms)", "Таймаут блокировки SQLite (мс)")

            "networkMaxPages" -> localize("Network Max Pages", "Макс. страниц сети")

            "rollbackActionsPerTick" -> localize("Rollback Actions Per Tick", "Rollback действий за тик")

            "previewActionsPerTick" -> localize("Preview Actions Per Tick", "Preview действий за тик")

            "networking" -> localize("Networking", "Сеть")

            "primary" -> localize("Primary", "Основной")

            "primaryVariant" -> localize("Primary Variant", "Вариант основного")

            "secondary" -> localize("Secondary", "Вторичный")

            "secondaryVariant" -> localize("Secondary Variant", "Вариант вторичного")

            "light" -> localize("Light", "Светлый")

            "actionPositive" -> localize("Action Positive", "Позитивное действие")

            "actionNegative" -> localize("Action Negative", "Негативное действие")

            else -> fieldName
        },
        HIGHLIGHT,
    )

    override fun fieldDescription(fieldName: String): Component = styled(
        when (fieldName) {
            "networkMaxPages" -> localize("Max pages sent for network responses", "Максимум страниц в сетевом ответе")

            "rollbackActionsPerTick" -> localize("Rollback throughput per server tick", "Скорость rollback за тик")

            "previewActionsPerTick" -> localize("Preview throughput per server tick", "Скорость preview за тик")

            "sqliteUseWal" -> localize("Enable WAL mode for SQLite", "Включить WAL для SQLite")

            "sqliteBusyTimeoutMs" -> localize(
                "SQLite lock wait timeout in milliseconds",
                "Таймаут ожидания блокировки SQLite в миллисекундах",
            )

            else -> localize("Editable via viaPanel", "Изменяется через viaPanel")
        },
        MAIN_TEXT,
    )

    override fun toggleHintText(): Component =
        styled(localize("$ON_BULLET toggle boolean", "$ON_BULLET переключить boolean"), HIGHLIGHT)
            .append("  ")
            .append(styled(localize("$OFF_BULLET off", "$OFF_BULLET выкл"), MAIN_TEXT))

    override fun editHintText(): Component = styled(
        localize("Set string/number/bool value", "Задайте значение string/number/bool"),
        MAIN_TEXT,
    )

    override fun savedSuffixText(): Component = styled(localize(" saved", " сохранено"), SUCCESS)

    override fun fieldNotBooleanText(): Component = styled(localize("Field is not boolean", "Поле не boolean"), ERROR)

    override fun unknownFieldText(): Component = styled(localize("Unknown field", "Неизвестное поле"), ERROR)

    override fun invalidNumberText(): Component = styled(localize("Invalid number", "Некорректное число"), ERROR)

    override fun applyGlobalLanguage(languageCode: String, source: CommandSourceStack) {
        panelLanguage = if (languageCode.equals("ru", ignoreCase = true)) "ru" else "en"
    }

    override fun reload(source: CommandSourceStack) {
        ViaLogiumPanelConfig.reload()
    }

    override fun reloadDoneText(): Component = styled(
        localize(
            "viaLogium config reloaded. Networking packet registration requires restart.",
            "Конфиг viaLogium перезагружен. Для регистрации сетевых пакетов нужен перезапуск.",
        ),
        SUCCESS,
    )
}
