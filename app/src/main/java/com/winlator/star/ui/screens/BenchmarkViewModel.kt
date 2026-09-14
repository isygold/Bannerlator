package com.winlator.star.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.winlator.star.container.ContainerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = ContainerManager(application)

    private val _containers = MutableStateFlow(manager.getContainers().toList())
    val containers: StateFlow<List<com.winlator.star.container.Container>> = _containers

    private val _selectedContainerId = MutableStateFlow(-1)
    val selectedContainerId: StateFlow<Int> = _selectedContainerId

    private val _exePath = MutableStateFlow("")
    val exePath: StateFlow<String> = _exePath

    private val _loggingEnabled = MutableStateFlow(false)
    val loggingEnabled: StateFlow<Boolean> = _loggingEnabled

    init {
        // Pre-select first container if only one exists
        val list = _containers.value
        if (list.size == 1) {
            _selectedContainerId.value = list[0].id
        }
    }

    fun selectContainer(id: Int) {
        _selectedContainerId.value = id
    }

    fun setExePath(path: String) {
        _exePath.value = path
    }

    fun setLoggingEnabled(enabled: Boolean) {
        _loggingEnabled.value = enabled
    }

    fun refresh() {
        manager.reloadContainers()
        _containers.value = manager.getContainers().toList()
    }
}
