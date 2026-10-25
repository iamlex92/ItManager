package com.itmanager.app.ui.components

/** Load state shared by every screen. Content stays visible while a refresh runs. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
    data class Content<T>(val data: T, val isRefreshing: Boolean = false) : UiState<T>
}
