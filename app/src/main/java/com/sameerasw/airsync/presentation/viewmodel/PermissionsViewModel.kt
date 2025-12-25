package com.sameerasw.airsync.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sameerasw.airsync.models.PermissionGroup
import com.sameerasw.airsync.utils.PermissionUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PermissionsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _permissionGroups = MutableStateFlow<List<PermissionGroup>>(emptyList())
    val permissionGroups: StateFlow<List<PermissionGroup>> = _permissionGroups.asStateFlow()
    
    private val _missingCount = MutableStateFlow(0)
    val missingCount: StateFlow<Int> = _missingCount.asStateFlow()
    
    private val _missingRequiredCount = MutableStateFlow(0)
    val missingRequiredCount: StateFlow<Int> = _missingRequiredCount.asStateFlow()
    
    init {
        refreshPermissions()
    }
    
    fun refreshPermissions() {
        viewModelScope.launch {
            val groups = PermissionUtil.getAllPermissionGroups(getApplication())
            _permissionGroups.value = groups
            
            val allPermissions = groups.flatMap { it.permissions }
            _missingCount.value = allPermissions.count { !it.isGranted }
            _missingRequiredCount.value = allPermissions.count { !it.isGranted && it.isRequired }
        }
    }
    
    fun getMissingPermissionCount(): Int {
        return _missingCount.value
    }
    
    fun getMissingRequiredPermissionCount(): Int {
        return _missingRequiredCount.value
    }
    
    fun hasAllRequiredPermissions(): Boolean {
        return _missingRequiredCount.value == 0
    }
}
