package com.lanyue.reader.ui

import android.os.Bundle
import com.lanyue.reader.R
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainScreen()
        }
    }
}

@Composable
private fun MainScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.onboarding_title),
            fontSize = 26.sp
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(stringResource(R.string.onboarding_step_1), fontSize = 17.sp, textAlign = TextAlign.Left)
            Text(stringResource(R.string.onboarding_step_2), fontSize = 17.sp, textAlign = TextAlign.Left)
            Text(stringResource(R.string.onboarding_step_3), fontSize = 17.sp, textAlign = TextAlign.Left)
            Text(stringResource(R.string.onboarding_note), fontSize = 14.sp, textAlign = TextAlign.Left)
        }
    }
}
