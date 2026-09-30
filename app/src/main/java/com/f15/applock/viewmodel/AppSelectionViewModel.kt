package com.f15.applock.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.f15.applock.data.repository.AppRepository
import com.f15.applock.data.security.SecurityEventLogger
import com.f15.applock.data.security.SecurityEventType
import com.f15.applock.data.storage.AppLockPreferences
import com.f15.applock.domain.model.InstalledApp
import com.f15.applock.security.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for the App Selection Screen.
 */
data class AppSelectionUiState(
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val installedApps: List<InstalledApp> = emptyList(),
    val filteredApps: List<InstalledApp> = emptyList(),
    val lockedPackageNames: Set<String> = emptySet(),
    val errorMessage: String? = null
) {
    val protectedCount: Int
        get() = lockedPackageNames.size
}

/**
 * ViewModel managing installed app discovery, search filtering, and app protection state persistence.
 *
 * Hardened for Phase 5:
 * Enforces authenticated session check before allowing any modifications to the protected application list.
 */
class AppSelectionViewModel(
    private val appRepository: AppRepository,
    private val preferences: AppLockPreferences,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    private val _searchQuery = MutableStateFlow("")
    private val _rawInstalledApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AppSelectionUiState> = combine(
        _isLoading,
        _searchQuery,
        _rawInstalledApps,
        preferences.lockedPackagesFlow,
        _errorMessage
    ) { isLoading, query, apps, lockedPackages, error ->
        val mappedApps = apps.map { app ->
            val isLocked = lockedPackages.contains(app.packageName)
            if (app.isLocked != isLocked) app.copy(isLocked = isLocked) else app
        }

        val filtered = if (query.isBlank()) {
            mappedApps
        } else {
            val queryTrimmed = query.trim()
            mappedApps.filter { app ->
                app.appName.contains(queryTrimmed, ignoreCase = true) ||
                app.packageName.contains(queryTrimmed, ignoreCase = true)
            }
        }

        AppSelectionUiState(
            isLoading = isLoading,
            searchQuery = query,
            installedApps = mappedApps,
            filteredApps = filtered,
            lockedPackageNames = lockedPackages,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSelectionUiState()
    )

    init {
        loadInstalledApps()
    }

    /**
     * Loads installed apps from the repository on a background thread.
     */
    fun loadInstalledApps(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val apps = appRepository.getInstalledApps(forceRefresh = forceRefresh)
                _rawInstalledApps.value = apps
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage ?: "Failed to load installed applications"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Updates current search query.
     */
    fun onSearchQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
    }

    /**
     * Clears active search filter.
     */
    fun onClearSearch() {
        _searchQuery.value = ""
    }

    /**
     * Toggles the protection/lock status for a specific app package.
     * Guaranteed Anti-Bypass: Requires an active authenticated session.
     */
    fun toggleAppProtection(packageName: String, currentStatus: Boolean) {
        if (!sessionManager.isAuthenticated.value) {
            _errorMessage.value = "Authentication required to modify protected applications"
            return
        }

        viewModelScope.launch {
            val newStatus = !currentStatus
            preferences.setAppLocked(packageName, newStatus)
            SecurityEventLogger.log(
                SecurityEventType.APP_PROTECTION_TOGGLED,
                "Package '$packageName' protection set to: $newStatus"
            )
        }
    }

    /**
     * Scans installed applications and purges uninstalled/stale protected packages.
     */
    fun cleanupStalePackages(onComplete: (Int) -> Unit = {}) {
        if (!sessionManager.isAuthenticated.value) {
            onComplete(0)
            return
        }

        viewModelScope.launch {
            val installedPackages = _rawInstalledApps.value.map { it.packageName }.toSet()
            if (installedPackages.isNotEmpty()) {
                val removed = preferences.cleanupStalePackages(installedPackages)
                if (removed > 0) {
                    SecurityEventLogger.log(
                        SecurityEventType.CLEANUP_STALE_PACKAGES,
                        "Purged $removed stale/uninstalled packages from protected list"
                    )
                }
                onComplete(removed)
            } else {
                onComplete(0)
            }
        }
    }

    /**
     * Factory for creating [AppSelectionViewModel] with dependencies.
     */
    class Factory(
        private val appRepository: AppRepository,
        private val preferences: AppLockPreferences,
        private val sessionManager: SessionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AppSelectionViewModel::class.java)) {
                return AppSelectionViewModel(appRepository, preferences, sessionManager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
