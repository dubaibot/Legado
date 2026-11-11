package com.lanyue.reader.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lanyue.reader.BuildConfig
import com.lanyue.reader.R
import com.lanyue.reader.core.shell.PlaceholderModel
import com.lanyue.reader.core.shell.ShellSpec

/**
 * 启动页：T03 要求的 Compose 空壳。
 *
 * 只有标题、说明、版本三行，没有任何书架/发现/播放入口——这些属于后续轮次。
 * 在这里放业务 UI 会让人误以为「功能已开工」，也会让后续拆分导航时反复搬迁。
 *
 * 判断逻辑一律委托给 [ShellSpec]（纯函数、有单测覆盖），本类只负责接线与渲染：
 * 一旦把 `if (savedTitle.isNullOrBlank())` 之类的分支写进 Composable，
 * 它就只能靠真机验证，而这恰恰是「切后台回来标题变空」这类问题的藏身处。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val savedTitle = rememberSaveable { mutableStateOf<String?>(null) }
                    PlaceholderScreen(
                        model = ShellSpec.buildPlaceholder(
                            appName = stringResource(R.string.app_name),
                            notice = stringResource(R.string.onboarding_note),
                            versionName = BuildConfig.VERSION_NAME,
                            // 保存的标题可能来自上一个进程或更早版本，合法性由 ShellSpec 判定
                            restoredTitle = savedTitle.value
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(model: PlaceholderModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = model.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        if (model.notice.isNotEmpty()) {
            Text(
                text = model.notice,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        Text(
            text = model.versionLabel,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 32.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderPreview() {
    MaterialTheme {
        PlaceholderScreen(
            model = ShellSpec.buildPlaceholder(
                appName = "览阅",
                notice = "本应用不含任何内置源与内容入口。",
                versionName = "0.1.0"
            )
        )
    }
}
