package io.legado.app.ui.main.discovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.appDb
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.widget.components.AppPageTopBar
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class DiscoverHistoryActivity : BaseComposeActivity() {
    private var entries by mutableStateOf<List<DiscoverHistoryEntry>>(emptyList())
    private var busy by mutableStateOf(false)
    private var failed by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh(change: suspend () -> Unit = {}) {
        if (busy) return
        busy = true
        failed = false
        lifecycleScope.launch {
            try {
                entries = withContext(IO) {
                    change()
                    mergeDiscoverHistory(
                        appDb.readRecordDao.allShow,
                        appDb.rssReadRecordDao.getRecords(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }

    private fun open(entry: DiscoverHistoryEntry) {
        when (entry) {
            is DiscoverHistoryEntry.Book -> lifecycleScope.launch {
                val bookName = entry.record.bookName
                val book = withContext(IO) {
                    appDb.bookDao.getBookByName(bookName)
                }
                if (book == null) SearchActivity.start(this@DiscoverHistoryActivity, bookName)
                else startActivityForBook(book)
            }
            is DiscoverHistoryEntry.Rss -> ReadRss.readRss(this, entry.record)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun ComposeContent() {
        var confirm by remember { mutableStateOf(false) }
        var target by remember { mutableStateOf<DiscoverHistoryEntry?>(null) }
        val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
        val canClear = entries.isNotEmpty() && !busy && !failed
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            AppPageTopBar(title = stringResource(R.string.read_record), onBackClick = ::finish) {
                TextButton(enabled = canClear, onClick = {
                    target = null
                    confirm = true
                }) {
                    Text(stringResource(R.string.clear))
                }
            }
            when {
                failed || entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        failed -> TextButton(onClick = { refresh() }) {
                            Text("${stringResource(R.string.load_failed)} · ${stringResource(R.string.retry)}")
                        }
                        busy -> CircularProgressIndicator()
                        else -> Text(stringResource(R.string.empty))
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.key }) { entry ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable(enabled = !busy) { open(entry) }
                                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val source = when (entry) {
                                    is DiscoverHistoryEntry.Book -> stringResource(R.string.book)
                                    is DiscoverHistoryEntry.Rss -> stringResource(R.string.rss_source)
                                }
                                val detail = when (entry) {
                                    is DiscoverHistoryEntry.Book -> ""
                                    is DiscoverHistoryEntry.Rss -> entry.record.origin
                                }
                                Text(
                                    listOfNotNull(source, detail.takeIf { it.isNotBlank() },
                                        entry.time?.let { dateFormat.format(Date(it)) }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(enabled = !busy, onClick = { target = entry; confirm = true }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.delete))
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        if (confirm) {
            val entry = target
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text(stringResource(if (entry == null) R.string.clear else R.string.delete)) },
                text = {
                    Text(
                        if (entry == null) stringResource(R.string.sure_del_any, stringResource(R.string.read_record))
                        else stringResource(R.string.sure_del_any, entry.title),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = false
                        refresh {
                            when (entry) {
                                is DiscoverHistoryEntry.Book -> appDb.readRecordDao.deleteByName(entry.record.bookName)
                                is DiscoverHistoryEntry.Rss -> appDb.rssReadRecordDao.deleteRecord(entry.record.origin, entry.record.record)
                                null -> appDb.withTransaction {
                                    appDb.readRecordDao.clear()
                                    appDb.rssReadRecordDao.deleteAllRecord()
                                }
                            }
                        }
                    }) { Text(stringResource(if (entry == null) R.string.clear else R.string.delete)) }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
