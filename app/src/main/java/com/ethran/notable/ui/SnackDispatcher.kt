package com.ethran.notable.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

interface SnackDispatcher {
    val activeSnacks: StateFlow<List<SnackConf>>

    fun showOrUpdateSnack(conf: SnackConf)
    fun removeSnack(id: String)
}

@Singleton
class DefaultSnackDispatcher @Inject constructor() : SnackDispatcher {
    private val _activeSnacks = MutableStateFlow<List<SnackConf>>(emptyList())
    override val activeSnacks = _activeSnacks.asStateFlow()

    override fun showOrUpdateSnack(conf: SnackConf) {
        _activeSnacks.update { currentList ->
            val index = currentList.indexOfFirst { it.id == conf.id }
            if (index != -1) {
                // if snack already exists, update it
                val newList = currentList.toMutableList()
                newList[index] = conf
                newList
            } else {
                // if new snack
                currentList + conf
            }
        }
    }

    override fun removeSnack(id: String) {
        _activeSnacks.update { currentList ->
            currentList.filterNot { it.id == id }
        }
    }
}