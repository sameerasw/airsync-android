package com.sameerasw.airsync.models

data class PermissionInfo(
    val permission: String,
    val displayName: String,
    val description: String,
    val category: PermissionCategory,
    val isGranted: Boolean,
    val isRequired: Boolean = false,
    val requiresSpecialHandling: Boolean = false
)

enum class PermissionCategory {
    CORE,           // Essential for app to work
    MESSAGING,      // SMS and messaging features
    CALLS,          // Call logs and phone state
    HEALTH,         // Health Connect permissions
    LOCATION,       // Location for activity tracking
    STORAGE,        // File access
    SPECIAL         // Special permissions (accessibility, notification listener, etc.)
}

data class PermissionGroup(
    val category: PermissionCategory,
    val title: String,
    val description: String,
    val permissions: List<PermissionInfo>
)
