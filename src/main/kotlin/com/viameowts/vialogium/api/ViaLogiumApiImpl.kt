package com.viameowts.vialogium.api

import com.viameowts.vialogium.ViaLogium
import com.viameowts.vialogium.actions.ActionType
import com.viameowts.vialogium.actionutils.ActionSearchParams
import com.viameowts.vialogium.actionutils.SearchResults
import com.viameowts.vialogium.database.ActionQueueService
import com.viameowts.vialogium.database.DatabaseManager
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture

internal object ViaLogiumApiImpl : ViaLogiumApi {
    override fun searchActions(params: ActionSearchParams, page: Int): CompletableFuture<SearchResults> =
        ViaLogium.future { DatabaseManager.searchActions(params, page) }

    override fun countActions(params: ActionSearchParams): CompletableFuture<Long> =
        ViaLogium.future { DatabaseManager.countActions(params) }

    override fun rollbackActions(params: ActionSearchParams): CompletableFuture<List<ActionType>> =
        ViaLogium.future { DatabaseManager.rollbackActions(params) }

    override fun restoreActions(params: ActionSearchParams): CompletableFuture<List<ActionType>> =
        ViaLogium.future { DatabaseManager.restoreActions(params) }

    override fun logAction(action: ActionType) {
        ActionQueueService.addToQueue(action)
    }
}
