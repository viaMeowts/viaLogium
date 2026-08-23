package com.viameowts.vialogium.actionutils

import com.viameowts.vialogium.actions.ActionType

data class SearchResults(
    val actions: List<ActionType>,
    val searchParams: ActionSearchParams,
    val page: Int,
    val pages: Int,
)
