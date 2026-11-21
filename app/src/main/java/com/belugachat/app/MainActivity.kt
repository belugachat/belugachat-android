package com.belugachat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.belugachat.app.data.AppSettings
import com.belugachat.app.ui.AppNav
import com.belugachat.app.ui.chat.ChatViewModel
import com.belugachat.app.ui.settings.ManageViewModel
import com.belugachat.app.ui.theme.BelugaChatTheme

class MainActivity : ComponentActivity() {
    private val chat: ChatViewModel by viewModels()
    private val manage: ManageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by container.settings.settings.collectAsState(AppSettings())
            BelugaChatTheme(settings.theme) {
                AppNav(chat, manage)
            }
        }
    }
}
