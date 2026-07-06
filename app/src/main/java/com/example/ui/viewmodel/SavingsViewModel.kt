package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.AppSettings
import com.example.data.model.Member
import com.example.data.model.Savings
import com.example.data.model.ChangeRequest
import com.example.data.repository.SavingsRepository
import com.example.ui.notification.AdminNotificationHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for managing the application states, settings, and business logic.
 * Handles search queries, database persistence triggers, and UI reactive state mapping.
 */
class SavingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: SavingsRepository

    // Comment: Store the currently logged-in member ID dynamically (starts null meaning logged out)
    private val _currentUserId = MutableStateFlow<Int?>(null)
    val currentUserId: StateFlow<Int?> = _currentUserId.asStateFlow()

    // Comment: Store and expose the logged-in user's email dynamically to resolve duplicate account issues
    val currentMemberEmail: StateFlow<String?>

    // Comment: Expose loading and error states for Google OAuth login process
    private val _googleLoginLoading = MutableStateFlow(false)
    val googleLoginLoading: StateFlow<Boolean> = _googleLoginLoading.asStateFlow()

    private val _googleLoginError = MutableStateFlow<String?>(null)
    val googleLoginError: StateFlow<String?> = _googleLoginError.asStateFlow()

    // Comment: For backwards compatibility, get active user's ID or fallback to -1 (No User)
    val currentUserMemberId: Int get() = _currentUserId.value ?: -1

    // UI state flows from Room
    val allMembers: StateFlow<List<Member>>
    val allSavings: StateFlow<List<Savings>>
    val currentUserSavings: StateFlow<List<Savings>>
    val appSettings: StateFlow<AppSettings>
    val allChangeRequests: StateFlow<List<ChangeRequest>>

    // Search query for members list
    private val _memberSearchQuery = MutableStateFlow("")
    val memberSearchQuery: StateFlow<String> = _memberSearchQuery

    // Filtered members for Search screen
    val filteredMembers: StateFlow<List<Member>>

    // Admin member contribution form states
    private val _selectedMemberForContribution = MutableStateFlow<Member?>(null)
    val selectedMemberForContribution: StateFlow<Member?> = _selectedMemberForContribution

    // Admin panel accordion open/close persistent states (Defaulted to false so they are closed by default when the app starts)
    private val _isAdminContributionOpen = MutableStateFlow(false)
    val isAdminContributionOpen: StateFlow<Boolean> = _isAdminContributionOpen

    private val _isAdminActiveCycleOpen = MutableStateFlow(false)
    val isAdminActiveCycleOpen: StateFlow<Boolean> = _isAdminActiveCycleOpen

    private val _isAdminChangeRequestsOpen = MutableStateFlow(false)
    val isAdminChangeRequestsOpen: StateFlow<Boolean> = _isAdminChangeRequestsOpen

    // Comment: Store state for manual database syncing with Supabase
    private val _isManualSyncing = MutableStateFlow(false)
    val isManualSyncing: StateFlow<Boolean> = _isManualSyncing.asStateFlow()

    // Comment: Store a pending deep navigation route triggered from notification click
    private val _pendingNavigationRoute = MutableStateFlow<String?>(null)
    val pendingNavigationRoute: StateFlow<String?> = _pendingNavigationRoute.asStateFlow()

    fun triggerNavigation(route: String) {
        _pendingNavigationRoute.value = route
    }

    fun clearPendingNavigation() {
        _pendingNavigationRoute.value = null
    }

    private var syncJob: kotlinx.coroutines.Job? = null

    fun startPeriodicSync() {
        if (syncJob != null && syncJob?.isActive == true) return
        android.util.Log.d("SupabaseSync", "Starting lifecycle-aware foreground periodic sync loop...")
        syncJob = viewModelScope.launch {
            // Wait 5 seconds after startup before starting periodic background sync to avoid conflicts with startup logic
            kotlinx.coroutines.delay(5000)
            while (true) {
                val email = com.example.data.SupabaseClient.getSessionEmail()
                if (!email.isNullOrBlank() && !isManualSyncing.value) {
                    try {
                        android.util.Log.d("SupabaseSync", "Periodic active-foreground sync triggering...")
                        val newlyPulled = repository.syncWithSupabase()

                        // Comment: If any new pending change requests were pulled and the current user is an admin, show them notifications
                        val currentEmail = com.example.data.SupabaseClient.getSessionEmail()
                        val isCurrentUserAdmin = if (!currentEmail.isNullOrBlank()) {
                            repository.getAllMembersDirect().find { it.email.trim().equals(currentEmail.trim(), ignoreCase = true) }?.role == "Admin"
                        } else false

                        if (isCurrentUserAdmin && newlyPulled.isNotEmpty()) {
                            for (req in newlyPulled) {
                                val friendlyChangeType = when (req.requestType) {
                                    "Edit" -> "edit"
                                    "Delete" -> "delete"
                                    "RemoveMember" -> "user removal"
                                    "MakeAdmin", "RemoveAdmin" -> "role change"
                                    else -> req.requestType
                                }
                                com.example.ui.notification.AdminNotificationHelper.showAdminChangeRequestNotification(
                                    getApplication(),
                                    req.memberName,
                                    friendlyChangeType
                                )
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("SupabaseSync", "Periodic active-foreground sync skipped/failed: ${e.localizedMessage}")
                    }
                }
                // Wait for 5 seconds before the next sync cycle
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    fun stopPeriodicSync() {
        android.util.Log.d("SupabaseSync", "Stopping periodic sync loop (inactive/backgrounded)...")
        syncJob?.cancel()
        syncJob = null
    }

    init {
        val database = AppDatabase.getDatabase(application)
        repository = SavingsRepository(
            database.memberDao(),
            database.savingsDao(),
            database.appSettingsDao(),
            database.changeRequestDao()
        )

        // Seed data asynchronously
        viewModelScope.launch {
            repository.populateInitialDataIfEmpty()

            // Comment: Dynamically update any pre-existing database default savings goal from 5000.0 to 500.0 for a consistent user experience
            repository.appSettings.firstOrNull()?.let { settings ->
                if (settings.personalGoal == 5000.0) {
                    repository.updateSettings(settings.copy(personalGoal = 500.0))
                }
            }

            // Comment: Automatically restore the user's logged-in session on startup if a valid Supabase session is persisted
            val savedEmail = com.example.data.SupabaseClient.getSessionEmail()
            if (!savedEmail.isNullOrBlank()) {
                // Comment: Force bidirectional data sync with Supabase tables on startup
                repository.syncWithSupabase()

                val membersList = repository.getAllMembersDirect()
                val matchedMember = membersList.find { it.email.trim().equals(savedEmail.trim(), ignoreCase = true) }
                if (matchedMember != null) {
                    _currentUserId.value = matchedMember.id
                    // Also update settings profile name and membershipNo for UI matching
                    val settings = repository.getSettingsDirect() ?: AppSettings()
                    repository.updateSettings(settings.copy(profileName = matchedMember.name, membershipNo = matchedMember.membershipNo))
                }
            }
        }

        // Comment: Dynamically track and expose the logged-in user's email for accurate de-duplication matching
        currentMemberEmail = combine(repository.allMembers, _currentUserId) { membersList, userId ->
            if (userId == null) null
            else membersList.find { it.id == userId }?.email
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        // Map flows from repository
        // Comment: Combine allMembers and allSavings to dynamically calculate the up-to-date totalSavings for each member from savings history
        // Includes full de-duplication of duplicate email accounts (such as Asad.msnd) to present a single merged account in UI.
        allMembers = combine(repository.allMembers, repository.allSavings) { members, savingsList ->
            val filtered = members.filter { it.email.trim().lowercase() !in seedEmails }
            val groupedByEmail = filtered.groupBy { it.email.trim().lowercase() }

            groupedByEmail.map { (email, memberList) ->
                val primaryMember = memberList.first()
                val allGroupIds = memberList.map { it.id }.toSet()
                val sumSavings = savingsList.filter { it.memberId in allGroupIds }.sumOf { it.amount }
                primaryMember.copy(totalSavings = sumSavings)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Comment: Map and filter savings records, routing any savings assigned to duplicate account IDs to the primary representative ID
        allSavings = combine(repository.allSavings, repository.allMembers) { savingsList, members ->
            val seedMemberIds = members
                .filter { it.email.trim().lowercase() in seedEmails }
                .map { it.id }
                .toSet()
            val filteredSavings = savingsList.filter { it.memberId !in seedMemberIds }

            val remainingMembers = members.filter { it.email.trim().lowercase() !in seedEmails }
            val groupedByEmail = remainingMembers.groupBy { it.email.trim().lowercase() }
            val duplicateIdMap = mutableMapOf<Int, Int>()
            for ((email, membersWithEmail) in groupedByEmail) {
                if (membersWithEmail.size > 1) {
                    val representative = membersWithEmail.first()
                    for (i in 1 until membersWithEmail.size) {
                        duplicateIdMap[membersWithEmail[i].id] = representative.id
                    }
                }
            }

            filteredSavings.map { s ->
                val mappedId = duplicateIdMap[s.memberId]
                if (mappedId != null) s.copy(memberId = mappedId) else s
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Comment: Dynamically filter user's savings based on the logged-in user session, automatically mapping duplicate IDs to the single primary ID
        currentUserSavings = combine(allSavings, _currentUserId, repository.allMembers) { savingsList, userId, membersList ->
            val activeId = if (userId == null) -1 else {
                val loggedIn = membersList.find { it.id == userId }
                if (loggedIn != null) {
                    val representative = membersList
                        .filter { it.email.trim().lowercase() !in seedEmails }
                        .find { it.email.trim().equals(loggedIn.email.trim(), ignoreCase = true) }
                    representative?.id ?: userId
                } else {
                    userId
                }
            }
            savingsList.filter { it.memberId == activeId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        appSettings = repository.appSettings
            .map { it ?: AppSettings() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

        allChangeRequests = repository.allChangeRequests
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Combine members and search query
        // Comment: Sort the members list alphabetically by name
        filteredMembers = combine(allMembers, _memberSearchQuery) { members, query ->
            val filtered = if (query.isBlank()) {
                members
            } else {
                members.filter { it.name.contains(query, ignoreCase = true) }
            }
            filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    // Update search query
    fun updateMemberSearchQuery(query: String) {
        _memberSearchQuery.value = query
    }

    // Set selected member in Admin contribution form
    fun selectMemberForContribution(member: Member?) {
        _selectedMemberForContribution.value = member
    }

    // Toggle states for the persistent admin panel accordions
    fun setAdminContributionOpen(open: Boolean) {
        _isAdminContributionOpen.value = open
    }

    fun setAdminActiveCycleOpen(open: Boolean) {
        _isAdminActiveCycleOpen.value = open
    }

    fun setAdminChangeRequestsOpen(open: Boolean) {
        _isAdminChangeRequestsOpen.value = open
    }

    // Comment: Trigger a manual bidirectional data sync with Supabase tables and update local Room DB cache
    fun triggerManualSync(onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _isManualSyncing.value = true
            try {
                val newlyPulled = repository.syncWithSupabase()

                // Comment: If any new pending change requests were pulled and the current user is an admin, show them notifications
                val currentEmail = com.example.data.SupabaseClient.getSessionEmail()
                val isCurrentUserAdmin = if (!currentEmail.isNullOrBlank()) {
                    repository.getAllMembersDirect().find { it.email.trim().equals(currentEmail.trim(), ignoreCase = true) }?.role == "Admin"
                } else false

                if (isCurrentUserAdmin && newlyPulled.isNotEmpty()) {
                    for (req in newlyPulled) {
                        val friendlyChangeType = when (req.requestType) {
                            "Edit" -> "edit"
                            "Delete" -> "delete"
                            "RemoveMember" -> "user removal"
                            "MakeAdmin", "RemoveAdmin" -> "role change"
                            else -> req.requestType
                        }
                        com.example.ui.notification.AdminNotificationHelper.showAdminChangeRequestNotification(
                            getApplication(),
                            req.memberName,
                            friendlyChangeType
                        )
                    }
                }

                onComplete(true, "Sync completed successfully")
            } catch (e: Exception) {
                onComplete(false, "Sync failed: ${e.localizedMessage}")
            } finally {
                _isManualSyncing.value = false
            }
        }
    }

    // Add saving contribution
    fun addSavingsContribution(memberId: Int, memberName: String, amount: Double, dateText: String) {
        viewModelScope.launch {
            val savings = Savings(
                memberId = memberId,
                memberName = memberName,
                amount = amount,
                dateText = dateText
            )
            repository.insertSavings(savings)
        }
    }

    // Edit saving contribution
    fun updateSavingsContribution(savings: Savings, oldAmount: Double) {
        viewModelScope.launch {
            repository.updateSavings(savings, oldAmount)
        }
    }

    // Delete saving contribution
    fun deleteSavingsContribution(savings: Savings) {
        viewModelScope.launch {
            repository.deleteSavings(savings)
        }
    }

    // Update application settings
    fun updateAppSettings(settings: AppSettings) {
        viewModelScope.launch {
            repository.updateSettings(settings)
        }
    }

    // Update settings: Dark Mode
    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            val current = repository.getSettingsDirect() ?: AppSettings()
            repository.updateSettings(current.copy(isDarkMode = enabled))
        }
    }

    // Update settings: Personal Goal
    fun setPersonalGoal(goal: Double) {
        viewModelScope.launch {
            val current = repository.getSettingsDirect() ?: AppSettings()
            repository.updateSettings(current.copy(personalGoal = goal))
        }
    }

    // Update settings: Profile details
    fun updateProfileInfo(name: String, membershipNo: String, profileImageUrl: String? = null) {
        viewModelScope.launch {
            val current = repository.getSettingsDirect() ?: AppSettings()
            val member = repository.getMemberById(currentUserMemberId)

            var img = profileImageUrl ?: current.profileImageUrl

            // Comment: If a local profile image path is provided (e.g. starts with /), upload it to Supabase Storage
            if (profileImageUrl != null && profileImageUrl.startsWith("/")) {
                try {
                    val file = java.io.File(profileImageUrl)
                    if (file.exists()) {
                        val fileBytes = file.readBytes()
                        // Use user email or fallback as base for file name
                        val emailToUse = member?.email ?: com.example.data.SupabaseClient.getSessionEmail() ?: "user"
                        val cleanedEmail = emailToUse.replace(Regex("[^a-zA-Z0-9]"), "_")
                        val fileName = "avatar_${cleanedEmail}_${System.currentTimeMillis()}.png"

                        val publicUrl = com.example.data.SupabaseClient.uploadAvatar(fileName, fileBytes)
                        if (publicUrl != null) {
                            img = publicUrl
                            android.util.Log.d("ProfileImageUpload", "Successfully uploaded image to Supabase Storage: $publicUrl")
                        } else {
                            android.util.Log.e("ProfileImageUpload", "Failed to upload image to Supabase Storage, falling back to local path")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ProfileImageUpload", "Exception during profile image upload: ${e.localizedMessage}", e)
                }
            }

            repository.updateSettings(current.copy(profileName = name, membershipNo = membershipNo, profileImageUrl = img))
            // Also update current user member name, avatarUrl, and membershipNo in the database
            if (member != null) {
                repository.updateMember(member.copy(name = name, avatarUrl = img, membershipNo = membershipNo))
            }
        }
    }

    // Update member details (e.g. status suspension)
    fun updateMember(member: Member) {
        viewModelScope.launch {
            repository.updateMember(member)
        }
    }

    // Update automated notification settings
    fun updateNotificationSettings(
        enabled: Boolean,
        day: String,
        time: String,
        text: String
    ) {
        viewModelScope.launch {
            val current = repository.getSettingsDirect() ?: AppSettings()
            repository.updateSettings(
                current.copy(
                    enableNotifications = enabled,
                    notificationDay = day,
                    notificationTime = time,
                    notificationText = text
                )
            )
        }
    }

    // Submit edit request
    fun requestEditSavings(savings: Savings, newAmount: Double, newDateText: String) {
        viewModelScope.launch {
            val request = ChangeRequest(
                savingsId = savings.id,
                memberId = savings.memberId,
                memberName = savings.memberName,
                requestType = "Edit",
                originalAmount = savings.amount,
                newAmount = newAmount,
                originalDateText = savings.dateText,
                newDateText = newDateText,
                status = "Pending"
            )
            repository.insertChangeRequest(request)

            // Comment: Trigger system notification for user who made that request
            com.example.ui.notification.AdminNotificationHelper.showUserChangeRequestSubmittedNotification(getApplication())

            // Comment: Trigger system notification for admins locally if the current logged-in user is an admin
            val currentEmail = com.example.data.SupabaseClient.getSessionEmail()
            val isCurrentUserAdmin = if (!currentEmail.isNullOrBlank()) {
                repository.getAllMembersDirect().find { it.email.trim().equals(currentEmail.trim(), ignoreCase = true) }?.role == "Admin"
            } else false
            if (isCurrentUserAdmin) {
                com.example.ui.notification.AdminNotificationHelper.showAdminChangeRequestNotification(
                    getApplication(),
                    request.memberName,
                    "edit"
                )
            }
        }
    }

    // Submit delete request
    fun requestDeleteSavings(savings: Savings) {
        viewModelScope.launch {
            val request = ChangeRequest(
                savingsId = savings.id,
                memberId = savings.memberId,
                memberName = savings.memberName,
                requestType = "Delete",
                originalAmount = savings.amount,
                originalDateText = savings.dateText,
                status = "Pending"
            )
            repository.insertChangeRequest(request)

            // Comment: Trigger system notification for user who made that request
            com.example.ui.notification.AdminNotificationHelper.showUserChangeRequestSubmittedNotification(getApplication())

            // Comment: Trigger system notification for admins locally if the current logged-in user is an admin
            val currentEmail = com.example.data.SupabaseClient.getSessionEmail()
            val isCurrentUserAdmin = if (!currentEmail.isNullOrBlank()) {
                repository.getAllMembersDirect().find { it.email.trim().equals(currentEmail.trim(), ignoreCase = true) }?.role == "Admin"
            } else false
            if (isCurrentUserAdmin) {
                com.example.ui.notification.AdminNotificationHelper.showAdminChangeRequestNotification(
                    getApplication(),
                    request.memberName,
                    "delete"
                )
            }
        }
    }

    // Admin approves a request
    fun approveChangeRequest(request: ChangeRequest) {
        viewModelScope.launch {
            if (request.status == "Pending") {
                if (request.requestType == "Delete") {
                    val savings = Savings(
                        id = request.savingsId,
                        memberId = request.memberId,
                        memberName = request.memberName,
                        amount = request.originalAmount,
                        dateText = request.originalDateText
                    )
                    repository.deleteSavings(savings)
                } else if (request.requestType == "Edit") {
                    val savings = Savings(
                        id = request.savingsId,
                        memberId = request.memberId,
                        memberName = request.memberName,
                        amount = request.newAmount ?: request.originalAmount,
                        dateText = request.newDateText ?: request.originalDateText
                    )
                    repository.updateSavings(savings, request.originalAmount)
                }
                repository.updateChangeRequest(request.copy(status = "Approved"))
            }
        }
    }

    // Admin rejects a request
    fun rejectChangeRequest(request: ChangeRequest) {
        viewModelScope.launch {
            if (request.status == "Pending") {
                repository.updateChangeRequest(request.copy(status = "Rejected"))
            }
        }
    }

    // Submit user removal request (generates a Member Change Request)
    fun requestRemoveMember(member: Member) {
        viewModelScope.launch {
            val request = ChangeRequest(
                savingsId = -1,
                memberId = member.id,
                memberName = member.name,
                requestType = "RemoveMember",
                originalAmount = 0.0,
                originalDateText = "",
                newDateText = "", // will store comma-separated Admin IDs who confirmed
                status = "Pending"
            )
            repository.insertChangeRequest(request)

            // Comment: Trigger system notification for user who made that request
            com.example.ui.notification.AdminNotificationHelper.showUserChangeRequestSubmittedNotification(getApplication())

            // Comment: Trigger system notification for admins locally if the current logged-in user is an admin
            val currentEmail = com.example.data.SupabaseClient.getSessionEmail()
            val isCurrentUserAdmin = if (!currentEmail.isNullOrBlank()) {
                repository.getAllMembersDirect().find { it.email.trim().equals(currentEmail.trim(), ignoreCase = true) }?.role == "Admin"
            } else false
            if (isCurrentUserAdmin) {
                com.example.ui.notification.AdminNotificationHelper.showAdminChangeRequestNotification(
                    getApplication(),
                    request.memberName,
                    "user removal"
                )
            }
        }
    }

    // Confirm member removal by a specific Admin. If all existing admins confirm, the user is removed.
    fun confirmRemoveMemberAsAdmin(request: ChangeRequest, adminId: Int, allAdmins: List<Member>) {
        viewModelScope.launch {
            if (request.status == "Pending") {
                val currentConfirmedIds = request.newDateText?.split(",")
                    ?.filter { it.isNotEmpty() }
                    ?.mapNotNull { it.toIntOrNull() }
                    ?.toSet() ?: emptySet()

                // Add current admin ID to the list of confirmed admins
                val updatedConfirmedIds = (currentConfirmedIds + adminId).distinct()
                val updatedNewDateText = updatedConfirmedIds.joinToString(",")

                // Fetch the list of active admin IDs to compare
                val allAdminIds = allAdmins.map { it.id }.toSet()

                // Check if all existing admins have now confirmed
                if (updatedConfirmedIds.containsAll(allAdminIds)) {
                    // All admins have confirmed! Remove the user from the app.
                    val memberToDelete = repository.getMemberById(request.memberId)
                    if (memberToDelete != null) {
                        repository.deleteMember(memberToDelete)
                    }
                    // Mark request as Approved
                    repository.updateChangeRequest(
                        request.copy(
                            newDateText = updatedNewDateText,
                            status = "Approved"
                        )
                    )
                } else {
                    // Update confirmation list, but keep status as Pending
                    repository.updateChangeRequest(
                        request.copy(
                            newDateText = updatedNewDateText
                        )
                    )
                }
            }
        }
    }

    // Submit role change request (generates a Member Change Request)
    fun requestChangeMemberRole(member: Member, newRole: String) {
        viewModelScope.launch {
            val request = ChangeRequest(
                savingsId = -1,
                memberId = member.id,
                memberName = member.name,
                requestType = if (newRole == "Admin") "MakeAdmin" else "RemoveAdmin",
                originalAmount = 0.0,
                originalDateText = "",
                newDateText = "", // will store comma-separated Admin IDs who confirmed
                status = "Pending"
            )
            repository.insertChangeRequest(request)

            // Comment: Trigger system notification for user who made that request
            com.example.ui.notification.AdminNotificationHelper.showUserChangeRequestSubmittedNotification(getApplication())

            // Comment: Trigger system notification for admins locally if the current logged-in user is an admin
            val currentEmail = com.example.data.SupabaseClient.getSessionEmail()
            val isCurrentUserAdmin = if (!currentEmail.isNullOrBlank()) {
                repository.getAllMembersDirect().find { it.email.trim().equals(currentEmail.trim(), ignoreCase = true) }?.role == "Admin"
            } else false
            if (isCurrentUserAdmin) {
                com.example.ui.notification.AdminNotificationHelper.showAdminChangeRequestNotification(
                    getApplication(),
                    request.memberName,
                    "role change"
                )
            }
        }
    }

    // Confirm member role change by a specific Admin. If all existing admins confirm, the role is updated.
    fun confirmRoleChangeAsAdmin(request: ChangeRequest, adminId: Int, allAdmins: List<Member>) {
        viewModelScope.launch {
            if (request.status == "Pending") {
                val currentConfirmedIds = request.newDateText?.split(",")
                    ?.filter { it.isNotEmpty() }
                    ?.mapNotNull { it.toIntOrNull() }
                    ?.toSet() ?: emptySet()

                // Add current admin ID to the list of confirmed admins
                val updatedConfirmedIds = (currentConfirmedIds + adminId).distinct()
                val updatedNewDateText = updatedConfirmedIds.joinToString(",")

                // Fetch the list of active admin IDs to compare
                val allAdminIds = allAdmins.map { it.id }.toSet()

                // Check if all existing admins have now confirmed
                if (updatedConfirmedIds.containsAll(allAdminIds)) {
                    // All admins have confirmed! Update the user's role.
                    val memberToUpdate = repository.getMemberById(request.memberId)
                    if (memberToUpdate != null) {
                        val updatedRole = if (request.requestType == "MakeAdmin") "Admin" else "Member"
                        repository.updateMember(memberToUpdate.copy(role = updatedRole))
                    }
                    // Mark request as Approved
                    repository.updateChangeRequest(
                        request.copy(
                            newDateText = updatedNewDateText,
                            status = "Approved"
                        )
                    )
                } else {
                    // Update confirmation list, but keep status as Pending
                    repository.updateChangeRequest(
                        request.copy(
                            newDateText = updatedNewDateText
                        )
                    )
                }
            }
        }
    }

    // Comment: Determine the role for a new user: if no manual members are present in Supabase, the first is Admin, others are Members.
    private suspend fun determineRoleForNewUser(): String {
        return try {
            val remoteMembers = com.example.data.SupabaseClient.dbFetchMembers()
            val manualMembers = remoteMembers.filter { member ->
                member.email.trim().lowercase() !in seedEmails
            }
            if (manualMembers.isEmpty()) "Admin" else "Member"
        } catch (e: Exception) {
            "Member" // Fallback to safe default
        }
    }

    // Comment: Store state to show the Reset/Update Password Dialog on deep link password recovery
    private val _showResetPasswordDialog = MutableStateFlow(false)
    val showResetPasswordDialog: StateFlow<Boolean> = _showResetPasswordDialog.asStateFlow()

    fun setResetPasswordDialogVisible(visible: Boolean) {
        _showResetPasswordDialog.value = visible
    }

    // Comment: Update password for the currently logged-in user session in both remote Supabase and local databases
    fun updatePassword(newPassword: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (newPassword.isBlank() || newPassword.length < 6) {
                onResult(false, "Password must be at least 6 characters long.")
                return@launch
            }

            // 1. Update Supabase Auth password
            val result = com.example.data.SupabaseClient.updatePassword(newPassword)
            if (!result.success) {
                onResult(false, result.message)
                return@launch
            }

            // 2. Update password in our local database and sync if user profile exists
            val currentEmail = com.example.data.SupabaseClient.getSessionEmail()
            var loggedInMember: Member? = null
            if (!currentEmail.isNullOrBlank()) {
                val membersList = repository.getAllMembersDirect()
                val matchedMember = membersList.find { it.email.trim().equals(currentEmail.trim(), ignoreCase = true) }
                if (matchedMember != null) {
                    val updatedMember = matchedMember.copy(password = newPassword)
                    repository.updateMember(updatedMember)
                    loggedInMember = updatedMember

                    // Force complete bidirectional data sync to make sure local/remote updates align
                    try {
                        repository.syncWithSupabase()
                    } catch (e: Exception) {
                        android.util.Log.e("SavingsViewModel", "Failed to sync during password update: ${e.localizedMessage}")
                    }
                }
            }

            // 3. SECURE UPGRADE: Log the user into the app ONLY AFTER they have successfully updated their password
            if (loggedInMember != null) {
                _currentUserId.value = loggedInMember.id
                try {
                    val settings = repository.getSettingsDirect() ?: AppSettings()
                    repository.updateSettings(settings.copy(profileName = loggedInMember.name, membershipNo = loggedInMember.membershipNo))
                } catch (e: Exception) {
                    android.util.Log.e("SavingsViewModel", "Failed to update AppSettings after password update", e)
                }
                try {
                    repository.syncWithSupabase()
                } catch (e: Exception) {
                    android.util.Log.e("SavingsViewModel", "Failed to run post-login sync after password update", e)
                }
            }

            onResult(true, "Your password has been successfully updated!")
        }
    }

    // Comment: Handles deep link callback specifically for password recovery types, establishing active session securely without immediate login bypass
    fun handleRecoveryCallback(
        accessToken: String,
        refreshToken: String,
        expiresIn: String,
        tokenType: String
    ) {
        viewModelScope.launch {
            _googleLoginLoading.value = true
            _googleLoginError.value = null

            val result = com.example.data.SupabaseClient.fetchUserAndSaveSession(
                accessToken,
                refreshToken,
                expiresIn,
                tokenType
            )

            if (!result.success) {
                _googleLoginLoading.value = false
                _googleLoginError.value = result.message
                return@launch
            }

            val email = result.userEmail
            if (email.isNullOrBlank()) {
                _googleLoginLoading.value = false
                _googleLoginError.value = "Recovery profile does not contain an email."
                return@launch
            }

            // Comment: Store the successfully authenticated email address in local SharedPreferences for pre-filling
            com.example.data.SupabaseClient.saveRegisteredEmail(email)

            // Perform the local profile setup
            val membersList = repository.getAllMembersDirect()
            var foundMember = membersList.find { it.email.trim().equals(email.trim(), ignoreCase = true) }

            if (foundMember == null) {
                val assignedRole = determineRoleForNewUser()
                var remoteMember: Member? = null
                try {
                    val remoteMembers = com.example.data.SupabaseClient.dbFetchMembers()
                    remoteMember = remoteMembers.find { it.email.trim().equals(email.trim(), ignoreCase = true) }
                } catch (e: Exception) {
                    android.util.Log.w("SavingsViewModel", "Failed to fetch remote members from Supabase: ${e.localizedMessage}")
                }

                if (remoteMember != null) {
                    try {
                        repository.insertMemberLocally(remoteMember)
                        foundMember = remoteMember
                    } catch (e: Exception) {
                        android.util.Log.e("SavingsViewModel", "Failed to insert remote member locally", e)
                    }
                } else {
                    val localName = email.substringBefore("@").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val newMember = Member(
                        name = localName,
                        email = email.trim(),
                        password = "RecoveredSession",
                        role = assignedRole,
                        status = "Active",
                        totalSavings = 0.0,
                        membershipNo = ""
                    )
                    try {
                        repository.insertMember(newMember)
                    } catch (e: Exception) {
                        android.util.Log.e("SavingsViewModel", "Failed to insert member remotely, falling back to local-only insert", e)
                        try {
                            repository.insertMemberLocally(newMember)
                        } catch (ex: Exception) {
                            android.util.Log.e("SavingsViewModel", "Failed to insert member locally too", ex)
                        }
                    }
                    val updatedList = repository.getAllMembersDirect()
                    foundMember = updatedList.find { it.email.trim().equals(email.trim(), ignoreCase = true) }
                }
            }

            if (foundMember != null) {
                if (foundMember.status == "Suspended") {
                    _googleLoginLoading.value = false
                    _googleLoginError.value = "Your account is suspended. Please contact Admin."
                } else {
                    // SECURE UPGRADE: Do NOT set _currentUserId.value or log the user in here.
                    // This keeps the user on the Sign In page visually while they interact with the Reset Password dialog.
                    // The password update operation will complete login upon successful password change.

                    // Comment: Open the reset password dialog immediately since the session is recovery type
                    _showResetPasswordDialog.value = true
                    _googleLoginLoading.value = false
                }
            } else {
                _googleLoginLoading.value = false
                _googleLoginError.value = "Error establishing local profile."
            }
        }
    }

    // Comment: Trigger a password recovery/reset request via Supabase Auth
    fun recoverPassword(email: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (email.isBlank()) {
                onResult(false, "Please enter your email address to reset your password.")
                return@launch
            }
            val result = com.example.data.SupabaseClient.recoverPassword(email)
            onResult(result.success, result.message)
        }
    }

    // Comment: Validate credentials and establish an active logged-in session for a Member with Supabase Auth
    fun login(email: String, password: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            // Authenticate with Supabase GoTrue Auth
            val supabaseResult = com.example.data.SupabaseClient.signInWithPassword(email, password)
            if (!supabaseResult.success) {
                onResult(false, supabaseResult.message)
                return@launch
            }

            // Comment: Store the successfully authenticated email address in local SharedPreferences for pre-filling
            com.example.data.SupabaseClient.saveRegisteredEmail(email)

            val membersList = repository.getAllMembersDirect()
            var foundMember = membersList.find { it.email.trim().equals(email.trim(), ignoreCase = true) }

            if (foundMember == null) {
                // Determine the role dynamically based on whether any custom user has already signed up in Supabase
                val assignedRole = determineRoleForNewUser()

                // Let's check if the member already exists in the remote database first
                var remoteMember: Member? = null
                try {
                    val remoteMembers = com.example.data.SupabaseClient.dbFetchMembers()
                    remoteMember = remoteMembers.find { it.email.trim().equals(email.trim(), ignoreCase = true) }
                } catch (e: Exception) {
                    android.util.Log.w("SavingsViewModel", "Failed to fetch remote members from Supabase: ${e.localizedMessage}")
                }

                if (remoteMember != null) {
                    // Remote member exists! Let's insert them directly into our local database to preserve their real ID.
                    repository.insertMemberLocally(remoteMember)
                    foundMember = remoteMember
                } else {
                    // Dynamically create a local member profile for the authenticated Supabase user
                    val localName = email.substringBefore("@").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val newMember = Member(
                        name = localName,
                        email = email.trim(),
                        password = password,
                        role = assignedRole,
                        status = "Active",
                        totalSavings = 0.0,
                        membershipNo = ""
                    )
                    repository.insertMember(newMember)
                    val updatedList = repository.getAllMembersDirect()
                    foundMember = updatedList.find { it.email.trim().equals(email.trim(), ignoreCase = true) }
                }
            }

            if (foundMember != null) {
                if (foundMember.status == "Suspended") {
                    onResult(false, "Your account is suspended. Please contact Admin.")
                } else {
                    _currentUserId.value = foundMember.id
                    // Update profile name and membership number in settings to match logged-in user for visual consistency
                    val settings = repository.getSettingsDirect() ?: AppSettings()
                    repository.updateSettings(settings.copy(profileName = foundMember.name, membershipNo = foundMember.membershipNo))

                    // Comment: Sync data on successful authentication
                    repository.syncWithSupabase()

                    onResult(true, "Welcome back, ${foundMember.name}!")
                }
            } else {
                onResult(false, "Failed to create or retrieve local user profile.")
            }
        }
    }

    // Comment: Register a new member inside Supabase and the Room database securely
    fun signUp(name: String, email: String, password: String, membershipNo: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (name.isBlank() || email.isBlank() || password.isBlank()) {
                onResult(false, "Please fill in all required fields (Name, Email, Password).")
                return@launch
            }

            // Authenticate with Supabase GoTrue Auth signUp
            val supabaseResult = com.example.data.SupabaseClient.signUp(email, password)
            if (!supabaseResult.success) {
                onResult(false, supabaseResult.message)
                return@launch
            }

            // Comment: Store the successfully registered email address in local SharedPreferences for pre-filling
            com.example.data.SupabaseClient.saveRegisteredEmail(email)

            val membersList = repository.getAllMembersDirect()
            val alreadyExists = membersList.any { it.email.trim().equals(email.trim(), ignoreCase = true) }

            // Determine the role dynamically based on whether any custom user has already signed up in Supabase
            val assignedRole = determineRoleForNewUser()

            var localMemberId: Int? = null
            if (!alreadyExists) {
                val newMember = Member(
                    name = name.trim(),
                    email = email.trim(),
                    password = password,
                    role = assignedRole,
                    status = "Active",
                    totalSavings = 0.0,
                    membershipNo = membershipNo.trim()
                )
                repository.insertMember(newMember)
                val updatedList = repository.getAllMembersDirect()
                localMemberId = updatedList.find { it.email.trim().equals(email.trim(), ignoreCase = true) }?.id
            } else {
                val existingMember = membersList.find { it.email.trim().equals(email.trim(), ignoreCase = true) }
                if (existingMember != null) {
                    localMemberId = existingMember.id
                    // Comment: Update the existing local member record's name and membershipNo with the newly registered details
                    val updatedMember = existingMember.copy(
                        name = name.trim(),
                        membershipNo = membershipNo.trim()
                    )
                    repository.updateMember(updatedMember)
                }
            }

            // Comment: Do NOT auto-login after a successful signUp as per instructions.
            // Return success so the AuthScreen can redirect the user to the Sign In page and keep the pre-filled email.
            if (localMemberId != null) {
                onResult(true, "Account registered successfully via Supabase!")
            } else {
                onResult(false, "Failed to register local member profile.")
            }
        }
    }

    // Comment: Handle Google Login Deep Link Callback tokens, fetch user profile, create DB entry, and log in
    fun handleGoogleLoginCallback(
        accessToken: String,
        refreshToken: String,
        expiresIn: String,
        tokenType: String
    ) {
        viewModelScope.launch {
            _googleLoginLoading.value = true
            _googleLoginError.value = null

            val result = com.example.data.SupabaseClient.fetchUserAndSaveSession(
                accessToken,
                refreshToken,
                expiresIn,
                tokenType
            )

            if (!result.success) {
                _googleLoginLoading.value = false
                _googleLoginError.value = result.message
                return@launch
            }

            val email = result.userEmail
            if (email.isNullOrBlank()) {
                _googleLoginLoading.value = false
                _googleLoginError.value = "Google profile does not contain an email."
                return@launch
            }

            // Comment: Store the successfully authenticated Google email address in local SharedPreferences for pre-filling
            com.example.data.SupabaseClient.saveRegisteredEmail(email)

            // Perform the local profile setup and login
            val membersList = repository.getAllMembersDirect()
            var foundMember = membersList.find { it.email.trim().equals(email.trim(), ignoreCase = true) }

            if (foundMember == null) {
                // Determine the role dynamically based on whether any custom user has already signed up in Supabase
                val assignedRole = determineRoleForNewUser()

                // Let's check if the member already exists in the remote database first
                var remoteMember: Member? = null
                try {
                    val remoteMembers = com.example.data.SupabaseClient.dbFetchMembers()
                    remoteMember = remoteMembers.find { it.email.trim().equals(email.trim(), ignoreCase = true) }
                } catch (e: Exception) {
                    android.util.Log.w("SavingsViewModel", "Failed to fetch remote members from Supabase: ${e.localizedMessage}")
                }

                if (remoteMember != null) {
                    // Remote member exists! Let's insert them directly into our local database to preserve their real ID.
                    repository.insertMemberLocally(remoteMember)
                    foundMember = remoteMember
                } else {
                    // Dynamically create a local member profile for the authenticated Supabase user
                    val localName = email.substringBefore("@").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val newMember = Member(
                        name = localName,
                        email = email.trim(),
                        password = "GoogleOAuth",
                        role = assignedRole,
                        status = "Active",
                        totalSavings = 0.0,
                        membershipNo = ""
                    )
                    repository.insertMember(newMember)
                    val updatedList = repository.getAllMembersDirect()
                    foundMember = updatedList.find { it.email.trim().equals(email.trim(), ignoreCase = true) }
                }
            }

            if (foundMember != null) {
                if (foundMember.status == "Suspended") {
                    _googleLoginLoading.value = false
                    _googleLoginError.value = "Your account is suspended. Please contact Admin."
                } else {
                    _currentUserId.value = foundMember.id
                    // Update profile name and membership number in settings to match logged-in user for visual consistency
                    val settings = repository.getSettingsDirect() ?: AppSettings()
                    repository.updateSettings(settings.copy(profileName = foundMember.name, membershipNo = foundMember.membershipNo))

                    // Comment: Sync data on successful authentication
                    repository.syncWithSupabase()

                    _googleLoginLoading.value = false
                }
            } else {
                _googleLoginLoading.value = false
                _googleLoginError.value = "Error establishing local profile."
            }
        }
    }

    // Comment: Log out the current user and clear the active session
    fun logout() {
        com.example.data.SupabaseClient.clearSession()
        _currentUserId.value = null
    }

    companion object {
        val seedEmails = setOf(
            "sarah.j@example.com",
            "m.reyes@example.com",
            "elena.r@example.com",
            "d.chen@example.com",
            "amanda@example.com",
            "jane.d@example.com",
            "r.smith@example.com",
            "ev.lin@example.com",
            "john.doe@example.com",
            "test@email.com"
        )
    }
}
