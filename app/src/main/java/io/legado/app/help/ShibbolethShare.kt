package io.legado.app.help

import android.content.Context
import android.view.LayoutInflater
import io.legado.app.R
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.Shibboleth
import io.legado.app.utils.ShibbolethType
import io.legado.app.utils.sendToClip

object ShibbolethShare {

    fun show(
        context: Context,
        inflater: LayoutInflater,
        url: String,
        type: ShibbolethType,
    ) {
        val now = System.currentTimeMillis()
        val expiryDays = DirectLinkUpload.getExpiryDate().coerceAtLeast(0)
        val expiresAt = if (expiryDays == 0) {
            0L
        } else {
            now + expiryDays.toLong() * 86_400_000L
        }
        val token = Shibboleth.encode(url, type, expiresAt, now) ?: return
        val binding = DialogEditTextBinding.inflate(inflater).apply {
            editView.setText(token)
        }
        context.alert(R.string.shibboleth) {
            customView { binding.root }
            okButton {
                context.sendToClip(token)
            }
            cancelButton()
        }
    }
}
