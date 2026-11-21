package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.AppSettings
import com.example.data.model.Member
import com.example.data.model.Savings
import com.example.data.model.ChangeRequest
import com.example.data.model.BankDeposit
import com.example.data.model.WeeklyGoalUpdateResult
import com.example.data.repository.ProfilePictureRepository
import com.example.data.repository.SavingsRepository
import com.example.ui.notification.AdminNotificationHelper
import com.example.util.Constants
import com.example.util.BankLedger
import com.example.util.PasswordValidator
import com.example.util.ProfileImageCrop
import com.example.util.ProfileImageProcessor
import com.example.util.ProfileImageResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ViewModel for managing the application states, settings, and business logic.
 * Handles search queries, database persistence triggers, and UI reactive state mapping.
 */
class SavingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: SavingsRepository

    // Comment: Store the currently logged-in member ID dynamically (starts null meaning logged out)
    private val _currentUserId = MutableStateFlow<Int?>(null)
    val currentUserId: StateFlow<Int?> = _currentUserId.asStateFlow()

    // Comment: Startup auth has three states, not two: a saved session may still be resolving its
    // local member. Showing AuthScreen during that window made a valid session look like a hang.
    private val _isRestoringSession = MutableStateFlow(true)
    val isRestoringSession: StateFlow<Boolean> = _isRestoringSession.asStateFlow()

    // Comment: A session exists but its member profile could not be resolved (usually offline).
    // Keep the restoring screen with Retry instead of pretending this is a logged-out user.
    private val _sessionRestoreError = MutableStateFlow<String?>(null)
    val sessionRestoreError: StateFlow<String?> = _sessionRestoreError.asStateFlow()

    // Comment: One-shot explanation shown above the login form when a saved session is cleared
    // because its refresh token was rejected or the stored session could not be read.
    private val _authNotice = MutableStateFlow<String?>(null)
    val authNotice: StateFlow<String?> = _authNotice.asStateFlow()

    // Comment: Store and expose the logged-in user's email dynamically to resolve duplicate account issues
    val currentMemberEmail: StateFlow<String?>

    // Comment: Expose loading and error states for Google OAuth login process
    private val _googleLoginLoading = MutableStateFlow(false)
    val googleLoginLoading: StateFlow<Boolean> = _googleLoginLoading.asStateFlow()

    private val _googleLoginError = MutableStateFlow<String?>(null)
    val googleLoginError: StateFlow<String?> = _googleLoginError.asStateFlow()

    // Comment: Bumped after every local picture write or delete so the profile surfaces reload the
    // file even though the path itself is stable. The upload never gates this state.
    private val _profilePictureRevision = MutableStateFlow(0)
    val profilePictureRevision: StateFlow<Int> = _profilePictureRevision.asStateFlow()
    // Comment: A local save is not the same as a confirmed public upload. This state keeps the
    // distinction visible in Profile Information without hiding the device-local preview.
    private val _profilePictureUploadError = MutableStateFlow<String?>(null)
    val profilePictureUploadError: StateFlow<String?> = _profilePictureUploadError.asStateFlow()
    // Comment: Serialize picture writes so a slow background upload can never finish after a delete
    // and resurrect the old object. The UI reports the local save before the upload starts, so this
    // lock never makes the screen wait for the network.
    private val profilePictureMutex = Mutex()

    // Comment: For backwards compatibility, get active user's ID or fallback to -1 (No User)
    val currentUserMemberId: Int get() = _currentUserId.value ?: -1

    // UI state flows from Room
    val allMembers: StateFlow<List<Member>>
    val allSavings: StateFlow<List<Savings>>
    val currentUserSavings: StateFlow<List<Savings>>
    val appSettings: StateFlow<AppSettings>
    val allChangeRequests: StateFlow<List<ChangeRequest>>

    // Comment: Bank deposit ledger (money moved from collected cash into the bank) and its derived figures
    val allBankDeposits: StateFlow<List<BankDeposit>>
    val totalCollected: StateFlow<Double>
    val totalBankDeposited: StateFlow<Double>
    val cashInHand: StateFlow<Double>

    // Search query for members list
    private val _memberSearchQuery = MutableStateFlow("")
    val memberSearchQuery: StateFlow<String> = _memberSearchQuery

    // Filtered members for Search screen
    val filteredMembers: StateFlow<List<Member>>

    // Admin member contribution form states
    private val _selectedMemberForContribution = MutableStateFlow<Member?>(null)
    val selectedMemberForContribution: StateFlow<Member?> = _selectedMemberForContribution

    // Admin bank deposition form states (the depositor is picked from the member list)
    private val _selectedMemberForBankDeposit = MutableStateFlow<Member?>(null)
    val selectedMemberForBankDeposit: StateFlow<Member?> = _selectedMemberForBankDeposit

    // Admin panel accordion open/close state: only one section can be open at a time (null means all closed)
    private val _adminOpenSection = MutableStateFlow<String?>(null)
    val adminOpenSection: StateFlow<String?> = _adminOpenSection.asStateFlow()

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

    init {
        val database = AppDatabase.getDatabase(application)
        repository = SavingsRepository(
            database.memberDao(),
            database.savingsDao(),
            database.appSettingsDao(),
            database.changeRequestDao(),
            database.bankDepositDao(),
            database.communitySettingsDao()
        )

        // Seed data asynchronously
        viewModelScope.launch {
            // Comment: Startup housekeeping must never strand the auth gate on the restoring screen.
            // Even if seeding/settings fail, still attempt the saved-session restore below.
            try {
                repository.populateInitialDataIfEmpty()

                // Comment: Dynamically update any pre-existing database default savings goal from 5000.0 to 500.0 for a consistent user experience
                repository.appSettings.firstOrNull()?.let { settings ->
                    if (settings.personalGoal == 5000.0) {
                        repository.updateSettings(settings.copy(personalGoal = 500.0))
                    }
                }

                // Comment: Schedule the weekly reminder once on startup from the persisted settings (single source of truth)
                repository.getSettingsDirect()?.let { startupSettings ->
                    com.example.ui.notification.NotificationScheduler.scheduleNotification(getApplication(), startupSettings)
                }
            } catch (e: Exception) {
                android.util.Log.e("SavingsViewModel", "Startup preparation failed", e)
            }

            // Comment: Restore from the local member before any network work. The previous code
            // awaited a full sync first, which left the login screen visible while a valid session
            // was still being checked. The full sync now starts after the app is usable.
            restoreSavedSession()

            // Comment: A failed or interrupted avatar upload leaves a pending marker; retry it on
            // this launch without making the already-cached local preview wait for the network.
            retryPendingProfilePictureUpload()
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
            val filtered = members.filter { it.email.trim().lowercase() !in Constants.SEED_EMAILS }
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
                .filter { it.email.trim().lowercase() in Constants.SEED_EMAILS }
                .map { it.id }
                .toSet()
            val filteredSavings = savingsList.filter { it.memberId !in seedMemberIds }

            val remainingMembers = members.filter { it.email.trim().lowercase() !in Constants.SEED_EMAILS }
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
                        .filter { it.email.trim().lowercase() !in Constants.SEED_EMAILS }
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

        // Comment: Bank deposit ledger state, plus the derived totals. Cash in Hand = everything collected from
        // members minus everything banked, so it can legitimately go negative if more was banked than collected.
        allBankDeposits = repository.allBankDeposits
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        totalCollected = allMembers
            .map { membersList -> membersList.sumOf { it.totalSavings } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

        totalBankDeposited = allBankDeposits
            .map { deposits -> BankLedger.totalDeposited(deposits) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

        cashInHand = combine(totalCollected, totalBankDeposited) { collected, banked ->
            BankLedger.cashInHand(collected, banked)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

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

    // Set selected member in Admin bank deposition form
    fun selectMemberForBankDeposit(member: Member?) {
        _selectedMemberForBankDeposit.value = member
    }

    // Comment: Open the given Admin Panel accordion section, closing the previously open one (only one at a time)
    fun toggleAdminSection(section: String) {
        _adminOpenSection.value = if (_adminOpenSection.value == section) null else section
    }

    // Comment: Open the given Admin Panel accordion section without toggling (used by deep-link navigation)
    fun openAdminSection(section: String) {
        _adminOpenSection.value = section
    }

    // Comment: Close all Admin Panel accordion sections (used when the user navigates to another page)
    fun closeAllAdminSections() {
        _adminOpenSection.value = null
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

    // Add bank deposit (the UI only exposes this to admins)
    fun addBankDeposit(memberId: Int, memberName: String, amount: Double, dateText: String) {
        viewModelScope.launch {
            val deposit = BankDeposit(
                amount = amount,
                dateText = dateText,
                depositedById = memberId,
                depositedByName = memberName
            )
            repository.insertBankDeposit(deposit)
        }
    }

    // Edit bank deposit (the UI only exposes this to admins)
    fun updateBankDeposit(deposit: BankDeposit) {
        viewModelScope.launch {
            repository.updateBankDeposit(deposit)
        }
    }

    // Delete bank deposit (the UI only exposes this to admins)
    fun deleteBankDeposit(deposit: BankDeposit) {
        viewModelScope.launch {
            repository.deleteBankDeposit(deposit)
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

    // Comment: Move the app-wide text step by one. Clamp here as well as on read so a caller can
    // never store a value outside the range the layouts were measured for.
    fun setFontSizeStep(step: Int) {
        viewModelScope.launch {
            val current = repository.getSettingsDirect() ?: AppSettings()
            repository.updateSettings(current.copy(fontSizeStep = Constants.clampFontSizeStep(step)))
        }
    }

    // Comment: Every member's Weekly Savings Goal, keyed by their lowercased email, read from the single shared table
    // so the Members list, history dialogs and Admin Panel all use each member's own goal instead of one society-wide
    // value. Falls back to an empty map (callers default to 500৳) while the shared rows are still loading.
    val weeklyGoals: StateFlow<Map<String, Double>> = repository.communitySettings
        .map { rows -> rows.associate { it.memberEmail.lowercase() to it.weeklyGoal } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Comment: The signed-in member's own Weekly Savings Goal, so the Personal Dashboard's Total Due / Projected
    // Savings match the Members list. Resolved via the current member's email and falling back to the 500৳ default
    // while the shared rows load or when the member has no row yet.
    val weeklyGoal: StateFlow<Double> = combine(repository.communitySettings, currentMemberEmail) { rows, email ->
        val key = email?.trim()?.lowercase()
        rows.find { it.memberEmail.lowercase() == key }?.weeklyGoal ?: 500.0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 500.0)

    // Comment: Admin-only update of the per-member Weekly Savings Goals; each write is pushed to Supabase and the
    // members' readers pick them up on their next sync. The outcome is reported back so the Admin Panel can say whether
    // the goals reached everyone, are still waiting to sync, or were beaten by a newer value set on another device.
    fun setCommunityWeeklyGoals(goals: Map<String, Double>, onResult: (WeeklyGoalUpdateResult) -> Unit) {
        viewModelScope.launch {
            onResult(repository.updateCommunityWeeklyGoals(goals))
        }
    }

    // Update settings: Profile details
    fun updateProfileInfo(name: String, membershipNo: String, profileImageUrl: String? = null, mobileNo: String = "") {
        viewModelScope.launch {
            val current = repository.getSettingsDirect() ?: AppSettings()
            val member = repository.getMemberById(currentUserMemberId)

            // Comment: Picture bytes are prepared and uploaded by the dedicated profile-picture flow;
            // this method only records a public URL, or keeps the current public URL. A stale local
            // file path is never written to the shared settings row.
            val img = (profileImageUrl ?: current.profileImageUrl)?.takeIf { it.startsWith("http") }
                ?: member?.avatarUrl

            // Comment: The input box holds digits only (the +88 prefix is shown separately), so always persist the
            // full number with the immutable country code regardless of which profile field triggered the save
            val normalizedMobileNo = if (mobileNo.isBlank() || mobileNo.startsWith("+88")) mobileNo else "+88${mobileNo.filter(Char::isDigit)}"

            repository.updateSettings(current.copy(profileName = name, membershipNo = membershipNo, profileImageUrl = img, mobileNo = normalizedMobileNo))
            // Also update current user member name, avatarUrl, membershipNo, and mobileNo in the database
            // so the shared members row (visible to admins) carries the mobile number for Call/Message actions
            if (member != null) {
                repository.updateMember(member.copy(name = name, avatarUrl = img, membershipNo = membershipNo, mobileNo = normalizedMobileNo))
            }

            // Comment: A picture stored before the 50 KB rule gets one re-compress on its next profile save.
            recompressExistingProfilePictureIfNeeded()
        }
    }

    // Comment: Save the crop page's result: local file first (so it survives a restart and appears at once),
    // then the public avatars object {user_id}.jpg in the background. The screen never waits for the upload.
    fun saveProfilePicture(uri: Uri, crop: ProfileImageCrop, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            profilePictureMutex.withLock {
                val app = getApplication<Application>()
                val userId = com.example.data.SupabaseClient.getCurrentUserUuid()
                if (userId.isNullOrBlank()) {
                    onResult(false, ProfilePictureRepository.UNREADABLE_MESSAGE)
                    return@withLock
                }

                when (val result = ProfileImageProcessor.processCropped(app, uri, crop)) {
                    is ProfileImageResult.Success -> {
                        val file = ProfilePictureRepository.localFile(app, userId)
                        if (!ProfilePictureRepository.writeLocal(file, result.bytes)) {
                            onResult(false, ProfilePictureRepository.UNREADABLE_MESSAGE)
                            return@withLock
                        }

                        // Comment: Record the pending public copy before exposing the local preview,
                        // so an upload failure or process death has a durable retry path. The local
                        // preview itself is shown immediately and never waits for the upload.
                        ProfilePictureRepository.markUploadPending(app, userId)
                        _profilePictureUploadError.value = null
                        _profilePictureRevision.value += 1
                        onResult(true, "Picture saved on this phone. Uploading…")

                        // Comment: Keep the previous public URL (or null) until the upload succeeds. The
                        // device-local file above is already what the profile surface displays; a local
                        // path must never be synced to the shared app_settings row. The upload runs in
                        // this same locked coroutine, so a later delete cannot be overtaken by it.
                        val settings = repository.getSettingsDirect() ?: AppSettings()
                        val currentUrl = settings.profileImageUrl?.takeIf { it.startsWith("http") }
                        if (currentUrl != settings.profileImageUrl) {
                            repository.updateSettings(settings.copy(profileImageUrl = currentUrl))
                        }

                        uploadAndRecord(app, userId, result.bytes)
                    }
                    ProfileImageResult.Unreadable -> onResult(false, ProfilePictureRepository.UNREADABLE_MESSAGE)
                }
            }
        }
    }

    // Comment: Delete the local file and the stored {user_id}.jpg object, clear the settings/member
    // avatar and bump the revision so the placeholder returns immediately.
    fun deleteProfilePicture(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            profilePictureMutex.withLock {
                val app = getApplication<Application>()
                val userId = com.example.data.SupabaseClient.getCurrentUserUuid()
                if (userId.isNullOrBlank()) {
                    onResult(false, ProfilePictureRepository.UNREADABLE_MESSAGE)
                    return@withLock
                }

                // Comment: A deletion supersedes any pending public upload for this user.
                ProfilePictureRepository.clearUploadPending(app, userId)
                _profilePictureUploadError.value = null
                ProfilePictureRepository.deleteLocal(ProfilePictureRepository.localFile(app, userId))
                _profilePictureRevision.value += 1

                val settings = repository.getSettingsDirect() ?: AppSettings()
                repository.updateSettings(settings.copy(profileImageUrl = null))
                val member = repository.getMemberById(currentUserMemberId)
                if (member != null) {
                    repository.updateMember(member.copy(avatarUrl = null))
                }

                val remoteDeleted = ProfilePictureRepository.deleteRemote(userId)
                if (remoteDeleted) {
                    onResult(true, "Profile picture removed.")
                } else {
                    onResult(false, "Picture removed on this phone, but the stored copy could not be deleted.")
                }
            }
        }
    }

    // Comment: One-time path for an older local picture over 50 KB. It re-encodes the whole image and
    // replaces the object, without touching a newer picture the user just saved.
    private fun recompressExistingProfilePictureIfNeeded() {
        viewModelScope.launch {
            profilePictureMutex.withLock {
                val app = getApplication<Application>()
                val userId = com.example.data.SupabaseClient.getCurrentUserUuid() ?: return@withLock
                val file = ProfilePictureRepository.existingLocalFile(app, userId) ?: return@withLock
                if (!ProfilePictureRepository.isOverLimit(file)) return@withLock

                // Comment: Only a local-only legacy picture needs this repair. If the shared row already
                // points at a public URL, the local file may be stale and must not overwrite that object.
                val settings = repository.getSettingsDirect() ?: return@withLock
                if (settings.profileImageUrl?.startsWith("http") == true) return@withLock

                val bytes = ProfilePictureRepository.recompressWholeImage(app, userId) ?: return@withLock
                if (!ProfilePictureRepository.writeLocal(file, bytes)) return@withLock
                ProfilePictureRepository.markUploadPending(app, userId)
                _profilePictureUploadError.value = null
                _profilePictureRevision.value += 1
                uploadAndRecord(app, userId, bytes)
            }
        }
    }

    // Comment: Retry a persisted avatar upload at launch, after sign-in, or when Profile
    // Information opens. It is a no-op when no upload is pending.
    fun retryPendingProfilePictureUpload() {
        viewModelScope.launch {
            profilePictureMutex.withLock {
                val app = getApplication<Application>()
                val userId = com.example.data.SupabaseClient.getCurrentUserUuid() ?: return@withLock
                if (!ProfilePictureRepository.hasPendingUpload(app, userId)) return@withLock
                retryPendingUploadLocked(app, userId)
            }
        }
    }

    // Comment: User-triggered retry from the visible failure message. It also works when the
    // pending marker itself could not be written, because the local JPEG is still present.
    fun retryProfilePictureUpload() {
        viewModelScope.launch {
            profilePictureMutex.withLock {
                val app = getApplication<Application>()
                val userId = com.example.data.SupabaseClient.getCurrentUserUuid() ?: return@withLock
                ProfilePictureRepository.markUploadPending(app, userId)
                retryPendingUploadLocked(app, userId)
            }
        }
    }

    private suspend fun retryPendingUploadLocked(app: Application, userId: String) {
        val file = ProfilePictureRepository.existingLocalFile(app, userId)
        if (file == null) {
            // Comment: The user deleted the picture after the upload failed; there is nothing to send.
            ProfilePictureRepository.clearUploadPending(app, userId)
            _profilePictureUploadError.value = null
            return
        }

        val bytes = ProfilePictureRepository.readLocal(file)
        if (bytes == null) {
            _profilePictureUploadError.value = ProfilePictureRepository.UPLOAD_FAILURE_MESSAGE
            return
        }
        uploadAndRecord(app, userId, bytes)
    }

    // Comment: Upload the local JPEG and only then record the public URL. A null result leaves the
    // pending marker in place and surfaces the failure; the local preview remains untouched.
    private suspend fun uploadAndRecord(app: Application, userId: String, bytes: ByteArray): Boolean {
        val publicUrl = ProfilePictureRepository.upload(userId, bytes)
        if (publicUrl == null) {
            _profilePictureUploadError.value = ProfilePictureRepository.UPLOAD_FAILURE_MESSAGE
            return false
        }

        return try {
            // Comment: Never apply an old account's completed upload to a newly signed-in account.
            // The old marker stays in place for that user's next sign-in.
            if (com.example.data.SupabaseClient.getCurrentUserUuid() != userId) return false

            val latest = repository.getSettingsDirect() ?: AppSettings()
            repository.updateSettings(latest.copy(profileImageUrl = publicUrl))
            val member = repository.getMemberById(currentUserMemberId)
                ?: com.example.data.SupabaseClient.getSessionEmail()?.let { email ->
                    repository.getAllMembersDirect().find {
                        it.email.trim().equals(email.trim(), ignoreCase = true)
                    }
                }
            if (member != null) {
                repository.updateMember(member.copy(avatarUrl = publicUrl))
            }
            ProfilePictureRepository.clearUploadPending(app, userId)
            _profilePictureUploadError.value = null
            _profilePictureRevision.value += 1
            true
        } catch (e: Exception) {
            android.util.Log.e("SavingsViewModel", "Profile picture uploaded but its link could not be saved", e)
            _profilePictureUploadError.value = ProfilePictureRepository.UPLOAD_FAILURE_MESSAGE
            false
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
            val updated = current.copy(
                enableNotifications = enabled,
                notificationDay = day,
                notificationTime = time,
                notificationText = text
            )
            repository.updateSettings(updated)
            // Comment: Schedule (or cancel) the weekly reminder from this single source of truth when notification settings change
            com.example.ui.notification.NotificationScheduler.scheduleNotification(getApplication(), updated)
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
                    // Comment: Sync immediately so the Approved removal is pushed to Supabase and the
                    // member's status is set to "Removed" remotely — propagating the removal to all devices
                    repository.syncWithSupabase()
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

    // Comment: New registrations always assume the Member role; Admin is granted only through admin approval flows
    private suspend fun determineRoleForNewUser(): String {
        return "Member"
    }

    // Comment: Store state to show the Reset/Update Password Dialog on deep link password recovery
    private val _showResetPasswordDialog = MutableStateFlow(false)
    val showResetPasswordDialog: StateFlow<Boolean> = _showResetPasswordDialog.asStateFlow()

    fun setResetPasswordDialogVisible(visible: Boolean) {
        _showResetPasswordDialog.value = visible
    }

    // Comment: Update the password for the currently logged-in user session in Supabase Auth (never stored locally)
    fun updatePassword(newPassword: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            // Comment: Enforce the minimum password policy through the shared validator (8+ characters with at least one letter and one number)
            PasswordValidator.validate(newPassword)?.let { error ->
                onResult(false, error)
                return@launch
            }

            // 1. Update Supabase Auth password
            val result = com.example.data.SupabaseClient.updatePassword(newPassword)
            if (!result.success) {
                onResult(false, result.message)
                return@launch
            }

            // 2. Keep the matched member profile in sync (passwords are managed only by Supabase Auth, never stored locally)
            val currentEmail = com.example.data.SupabaseClient.getSessionEmail()
            var loggedInMember: Member? = null
            if (!currentEmail.isNullOrBlank()) {
                val membersList = repository.getAllMembersDirect()
                val matchedMember = membersList.find { it.email.trim().equals(currentEmail.trim(), ignoreCase = true) }
                if (matchedMember != null) {
                    repository.updateMember(matchedMember)
                    loggedInMember = matchedMember

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
                    repository.updateSettings(settings.copy(profileName = loggedInMember.name, membershipNo = loggedInMember.membershipNo, mobileNo = loggedInMember.mobileNo))
                } catch (e: Exception) {
                    android.util.Log.e("SavingsViewModel", "Failed to update AppSettings after password update", e)
                }
                try {
                    repository.syncWithSupabase()
                } catch (e: Exception) {
                    android.util.Log.e("SavingsViewModel", "Failed to run post-login sync after password update", e)
                }

                // Comment: Retry a persisted avatar upload after this recovery login completes.
                retryPendingProfilePictureUpload()
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
                    // Comment: Include removed members so a removed account cannot sign back in and be re-created
                    val remoteMembers = com.example.data.SupabaseClient.dbFetchMembers(includeRemoved = true)
                    remoteMember = remoteMembers.find { it.email.trim().equals(email.trim(), ignoreCase = true) }
                } catch (e: Exception) {
                    android.util.Log.w("SavingsViewModel", "Failed to fetch remote members from Supabase: ${e.localizedMessage}")
                }

                // Comment: Block the recovery flow for an account that was removed by an admin
                if (remoteMember != null && remoteMember.status == Constants.REMOVED_STATUS) {
                    _googleLoginLoading.value = false
                    _googleLoginError.value = "Your account has been removed. Please contact Admin."
                    return@launch
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
                    // Comment: Include removed members so a removed account cannot sign back in and be re-created
                    val remoteMembers = com.example.data.SupabaseClient.dbFetchMembers(includeRemoved = true)
                    remoteMember = remoteMembers.find { it.email.trim().equals(email.trim(), ignoreCase = true) }
                } catch (e: Exception) {
                    android.util.Log.w("SavingsViewModel", "Failed to fetch remote members from Supabase: ${e.localizedMessage}")
                }

                // Comment: Block sign-in for an account that was removed by an admin
                if (remoteMember != null && remoteMember.status == Constants.REMOVED_STATUS) {
                    onResult(false, "Your account has been removed. Please contact Admin.")
                    return@launch
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
                    // Restore the remote member avatar while rebuilding local settings after a data reset
                    val settings = repository.getSettingsDirect() ?: AppSettings()
                    repository.updateSettings(settings.copy(profileName = foundMember.name, membershipNo = foundMember.membershipNo, mobileNo = foundMember.mobileNo, profileImageUrl = foundMember.avatarUrl ?: settings.profileImageUrl?.takeIf { it.startsWith("http") }))

                    // Comment: Sync data on successful authentication
                    repository.syncWithSupabase()

                    // Comment: The startup retry may have run before this session existed.
                    retryPendingProfilePictureUpload()
                    onResult(true, "Welcome back, ${foundMember.name}!")
                }
            } else {
                onResult(false, "Failed to create or retrieve local user profile.")
            }
        }
    }

    // Comment: Register a new member inside Supabase and the Room database securely
    fun signUp(name: String, email: String, password: String, membershipNo: String, mobileNo: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            if (name.isBlank() || email.isBlank() || password.isBlank()) {
                onResult(false, "Please fill in all required fields (Name, Email, Password).")
                return@launch
            }

            // Comment: Enforce the minimum password policy through the shared validator (8+ characters with at least one letter and one number)
            PasswordValidator.validate(password)?.let { error ->
                onResult(false, error)
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

            // Comment: Build the full mobile number with country code once so it can be stored on both
            // the members row (shared, visible to admins) and app_settings (current user only)
            val fullMobileNo = if (mobileNo.isBlank()) "" else "+88${mobileNo.filter(Char::isDigit)}"

            var localMemberId: Int? = null
            if (!alreadyExists) {
                val newMember = Member(
                    name = name.trim(),
                    email = email.trim(),
                    role = assignedRole,
                    status = "Active",
                    totalSavings = 0.0,
                    membershipNo = membershipNo.trim(),
                    mobileNo = fullMobileNo
                )
                repository.insertMember(newMember)
                val updatedList = repository.getAllMembersDirect()
                localMemberId = updatedList.find { it.email.trim().equals(email.trim(), ignoreCase = true) }?.id
            } else {
                val existingMember = membersList.find { it.email.trim().equals(email.trim(), ignoreCase = true) }
                if (existingMember != null) {
                    localMemberId = existingMember.id
                    // Comment: Update the existing local member record's name, membershipNo, and mobileNo with the newly registered details
                    val updatedMember = existingMember.copy(
                        name = name.trim(),
                        membershipNo = membershipNo.trim(),
                        mobileNo = fullMobileNo
                    )
                    repository.updateMember(updatedMember)
                }
            }

            // Comment: Do NOT auto-login after a successful signUp as per instructions.
            // Return success so the AuthScreen can redirect the user to the Sign In page and keep the pre-filled email.
            if (localMemberId != null) {
                // Comment: Persist the mobile number entered during sign up into app_settings so it syncs
                // to the Supabase app_settings.mobile_no column (pushed when a session exists, synced later otherwise)
                try {
                    val settings = repository.getSettingsDirect() ?: AppSettings()
                    repository.updateSettings(settings.copy(mobileNo = fullMobileNo))
                } catch (e: Exception) {
                    android.util.Log.e("SavingsViewModel", "Failed to save mobile number during signup", e)
                }
                onResult(true, "Account registered successfully via Supabase!")
            } else {
                onResult(false, "Failed to register local member profile.")
            }
        }
    }

    // Comment: Native Google sign-in. The ID token is exchanged with Supabase using the raw nonce;
    // the local member/settings setup is shared with the old deep-link completion path.
    fun signInWithGoogle(idToken: String, rawNonce: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _googleLoginLoading.value = true
            _googleLoginError.value = null
            try {
                val result = com.example.data.SupabaseClient.signInWithGoogleIdToken(idToken, rawNonce)
                if (!result.success) {
                    _googleLoginError.value = result.message
                    onResult(false, result.message)
                    return@launch
                }

                val email = result.userEmail
                if (email.isNullOrBlank()) {
                    val message = "Google profile does not contain an email."
                    _googleLoginError.value = message
                    onResult(false, message)
                    return@launch
                }

                // Comment: Store the successfully authenticated Google email address for pre-filling
                com.example.data.SupabaseClient.saveRegisteredEmail(email)
                completeExternalSignIn(email, onResult)
            } catch (e: Exception) {
                val message = "Network error: ${e.localizedMessage ?: "Unknown error"}"
                _googleLoginError.value = message
                onResult(false, message)
            } finally {
                // Comment: The busy flag is released on success, cancellation, exception and timeout alike.
                _googleLoginLoading.value = false
            }
        }
    }

    // Comment: Finish an external sign-in by finding or creating the local member and adopting its profile.
    private suspend fun completeExternalSignIn(email: String, onResult: (Boolean, String) -> Unit) {
        val membersList = repository.getAllMembersDirect()
        var foundMember = membersList.find { it.email.trim().equals(email.trim(), ignoreCase = true) }

        if (foundMember == null) {
            val assignedRole = determineRoleForNewUser()

            // Comment: Check the remote table first so a member who exists on another device keeps their real id
            var remoteMember: Member? = null
            try {
                // Comment: Include removed members so a removed account cannot sign back in and be re-created
                val remoteMembers = com.example.data.SupabaseClient.dbFetchMembers(includeRemoved = true)
                remoteMember = remoteMembers.find { it.email.trim().equals(email.trim(), ignoreCase = true) }
            } catch (e: Exception) {
                android.util.Log.w("SavingsViewModel", "Failed to fetch remote members from Supabase: ${e.localizedMessage}")
            }

            // Comment: Block sign-in for an account that was removed by an admin
            if (remoteMember != null && remoteMember.status == Constants.REMOVED_STATUS) {
                val message = "Your account has been removed. Please contact Admin."
                _googleLoginError.value = message
                onResult(false, message)
                return
            }

            if (remoteMember != null) {
                // Remote member exists; insert locally to preserve their real id
                repository.insertMemberLocally(remoteMember)
                foundMember = remoteMember
            } else {
                val localName = email.substringBefore("@").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                val newMember = Member(
                    name = localName,
                    email = email.trim(),
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

        if (foundMember == null) {
            val message = "Error establishing local profile."
            _googleLoginError.value = message
            onResult(false, message)
            return
        }

        if (foundMember.status == "Suspended") {
            val message = "Your account is suspended. Please contact Admin."
            _googleLoginError.value = message
            onResult(false, message)
            return
        }

        _currentUserId.value = foundMember.id
        // Comment: Restore the remote member avatar while rebuilding local settings after a data reset
        val settings = repository.getSettingsDirect() ?: AppSettings()
        repository.updateSettings(
            settings.copy(
                profileName = foundMember.name,
                membershipNo = foundMember.membershipNo,
                mobileNo = foundMember.mobileNo,
                profileImageUrl = foundMember.avatarUrl ?: settings.profileImageUrl?.takeIf { it.startsWith("http") }
            )
        )

        // Comment: A sync failure must not turn a successful sign-in into a failed one; local data is usable offline.
        try {
            repository.syncWithSupabase()
        } catch (e: Exception) {
            android.util.Log.w("SavingsViewModel", "Post-sign-in sync failed: ${e.localizedMessage}")
        }

        // Comment: A pending upload from an earlier session is retried once the user is signed in.
        retryPendingProfilePictureUpload()
        onResult(true, "Welcome back, ${foundMember.name}!")
    }

    // Comment: Restore a saved session from the local Room member first. AuthScreen must only be
    // shown when there is definitely no session or the refresh token has been rejected.
    private suspend fun restoreSavedSession() {
        try {
            if (com.example.data.SupabaseClient.getSession() == null) {
                _isRestoringSession.value = false
                return
            }

            val savedEmail = com.example.data.SupabaseClient.getSessionEmail()
            if (savedEmail.isNullOrBlank()) {
                endSessionRestoreWithLogin("Your saved session could not be restored. Please sign in again.")
                return
            }

            val localMember = findLocalMemberByEmail(savedEmail)
            if (localMember != null) {
                adoptRestoredMember(localMember)
                _sessionRestoreError.value = null
                _isRestoringSession.value = false
                startBackgroundSessionSync(savedEmail)
                return
            }

            // Comment: The local row can be missing after a database reset. Give the full sync one
            // chance to pull this member by email; keep the restoring screen (not login) meanwhile.
            if (!refreshExpiringSessionForRestore()) return
            repository.syncWithSupabase()
            if (com.example.data.SupabaseClient.getSession() == null) {
                endSessionRestoreWithLogin("Your session has expired. Please sign in again.")
                return
            }

            val syncedMember = findLocalMemberByEmail(savedEmail)
            if (syncedMember != null) {
                adoptRestoredMember(syncedMember)
                _sessionRestoreError.value = null
                _isRestoringSession.value = false
                return
            }

            _sessionRestoreError.value = "We couldn't restore your member profile. Check your connection and try again."
        } catch (e: Exception) {
            android.util.Log.e("SavingsViewModel", "Failed to restore the saved session", e)
            _sessionRestoreError.value = "We couldn't restore your session. Check your connection and try again."
        }
    }

    // Comment: Refresh an expiring access token before the one-shot missing-member sync. A real
    // rejection clears the session and shows login with an explanation; a network failure keeps
    // the restoring screen retryable instead of signing the user out.
    private suspend fun refreshExpiringSessionForRestore(): Boolean {
        if (!com.example.data.SupabaseClient.isSessionExpiringSoon()) return true
        val refreshResult = com.example.data.SupabaseClient.refreshAccessToken()
        if (refreshResult.refreshRejected) {
            endSessionRestoreWithLogin("Your session has expired. Please sign in again.")
            return false
        }
        if (!refreshResult.success) {
            _sessionRestoreError.value = "We couldn't refresh your session. Check your connection and try again."
            return false
        }
        return true
    }

    // Comment: The local member is enough to render the app; the network sync runs after this.
    private suspend fun adoptRestoredMember(member: Member) {
        _currentUserId.value = member.id
        val settings = repository.getSettingsDirect() ?: AppSettings()
        repository.updateSettings(
            settings.copy(
                profileName = member.name,
                membershipNo = member.membershipNo,
                mobileNo = member.mobileNo
            )
        )
    }

    private suspend fun findLocalMemberByEmail(email: String): Member? =
        repository.getAllMembersDirect().find {
            it.email.trim().equals(email.trim(), ignoreCase = true)
        }

    // Comment: Run the full sync only after the local member has opened the app. A rejected
    // refresh token is the one case that must move the user back to login; network failures leave
    // the local-first app usable.
    private fun startBackgroundSessionSync(restoredEmail: String) {
        viewModelScope.launch {
            try {
                // Comment: The user may have logged out or changed accounts before this job starts.
                if (!isRestoredSessionStillActive(restoredEmail)) return@launch

                if (com.example.data.SupabaseClient.isSessionExpiringSoon()) {
                    val refreshResult = com.example.data.SupabaseClient.refreshAccessToken()
                    if (refreshResult.refreshRejected) {
                        if (_currentUserId.value != null) {
                            endSessionRestoreWithLogin("Your session has expired. Please sign in again.")
                        }
                        return@launch
                    }
                    if (!refreshResult.success) return@launch
                }

                if (!isRestoredSessionStillActive(restoredEmail)) return@launch
                repository.syncWithSupabase()

                // Comment: Do not let an old background sync sign out a user who has already
                // logged out or signed in with a different account while it was running.
                if (com.example.data.SupabaseClient.getSession() == null) {
                    if (_currentUserId.value != null) {
                        endSessionRestoreWithLogin("Your session has expired. Please sign in again.")
                    }
                    return@launch
                }
                if (!isRestoredSessionStillActive(restoredEmail)) return@launch

                // Comment: Sync can reconcile local IDs to remote IDs; re-resolve by email so the
                // authenticated UI does not keep pointing at a deleted local row.
                findLocalMemberByEmail(restoredEmail)?.let { member ->
                    if (member.id != _currentUserId.value) _currentUserId.value = member.id
                }
            } catch (e: Exception) {
                android.util.Log.w("SavingsViewModel", "Background session sync failed: ${e.localizedMessage}")
            }
        }
    }

    private fun isRestoredSessionStillActive(restoredEmail: String): Boolean {
        val currentEmail = com.example.data.SupabaseClient.getSessionEmail()
        return !currentEmail.isNullOrBlank() &&
            currentEmail.trim().equals(restoredEmail.trim(), ignoreCase = true)
    }

    // Comment: Clear the unusable session and put the explanation above the login form.
    private fun endSessionRestoreWithLogin(message: String) {
        logout()
        _authNotice.value = message
    }

    // Comment: Retry only exists after restore has stopped with an error, so this cannot start a
    // second concurrent restore from repeated taps.
    fun retrySessionRestore() {
        if (_sessionRestoreError.value == null) return
        _sessionRestoreError.value = null
        _isRestoringSession.value = true
        viewModelScope.launch { restoreSavedSession() }
    }

    // Comment: Explicit user escape from a restore that cannot complete (for example, offline with
    // no local member). Clearing the session makes the login screen the correct destination.
    fun abandonSessionRestore() {
        logout()
        _sessionRestoreError.value = null
        _authNotice.value = "Your saved session was cleared. Please sign in again."
    }

    fun clearAuthNotice() {
        _authNotice.value = null
    }

    // Comment: Log out the current user and clear the active session
    fun logout() {
        com.example.data.SupabaseClient.clearSession()
        _currentUserId.value = null
        _isRestoringSession.value = false
        _sessionRestoreError.value = null
        _authNotice.value = null
        // Comment: Reset auth-related UI state so a logged-out user cannot see stale login dialogs or errors
        _googleLoginLoading.value = false
        _googleLoginError.value = null
        _showResetPasswordDialog.value = false
        _profilePictureUploadError.value = null
    }

    companion object {
        // Comment: Admin Panel accordion section keys (only one section can be open at a time)
        const val SECTION_CONTRIBUTION = "contribution"
        const val SECTION_BANK_DEPOSIT = "bankDeposit"
        const val SECTION_WEEKLY_GOAL = "weeklyGoal"
        const val SECTION_ACTIVE_CYCLE = "activeCycle"
        const val SECTION_CHANGE_REQUESTS = "changeRequests"
        const val SECTION_MEMBERS = "members"
        const val SECTION_REPORTS = "reports"

    }
}
