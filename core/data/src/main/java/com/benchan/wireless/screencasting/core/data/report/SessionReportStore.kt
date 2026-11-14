package com.benchan.wireless.screencasting.core.data.report

import android.content.Context
import com.benchan.wireless.screencasting.core.data.JsonFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 本机投屏报告存储：最多保留最近 [MAX_RECORDS] 次会话，只存本地（决策 #12）。 */
@Singleton
class SessionReportStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = JsonFileStore(
        File(context.noBackupFilesDir, "session_reports.json"),
        ListSerializer(SessionRecord.serializer()),
    ) { emptyList() }

    private val _records = MutableStateFlow(store.read())
    val records: StateFlow<List<SessionRecord>> = _records.asStateFlow()

    fun add(record: SessionRecord) {
        _records.value = store.update { (it + record).takeLast(MAX_RECORDS) }
    }

    fun clear() {
        store.clear()
        _records.value = emptyList()
    }

    companion object {
        const val MAX_RECORDS = 300
    }
}
