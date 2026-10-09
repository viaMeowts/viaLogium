package com.viameowts.vialogium.commands.subcommands

import com.mojang.brigadier.arguments.StringArgumentType
import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actionutils.ActionSearchParams
import com.viameowts.vialogium.commands.BuildableCommand
import com.viameowts.vialogium.commands.CommandConsts
import com.viameowts.vialogium.commands.arguments.SearchParamArgument
import com.viameowts.vialogium.config.SearchSpec
import com.viameowts.vialogium.config.config
import com.viameowts.vialogium.database.DatabaseManager
import com.viameowts.vialogium.utility.Context
import com.viameowts.vialogium.utility.LiteralNode
import com.viameowts.vialogium.utility.MeridianaAudit
import com.viameowts.vialogium.utility.TextColorPallet
import com.viameowts.vialogium.utility.literal
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.Commands
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

object PurgeCommand : BuildableCommand {
    private const val CONFIRM_KEY_LENGTH = 6
    private const val CONFIRM_TTL_SECONDS = 120L
    private const val CONFIRM_ARG = "confirmKey"
    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val random = SecureRandom()

    private data class PendingPurge(val params: ActionSearchParams, val key: String, val expiresAt: Instant)

    private val pendingPurges = ConcurrentHashMap<String, PendingPurge>()

    override fun build(): LiteralNode = literal("purge")
        .requires(Permissions.require("vialogium.commands.purge", config[SearchSpec.purgePermissionLevel]))
        .then(
            SearchParamArgument.argument(CommandConsts.PARAMS).executes {
                requestPurgeConfirm(it, SearchParamArgument.get(it, CommandConsts.PARAMS))
            },
        )
        .then(
            Commands.literal("--confirm")
                .then(
                    Commands.argument(CONFIRM_ARG, StringArgumentType.word()).executes {
                        confirmPurge(it, StringArgumentType.getString(it, CONFIRM_ARG))
                    },
                ),
        )
        .build()

    private fun requestPurgeConfirm(ctx: Context, params: ActionSearchParams): Int {
        val source = ctx.source
        val sourceKey = source.textName
        val key = generateConfirmKey()
        val expiresAt = Instant.now().plusSeconds(CONFIRM_TTL_SECONDS)

        pendingPurges[sourceKey] = PendingPurge(params, key, expiresAt)

        source.sendFailure(
            Component.translatable(
                "text.vialogium.purge.confirm_required",
                key.literal().setStyle(TextColorPallet.primaryVariant),
                CONFIRM_TTL_SECONDS.toString().literal().setStyle(TextColorPallet.secondaryVariant),
            ).setStyle(TextColorPallet.secondary),
        )
        return 1
    }

    private fun confirmPurge(ctx: Context, key: String): Int {
        val source = ctx.source
        val sourceKey = source.textName
        val pending = pendingPurges[sourceKey]

        if (pending == null) {
            source.sendFailure(
                Component.translatable("error.vialogium.purge.no_pending").setStyle(TextColorPallet.actionNegative),
            )
            return 0
        }

        if (Instant.now().isAfter(pending.expiresAt)) {
            pendingPurges.remove(sourceKey)
            source.sendFailure(
                Component.translatable("error.vialogium.purge.expired").setStyle(TextColorPallet.actionNegative),
            )
            return 0
        }

        if (!pending.key.equals(key, ignoreCase = true)) {
            source.sendFailure(
                Component.translatable("error.vialogium.purge.invalid_key").setStyle(TextColorPallet.actionNegative),
            )
            return 0
        }

        pendingPurges.remove(sourceKey)
        source.sendSuccess(
            { Component.translatable("text.vialogium.purge.starting").setStyle(TextColorPallet.secondary) },
            true,
        )
        ViaLogium.launch {
            val removed = DatabaseManager.purgeActions(pending.params)
            MeridianaAudit.event(
                source.textName,
                "WARN",
                "/vl purge: безвозвратно удалено записей из базы: $removed",
            )
            source.sendSuccess(
                { Component.translatable("text.vialogium.purge.complete").setStyle(TextColorPallet.secondary) },
                true,
            )
        }
        return 1
    }

    private fun generateConfirmKey(): String {
        val builder = StringBuilder(CONFIRM_KEY_LENGTH)
        repeat(CONFIRM_KEY_LENGTH) {
            builder.append(CHARS[random.nextInt(CHARS.length)])
        }
        return builder.toString()
    }
}
