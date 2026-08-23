package com.viameowts.vialogium.commands

import com.viameowts.vialogium.api.ExtensionManager
import com.viameowts.vialogium.commands.subcommands.InspectCommand
import com.viameowts.vialogium.commands.subcommands.NearCommand
import com.viameowts.vialogium.commands.subcommands.PageCommand
import com.viameowts.vialogium.commands.subcommands.PlayerCommand
import com.viameowts.vialogium.commands.subcommands.PreviewCommand
import com.viameowts.vialogium.commands.subcommands.PurgeCommand
import com.viameowts.vialogium.commands.subcommands.RestoreCommand
import com.viameowts.vialogium.commands.subcommands.RollbackCommand
import com.viameowts.vialogium.commands.subcommands.SearchCommand
import com.viameowts.vialogium.commands.subcommands.StatusCommand
import com.viameowts.vialogium.commands.subcommands.TeleportCommand
import com.viameowts.vialogium.utility.BrigadierUtils
import com.viameowts.vialogium.utility.Dispatcher
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.Commands.literal

fun registerCommands(dispatcher: Dispatcher) {
    val rootNode =
        literal("vl").requires(Permissions.require("vialogium.commands.root", CommandConsts.PERMISSION_LEVEL))
            .build()

    dispatcher.root.addChild(rootNode)

    // Subcommands with aliases
    val inspectNode = InspectCommand.build()
    rootNode.addChild(inspectNode)
    BrigadierUtils.buildRedirect("i", inspectNode)?.let(rootNode::addChild)

    val searchNode = SearchCommand.build()
    rootNode.addChild(searchNode)
    BrigadierUtils.buildRedirect("s", searchNode)?.let(rootNode::addChild)

    val nearNode = NearCommand.build()
    rootNode.addChild(nearNode)
    BrigadierUtils.buildRedirect("n", nearNode)?.let(rootNode::addChild)

    val pageNode = PageCommand.build()
    rootNode.addChild(pageNode)
    BrigadierUtils.buildRedirect("pg", pageNode)?.let(rootNode::addChild)

    val rollbackNode = RollbackCommand.build()
    rootNode.addChild(rollbackNode)
    BrigadierUtils.buildRedirect("rb", rollbackNode)?.let(rootNode::addChild)

    val previewNode = PreviewCommand.build()
    rootNode.addChild(previewNode)
    BrigadierUtils.buildRedirect("pv", previewNode)?.let(rootNode::addChild)

    // Subcommands without aliases
    rootNode.addChild(RestoreCommand.build())
    rootNode.addChild(StatusCommand.build())
    rootNode.addChild(TeleportCommand.build())
    rootNode.addChild(PurgeCommand.build())
    rootNode.addChild(PlayerCommand.build())

    ExtensionManager.commands.forEach {
        it.registerSubcommands().forEach { command ->
            rootNode.addChild(command.build())
        }
    }

    // Root alias (same as /vl). Must be built AFTER all subcommands are added to rootNode:
    // buildRedirect clones the destination's children at build time, so building it earlier
    // would copy an empty command with no subcommands.
    BrigadierUtils.buildRedirect("vialogium", rootNode)?.let { dispatcher.root.addChild(it) }
}
