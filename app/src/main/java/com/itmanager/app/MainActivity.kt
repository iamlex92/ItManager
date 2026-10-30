package com.itmanager.app

import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itmanager.app.core.nfc.NfcController
import com.itmanager.app.core.security.AppLock
import com.itmanager.app.core.security.LockState
import com.itmanager.app.core.vpn.FactoryVpn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.itmanager.app.ui.navigation.AppRoot
import com.itmanager.app.ui.theme.ItManagerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// FragmentActivity rather than ComponentActivity: BiometricPrompt needs one.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var nfc: NfcController
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var vpn: FactoryVpn

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
        if (savedInstanceState == null) {
            handleNfcIntent(intent)
            vpn.onCallback(intent?.data)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vpn.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() } }
                // Each time the app comes to the front and is unlocked: the factory VPN, if the server does not answer.
                appLock.state.first { it == LockState.UNLOCKED }
                vpn.connectIfNeeded(this@MainActivity)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (vpn.onCallback(intent.data)) return
        handleNfcIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        appLock.onForeground(SystemClock.elapsedRealtime())
    }

    override fun onStop() {
        super.onStop()
        // A rotation is not leaving the app.
        if (!isChangingConfigurations) appLock.onBackground(SystemClock.elapsedRealtime())
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
