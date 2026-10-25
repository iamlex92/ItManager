package com.itmanager.app

import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import com.itmanager.app.core.nfc.NfcController
import com.itmanager.app.ui.navigation.AppRoot
import com.itmanager.app.ui.theme.ItManagerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var nfc: NfcController

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is dark-only, so system bar icons stay light regardless of the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ItManagerTheme {
                AppRoot()
            }
        }
        // A tag that launched the app; not again after rotation.
        if (savedInstanceState == null) handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // While the app is open every tag comes straight here, without the system chooser or a new intent.
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this,
            { tag -> nfc.onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A,
            null,
        )
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return
        IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java)?.let(nfc::onTag)
    }
}
