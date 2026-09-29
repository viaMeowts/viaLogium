package com.viameowts.vialogium.utility

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actionutils.SearchResults
import com.viameowts.vialogium.config.SearchSpec
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.logWarn
import com.viameowts.vialogium.network.Networking.hasNetworking
import com.viameowts.vialogium.network.packet.action.ActionS2CPacket
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.commands.CommandSourceStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.time.ExperimentalTime
import kotlin.time.toKotlinDuration

object MessageUtils {
    val pageChangeAction: Identifier = ViaLogium.identifier("page-change")
    val teleportAction: Identifier = ViaLogium.identifier("teleport")

    @Suppress("TooGenericExceptionCaught") // a failure here must not take the server down
    @OptIn(ExperimentalTime::class)
    suspend fun sendSearchResults(source: CommandSourceStack, results: SearchResults, header: Component) {
        // A player with a ViaLogium compatible client gets results as action packets rather than chat messages
        if (source.hasPlayer() && source.playerOrException.hasNetworking()) {
            for (n in results.page..results.pages) {
                val networkResults = DatabaseManager.searchActions(results.searchParams, n)
                networkResults.actions.forEach {
                    ServerPlayNetworking.send(source.playerOrException, ActionS2CPacket(it))
                }
            }
            return
        }

        source.sendSystemMessage(header)

        results.actions.forEach { actionType ->
            try {
                source.sendSystemMessage(actionType.getMessage(source))
            } catch (t: Throwable) {
                logWarn(
                    "Skipping invalid action message for action id=${actionType.id}, " +
                        "identifier=${actionType.identifier}",
                    t,
                )
            }
        }

        source.sendSystemMessage(
            Component.translatable(
                "text.vialogium.footer.search",
                Component.translatable(
                    "text.vialogium.footer.page_backward",
                ).setStyle(TextColorPallet.primaryVariant).withStyle {
                    if (results.page > 1) {
                        val tag = CompoundTag()
                        tag.putInt("page", results.page - 1)
                        it.withHoverEvent(
                            HoverEvent.ShowText(
                                Component.translatable("text.vialogium.footer.page_backward.hover"),
                            ),
                        ).withClickEvent(
                            ClickEvent.Custom(pageChangeAction, Optional.of(tag)),
                        )
                    } else {
                        Style.EMPTY
                    }
                },
                results.page.toString().literal().setStyle(TextColorPallet.primaryVariant),
                results.pages.toString().literal().setStyle(TextColorPallet.primaryVariant),
                Component.translatable(
                    "text.vialogium.footer.page_forward",
                ).setStyle(TextColorPallet.primaryVariant).withStyle {
                    if (results.page < results.pages) {
                        val tag = CompoundTag()
                        tag.putInt("page", results.page + 1)
                        it.withHoverEvent(
                            HoverEvent.ShowText(
                                Component.translatable("text.vialogium.footer.page_forward.hover"),
                            ),
                        ).withClickEvent(
                            ClickEvent.Custom(pageChangeAction, Optional.of(tag)),
                        )
                    } else {
                        Style.EMPTY
                    }
                },
            ).setStyle(TextColorPallet.primary),
        )
    }

    fun sendPlayerMessage(source: CommandSourceStack, results: List<PlayerResult>) {
        if (results.isEmpty()) {
            source.sendSystemMessage(
                "error.vialogium.command.no_results".translate().setStyle(TextColorPallet.actionNegative),
            )
            return
        }
        source.sendSystemMessage("text.vialogium.header.search".translate().setStyle(TextColorPallet.secondary))
        results.forEach {
            source.sendSystemMessage(it.toText())
        }
    }

    fun warnBusy(source: CommandSourceStack) {
//        if (DatabaseManager.dbMutex.isLocked) { //TODO
//            source.sendFeedback(
//                {
//                    Text.translatable(
//                        "text.vialogium.database.busy"
//                    ).setStyle(TextColorPallet.primary)
//                },
//                false
//            )
//        }
    }

    fun instantToText(time: Instant): MutableComponent {
        val duration = Duration.between(time, Instant.now()).toKotlinDuration()
        val text: MutableComponent = "".literal()

        duration.toComponents { days, hours, minutes, seconds, _ ->

            when {
                days > 0 -> text.append(days.toString()).append("d")
                hours > 0 -> text.append(hours.toString()).append("h")
                minutes > 0 -> text.append(minutes.toString()).append("m")
                else -> text.append(seconds.toString()).append("s")
            }
        }

        val message = Component.translatable("text.vialogium.action_message.time_diff", text)

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        val timeMessage = formatter.format(time.atZone(ViaLogium.config[SearchSpec.timeZone])).literal()

        message.withStyle {
            it.withHoverEvent(
                HoverEvent.ShowText(
                    timeMessage,
                ),
            )
        }
        return message
    }
}
