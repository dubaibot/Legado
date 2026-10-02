package io.legado.app.ui.main.explore

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
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
}
