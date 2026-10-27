package io.legado.app.ui.main.explore

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.source.SourceHelp
import kotlinx.coroutines.flow.first

class ExploreNewViewModel(application: Application) : BaseViewModel(application) {

    val sourcesLiveData = MutableLiveData<List<BookSourcePart>>()

    fun loadSources(group: String) {
        execute {
            val list = appDb.bookSourceDao.flowGroupExplore(group).first()
            sourcesLiveData.postValue(list)
        }.onError {
            AppLog.put("新版发现加载书源失败\n${it.localizedMessage}", it)
        }
    }

    fun topSource(bookSource: BookSourcePart) {
        execute {
            val minXh = appDb.bookSourceDao.minOrder
            bookSource.customOrder = minXh - 1
            appDb.bookSourceDao.upOrder(bookSource)
        }
    }

    fun toBottom(bookSource: BookSourcePart) {
        execute {
            val maxXh = appDb.bookSourceDao.maxOrder
            bookSource.customOrder = maxXh + 1
            appDb.bookSourceDao.upOrder(bookSource)
        }
    }

    fun deleteSource(source: BookSourcePart) {
        execute {
            SourceHelp.deleteBookSource(source.bookSourceUrl)
        }
    }

}
