package dev.blackbox.router

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.blackbox.core.DiagnosticEngine
import dev.blackbox.core.RouterRepository
import dev.blackbox.router.diagnostics.AndroidFacts
import dev.blackbox.router.root.LibsuRootExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class RouterViewModel(application: Application) : AndroidViewModel(application) {
    val controller = dev.blackbox.router.runtime.RouterController.get(application)
    val runtime = controller.state
    private val mutableActionError = MutableStateFlow<String?>(null)
    val actionError = mutableActionError.asStateFlow()
    private val mutableRecoveryInspection=MutableStateFlow<String?>(null)
    val recoveryInspection=mutableRecoveryInspection.asStateFlow()
    fun inspectRecovery() = action {mutableRecoveryInspection.value=controller.inspectRecovery()}
    fun closeRecoveryInspection() {mutableRecoveryInspection.value=null}
    fun saveProfile(p:dev.blackbox.core.RouterProfile) = action { controller.saveProfile(p) }
    fun deleteProfile(id:String) = action { controller.deleteProfile(id) }
    fun discoverVpn() = action { controller.discoverVpn() }
    fun policy(mac:String,policy:dev.blackbox.core.ClientPolicy) = action { controller.setPolicy(mac,policy) }
    fun clearActionError() { mutableActionError.value=null }
    private fun action(block:suspend ()->Unit) { viewModelScope.launch(Dispatchers.IO) { runCatching { block() }.onFailure { mutableActionError.value=it.message?:"Action failed" } } }
    fun routerCommand(action:String,profile:String?=null) { runCatching { dev.blackbox.router.runtime.RouterService.command(getApplication(),action,profile) }.onFailure { mutableActionError.value=it.message } }
    private val executor = LibsuRootExecutor()
    private val repository = RouterRepository(DiagnosticEngine(executor), AndroidFacts(application)::read)
    private val preferences = application.getSharedPreferences("blackbox_preferences", 0)
    private val mutableDeveloper = MutableStateFlow(preferences.getBoolean("developer_mode", false))
    val developer = mutableDeveloper.asStateFlow()
    val state = repository.state
    private var scanJob: Job? = null
    init { scan(false) }
    fun scan(requestRoot: Boolean) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch { withContext(Dispatchers.IO) { repository.scan(requestRoot) } }
    }
    fun cancelScan() { scanJob?.cancel() }
    fun setDeveloper(enabled: Boolean) {
        preferences.edit { putBoolean("developer_mode", enabled) }
        mutableDeveloper.value = enabled
    }
    override fun onCleared() {
        super.onCleared()
        // close() only destroys a private diagnostic shell; it never mutates networking.
        CoroutineScope(Dispatchers.IO).launch { executor.close() }
    }
}
