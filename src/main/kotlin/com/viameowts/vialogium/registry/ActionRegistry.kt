package com.viameowts.vialogium.registry

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actions.*
import com.viameowts.vialogium.database.DatabaseManager
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectSet
import kotlinx.coroutines.launch
import java.util.function.Supplier

private const val MAX_LENGTH = 16

object ActionRegistry {
    private val actionTypes = Object2ObjectOpenHashMap<String, Supplier<ActionType>>()

    // TODO make this better
    // TODO create some sort of action identifier with grouping
    fun registerActionType(supplier: Supplier<ActionType>) {
        val id = supplier.get().identifier
        require(id.length <= MAX_LENGTH) {
            "Action identifier '$id' exceeds max length $MAX_LENGTH"
        }

        require(actionTypes.putIfAbsent(id, supplier) == null) {
            "Action identifier '$id' is already registered"
        }
        ViaLogium.launch {
            DatabaseManager.registerActionType(id)
        }
    }

    fun registerDefaultTypes() {
        registerActionType { BlockBreakActionType() }
        registerActionType { BlockPlaceActionType() }
        registerActionType { BlockChangeActionType() }
        registerActionType { ItemInsertActionType() }
        registerActionType { ItemRemoveActionType() }
        registerActionType { ItemPickUpActionType() }
        registerActionType { ItemDropActionType() }
        registerActionType { EntityKillActionType() }
        registerActionType { PlayerKillActionType() }
        registerActionType { PlayerJoinActionType() }
        registerActionType { PlayerLeaveActionType() }
        registerActionType { EntityChangeActionType() }
        registerActionType { ItemFrameInsertActionType() }
        registerActionType { ItemFrameRemoveActionType() }
        registerActionType { EntityMountActionType() }
        registerActionType { EntityDismountActionType() }
        registerActionType { TotemPopActionType() }
        registerActionType { VillagerTradeActionType() }
    }

    fun getType(id: String) = actionTypes[id]

    fun getTypes(): ObjectSet<String> = actionTypes.keys
}
