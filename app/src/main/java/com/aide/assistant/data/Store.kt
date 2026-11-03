package com.aide.assistant.data

import com.aide.core.model.AppData
import com.aide.core.store.JsonFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** App-wide data, observable from Compose. Backed by a JSON file in the app's private storage. */
class Store(file: File) {
    private val db = JsonFileStore(file)
    private val _state = MutableStateFlow(db.snapshot())
    val state: StateFlow<AppData> = _state

    init {
        db.addListener { _state.value = it }
    }

    val data: AppData get() = db.snapshot()

    fun update(transform: (AppData) -> AppData): AppData = db.update(transform)
}
