package com.viameowts.vialogium.api;

import com.viameowts.vialogium.actions.ActionType;
import com.viameowts.vialogium.actionutils.ActionSearchParams;
import com.viameowts.vialogium.actionutils.SearchResults;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ViaLogiumApi {
    /**
     * Executes a search on the database
     * @param params The search parameters to filter results
     * @param page The page of results you want
     * @return A list of the results on the page along with the current page and total pages
     */
    CompletableFuture<SearchResults> searchActions(ActionSearchParams params, int page);

    /**
     * Counts <b>all</b> matching actions matching the parameters
     * @param params The search parameters to filter results
     * @return The number of matching actions
     */
    CompletableFuture<Long> countActions(ActionSearchParams params);

    /**
     * Executes a rollback for the matching actions
     * @param params The search parameters to filter results
     * @return A list of the actions that failed to rollback
     */
    CompletableFuture<List<ActionType>> rollbackActions(ActionSearchParams params);

    /**
     * Executes a restore (undoes a rollback) for the matching actions
     * @param params The search parameters to filter results
     * @return A list of the actions that failed to restore
     */
    CompletableFuture<List<ActionType>> restoreActions(ActionSearchParams params);

    /**
     * Logs an action to the database
     * @param action The action to log
     */
    void logAction(ActionType action);
}
