package com.belugachat.app

import android.app.Application
import android.content.Context
import com.belugachat.app.chat.AttachmentStore
import com.belugachat.app.chat.ChatEngine
import com.belugachat.app.chat.GenerationService
import com.belugachat.app.data.AppDatabase
import com.belugachat.app.data.KeystoreCipher
import com.belugachat.app.data.ProviderRepository
import com.belugachat.app.data.SettingsStore
import com.belugachat.app.ext.ExtensionsRepository
import com.belugachat.app.ext.McpConnections
import com.belugachat.app.tools.ToolRegistry
import com.belugachat.app.update.AppUpdater
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Hand-wired dependencies; one instance per process. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(context)
    private val cipher = KeystoreCipher()
    val settings = SettingsStore(context)
    val providers = ProviderRepository(db, cipher)
    val extensions = ExtensionsRepository(context, db, cipher, McpConnections())
    val tools = ToolRegistry(db, extensions)
    val attachments = AttachmentStore(context)
    val engine = ChatEngine(db, providers, tools, extensions, attachments, settings, scope)
    val updater = AppUpdater(context, settings)
}

class BelugaChatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
        // Answers that were streaming when the process died can't resume.
        container.scope.launch { container.db.chats().markInterrupted() }
        // Keep streaming when the screen locks or the user switches apps.
        container.scope.launch {
            container.engine.live.map { it.isNotEmpty() }.distinctUntilChanged().filter { it }
                .collect { GenerationService.start(this@BelugaChatApp) }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as BelugaChatApp).container
