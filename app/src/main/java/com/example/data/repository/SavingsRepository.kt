package com.example.data.repository

import com.example.data.local.MemberDao
import com.example.data.local.SavingsDao
import com.example.data.local.AppSettingsDao
import com.example.data.local.ChangeRequestDao
import com.example.data.local.BankDepositDao
import com.example.data.local.CommunitySettingsDao
import com.example.data.model.AppSettings
import com.example.data.model.Member
import com.example.data.model.Savings
import com.example.data.model.ChangeRequest
import com.example.data.model.BankDeposit
import com.example.data.model.CommunitySettings
import com.example.data.model.WeeklyGoalUpdateResult
import com.example.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

/**
 * Repository class that abstracts data sources and provides clean access to DAOs.
 * Also handles initial database seeding with high-fidelity mock data on first launch.
 */
class SavingsRepository(
    private val memberDao: MemberDao,
    private val savingsDao: SavingsDao,
    private val appSettingsDao: AppSettingsDao,
    private val changeRequestDao: ChangeRequestDao,
    private val bankDepositDao: BankDepositDao,
    private val communitySettingsDao: CommunitySettingsDao
) {
    val allMembers: Flow<List<Member>> = memberDao.getAllMembers()
    val allSavings: Flow<List<Savings>> = savingsDao.getAllSavings()
    val appSettings: Flow<AppSettings?> = appSettingsDao.getSettingsFlow()
    val allChangeRequests: Flow<List<ChangeRequest>> = changeRequestDao.getAllChangeRequests()
    val allBankDeposits: Flow<List<BankDeposit>> = bankDepositDao.getAllBankDeposits()
    // Comment: Shared per-member Weekly Savings Goals (one row per member) every device reads
    val communitySettings: Flow<List<CommunitySettings>> = communitySettingsDao.getCommunitySettingsFlow()

    fun getSavingsForMember(memberId: Int): Flow<List<Savings>> {
        return savingsDao.getSavingsForMember(memberId)
    }

    suspend fun getMemberById(id: Int): Member? {
        return memberDao.getMemberById(id)
    }

    suspend fun getAllMembersDirect(): List<Member> = withContext(Dispatchers.IO) {
        memberDao.getAllMembersDirect()
    }

    suspend fun insertMember(member: Member) = withContext(Dispatchers.IO) {
        val localId = memberDao.insertMember(member).toInt()
        val localMemberWithId = member.copy(id = localId)
        // Comment: Securely sync the newly created member profile to Supabase remote database table if user is authenticated
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            val inserted = com.example.data.SupabaseClient.dbInsertMember(localMemberWithId)
            if (inserted != null && inserted.id != localMemberWithId.id) {
                // Comment: Atomically swap the local auto-increment row for the remote SERIAL row so a crash cannot leave a missing member
                memberDao.replaceMemberWithRemote(localMemberWithId, inserted)
            }
        }
    }

    suspend fun insertMemberLocally(member: Member) = withContext(Dispatchers.IO) {
        memberDao.insertMember(member)
    }

    suspend fun updateMember(member: Member) = withContext(Dispatchers.IO) {
        val oldMember = memberDao.getMemberById(member.id)
        memberDao.updateMember(member)
        // Comment: Securely update the member profile in Supabase remote database table if user is authenticated
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            com.example.data.SupabaseClient.dbUpdateMember(member)
        }

        // Comment: If member name was updated, propagate the change across all tables locally and in Supabase
        if (oldMember != null && oldMember.name != member.name) {
            propagateMemberNameChange(member.id, member.name)
        }
    }

    suspend fun deleteMember(member: Member) = withContext(Dispatchers.IO) {
        memberDao.deleteMember(member)
        // Comment: Soft-delete on the remote (status = "Removed") instead of a hard DELETE, so the member's
        // change_requests rows are not cascade-deleted and the approved removal can propagate to every device.
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            com.example.data.SupabaseClient.dbSoftDeleteMember(member)
        }
    }

    suspend fun insertSavings(savings: Savings) = withContext(Dispatchers.IO) {
        // Comment: Assign a stable client-generated UUID match key to new entries so local and remote rows can be matched without relying on numeric IDs
        val savingsWithKey = if (savings.syncKey.isBlank()) {
            savings.copy(syncKey = java.util.UUID.randomUUID().toString())
        } else savings
        val localId = savingsDao.insertSavings(savingsWithKey).toInt()
        val localSavingsWithId = savingsWithKey.copy(id = localId)
        // Dynamically update the member's totalSavings in the database
        val member = memberDao.getMemberById(localSavingsWithId.memberId)
        if (member != null) {
            val updatedMember = member.copy(totalSavings = member.totalSavings + localSavingsWithId.amount)
            memberDao.updateMember(updatedMember)
            // Comment: Sync updated member totalSavings to Supabase remote database table
            if (com.example.data.SupabaseClient.getAccessToken() != null) {
                com.example.data.SupabaseClient.dbUpdateMember(updatedMember)
            }
        }
        // Comment: Sync savings contribution to Supabase remote database table (upserted on sync_key)
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            val inserted = com.example.data.SupabaseClient.dbUpsertSavings(localSavingsWithId)
            if (inserted != null && inserted.id != localSavingsWithId.id) {
                // Comment: Atomically swap the local auto-increment row for the remote SERIAL row so a crash cannot leave a missing savings entry
                savingsDao.replaceSavingsWithRemote(localSavingsWithId, inserted)
            }
        }
    }

    suspend fun updateSavings(savings: Savings, oldAmount: Double) = withContext(Dispatchers.IO) {
        // Comment: Preserve the row's stable sync key when callers pass a partially-populated Savings
        // (the admin approval flow omits syncKey), so a full-row @Update does not wipe it
        val existing = savingsDao.getSavingsById(savings.id)
        val effective = savings.copy(syncKey = savings.syncKey.ifBlank { existing?.syncKey ?: java.util.UUID.randomUUID().toString() })
        savingsDao.updateSavings(effective)
        // Dynamically update the member's totalSavings in the database
        val member = memberDao.getMemberById(effective.memberId)
        if (member != null) {
            val updatedMember = member.copy(totalSavings = member.totalSavings - oldAmount + effective.amount)
            memberDao.updateMember(updatedMember)
            // Comment: Sync updated member totalSavings to Supabase remote database table
            if (com.example.data.SupabaseClient.getAccessToken() != null) {
                com.example.data.SupabaseClient.dbUpdateMember(updatedMember)
            }
        }
        // Comment: Sync updated savings contribution to Supabase remote database table
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            com.example.data.SupabaseClient.dbUpdateSavings(effective)
        }
    }

    suspend fun deleteSavings(savings: Savings) = withContext(Dispatchers.IO) {
        // Comment: Resolve the row's stable sync key before deleting so the remote soft-delete
        // targets the correct row even when the caller passed a partial Savings object
        val existing = savingsDao.getSavingsById(savings.id)
        val effective = savings.copy(syncKey = savings.syncKey.ifBlank { existing?.syncKey ?: "" })
        savingsDao.deleteSavings(savings)
        // Dynamically update the member's totalSavings in the database
        val member = memberDao.getMemberById(savings.memberId)
        if (member != null) {
            val updatedMember = member.copy(totalSavings = maxOf(0.0, member.totalSavings - savings.amount))
            memberDao.updateMember(updatedMember)
            // Comment: Sync updated member totalSavings to Supabase remote database table
            if (com.example.data.SupabaseClient.getAccessToken() != null) {
                com.example.data.SupabaseClient.dbUpdateMember(updatedMember)
            }
        }
        // Comment: Sync savings contribution deletion to Supabase remote database table
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            com.example.data.SupabaseClient.dbDeleteSavings(effective)
        }
    }

    suspend fun insertBankDeposit(deposit: BankDeposit) = withContext(Dispatchers.IO) {
        // Comment: Assign a stable client-generated UUID match key to new entries so local and remote rows can be matched without relying on numeric IDs
        val depositWithKey = if (deposit.syncKey.isBlank()) {
            deposit.copy(syncKey = java.util.UUID.randomUUID().toString())
        } else deposit
        val localId = bankDepositDao.insertBankDeposit(depositWithKey).toInt()
        val localDepositWithId = depositWithKey.copy(id = localId)
        // Comment: Push the new bank deposit to the remote ledger (upserted on sync_key) so every device agrees on Cash in Hand
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            val inserted = com.example.data.SupabaseClient.dbUpsertBankDeposit(localDepositWithId)
            if (inserted != null && inserted.id != localDepositWithId.id) {
                // Comment: Atomically swap the local auto-increment row for the remote SERIAL row so a crash cannot leave a missing deposit
                bankDepositDao.replaceBankDepositWithRemote(localDepositWithId, inserted)
            }
        }
    }

    suspend fun updateBankDeposit(deposit: BankDeposit) = withContext(Dispatchers.IO) {
        // Comment: Preserve the row's stable sync key when callers pass a partially-populated BankDeposit
        val existing = bankDepositDao.getBankDepositById(deposit.id)
        val effective = deposit.copy(syncKey = deposit.syncKey.ifBlank { existing?.syncKey ?: java.util.UUID.randomUUID().toString() })
        bankDepositDao.updateBankDeposit(effective)
        // Comment: Sync the edited deposit to the remote ledger (upserted on sync_key)
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            com.example.data.SupabaseClient.dbUpsertBankDeposit(effective)
        }
    }

    suspend fun deleteBankDeposit(deposit: BankDeposit) = withContext(Dispatchers.IO) {
        // Comment: Resolve the row's stable sync key before deleting so the remote soft-delete
        // targets the correct row even when the caller passed a partial BankDeposit object
        val existing = bankDepositDao.getBankDepositById(deposit.id)
        val effective = deposit.copy(syncKey = deposit.syncKey.ifBlank { existing?.syncKey ?: "" })
        bankDepositDao.deleteBankDeposit(deposit)
        // Comment: Sync the deposit deletion to the remote ledger as a soft-delete so it propagates to other devices
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            com.example.data.SupabaseClient.dbDeleteBankDeposit(effective)
        }
    }

    suspend fun getSettingsDirect(): AppSettings? = withContext(Dispatchers.IO) {
        appSettingsDao.getSettingsDirect()
    }

    /**
     * Comment: Update the per-member Weekly Savings Goals (admin action). Members save different amounts, so the
     * admin's draft arrives as a map of member email -> goal and each row is written and pushed on its own. The local
     * row is written first so the admin's screens update instantly, then pushed to Supabase — the RLS policy enforces
     * that only an admin can write, and each row's version keeps this device from overwriting a newer goal set on
     * another device. The overall outcome is returned so the caller can tell the admin whether it reached everyone.
     */
    suspend fun updateCommunityWeeklyGoals(goals: Map<String, Double>): WeeklyGoalUpdateResult = withContext(Dispatchers.IO) {
        var allSynced = true
        var conflict = false

        for ((rawEmail, goal) in goals) {
            val email = rawEmail.trim().lowercase()
            if (email.isBlank()) continue

            // Comment: Stamp the row with the member's id and name, because the email alone does not tell an admin
            // (or another device reading the shared table) whose goal a row holds
            val member = memberDao.getMemberByEmail(email)

            // Comment: Carry over the version this member's row was last read from; it is the precondition for the push
            val previousRow = communitySettingsDao.getCommunitySettingsForMember(email)
            val previousVersion = previousRow?.remoteVersion ?: 0
            val updated = CommunitySettings(
                memberEmail = email,
                weeklyGoal = goal,
                // Comment: Fall back to whatever identity the row already holds if the member is not resolvable here
                memberId = member?.id ?: previousRow?.memberId ?: 0,
                memberName = member?.name ?: previousRow?.memberName ?: "",
                remoteVersion = previousVersion
            )
            communitySettingsDao.insertOrUpdateCommunitySettings(updated)

            // Comment: Without a session there is nothing to push to, so the edit stays local until a later sync retries it
            if (com.example.data.SupabaseClient.getAccessToken() == null) {
                allSynced = false
                continue
            }

            val stored = com.example.data.SupabaseClient.dbPushCommunitySettings(updated)
            if (stored != null) {
                communitySettingsDao.insertOrUpdateCommunitySettings(stored)
                continue
            }

            // Comment: A rejected push is either a lost race or a transient error, so re-read this member's row: only a
            // version that moved on since this device last synced proves another admin changed that goal first, in which
            // case the newer central value is adopted here instead of being reverted by this device.
            val latestRemote = com.example.data.SupabaseClient.dbFetchCommunitySettingsForMember(email)
            if (latestRemote != null && latestRemote.remoteVersion != previousVersion) {
                communitySettingsDao.insertOrUpdateCommunitySettings(latestRemote)
                conflict = true
            }
            allSynced = false
        }

        when {
            conflict -> WeeklyGoalUpdateResult.CONFLICT
            allSynced -> WeeklyGoalUpdateResult.SYNCED
            else -> WeeklyGoalUpdateResult.PENDING
        }
    }

    suspend fun updateSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        appSettingsDao.insertOrUpdateSettings(settings)
        // Comment: Sync app settings configurations to Supabase remote database table
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            com.example.data.SupabaseClient.dbUpsertSettings(settings)
        }
    }

    /**
     * Seeds the database with high-fidelity mock data if empty.
     */
    suspend fun populateInitialDataIfEmpty() = withContext(Dispatchers.IO) {
        // Comment: Proactively clean up any preseeded/mock members and savings from local database to only keep manual accounts
        try {
            val allLocalMembers = memberDao.getAllMembersDirect()
            val preseededMembers = allLocalMembers.filter { it.email.trim().lowercase() in Constants.SEED_EMAILS }
            for (m in preseededMembers) {
                val memberSavings = savingsDao.getSavingsForMemberDirect(m.id)
                for (s in memberSavings) {
                    savingsDao.deleteSavings(s)
                }
                memberDao.deleteMember(m)
            }

            // Comment: Clean up duplicate member profiles locally (such as Asad.msnd with same email asad.msnd@gmail.com)
            val remainingMembers = memberDao.getAllMembersDirect()
            val groupedByEmail = remainingMembers.groupBy { it.email.trim().lowercase() }
            for ((email, membersWithEmail) in groupedByEmail) {
                if (membersWithEmail.size > 1) {
                    val representative = membersWithEmail.first()
                    for (i in 1 until membersWithEmail.size) {
                        val duplicate = membersWithEmail[i]
                        val dupSavings = savingsDao.getSavingsForMemberDirect(duplicate.id)
                        for (s in dupSavings) {
                            savingsDao.deleteSavings(s)
                            savingsDao.insertSavings(s.copy(memberId = representative.id))
                        }
                        memberDao.deleteMember(duplicate)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Comment: No per-member Weekly Savings Goal rows are seeded here: a member with no row simply falls back to
        // the 500৳ default, and real rows arrive from Supabase or are created the first time an admin saves their goal.

        val currentSettings = appSettingsDao.getSettingsDirect()
        if (currentSettings == null) {
            // Seed Settings
            appSettingsDao.insertOrUpdateSettings(
                AppSettings(
                    id = 1,
                    // Comment: Seed the app settings with a personal goal of 500.0 instead of 5000.0 per user request
                    personalGoal = 500.0,
                    profileName = "New Admin",
                    membershipNo = "",
                    isDarkMode = false
                )
            )
        }
    }

    suspend fun getChangeRequestById(id: Int): ChangeRequest? {
        return changeRequestDao.getChangeRequestById(id)
    }

    suspend fun insertChangeRequest(request: ChangeRequest) = withContext(Dispatchers.IO) {
        val localId = changeRequestDao.insertChangeRequest(request).toInt()
        val localRequestWithId = request.copy(id = localId)
        // Comment: Securely sync the newly created change request to Supabase remote database table if user is authenticated
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            val inserted = com.example.data.SupabaseClient.dbInsertChangeRequest(localRequestWithId)
            if (inserted != null && inserted.id != localRequestWithId.id) {
                // Comment: Atomically swap the local auto-increment row for the remote SERIAL row so a crash cannot leave a missing change request
                changeRequestDao.replaceChangeRequestWithRemote(localRequestWithId, inserted)
            }
        }
    }

    suspend fun updateChangeRequest(request: ChangeRequest) = withContext(Dispatchers.IO) {
        changeRequestDao.updateChangeRequest(request)
        // Comment: Securely update the change request in Supabase remote database table if user is authenticated
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            com.example.data.SupabaseClient.dbUpdateChangeRequest(request)
        }
    }

    suspend fun deleteChangeRequest(request: ChangeRequest) = withContext(Dispatchers.IO) {
        changeRequestDao.deleteChangeRequest(request)
        // Comment: Securely delete the change request from Supabase remote database table if user is authenticated
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            com.example.data.SupabaseClient.dbDeleteChangeRequest(request)
        }
    }

    /**
     * Comment: Synchronize local Room database with Supabase remote tables when user is authenticated.
     * Fully implements bidirectional sync (both pulling and pushing), preserving roles, mapped foreign keys,
     * and auto-resolving local vs. remote ID conflicts.
     */
    // Comment: Serialize concurrent sync calls so overlapping syncs cannot mutate the same tables and produce duplicate or lost rows
    private val syncMutex = Mutex()

    suspend fun syncWithSupabase(): List<ChangeRequest> = syncMutex.withLock {
        syncWithSupabaseInternal()
    }

    private suspend fun syncWithSupabaseInternal(): List<ChangeRequest> = withContext(Dispatchers.IO) {
        val newPendingRequests = mutableListOf<ChangeRequest>()
        val token = com.example.data.SupabaseClient.getAccessToken() ?: return@withContext emptyList()
        // Comment: Refresh the access token before syncing if it is close to expiry, so the sync does not fail mid-flight with a 401
        if (com.example.data.SupabaseClient.isSessionExpiringSoon()) {
            val refreshResult = com.example.data.SupabaseClient.refreshAccessToken()
            if (!refreshResult.success) {
                Log.w("SupabaseSync", "Access token could not be refreshed: ${refreshResult.message}")
                return@withContext emptyList()
            }
        }
        Log.d("SupabaseSync", "Starting comprehensive bidirectional database synchronization with Supabase...")

        try {
            // Comment: Process all "Approved" Change Requests from Supabase locally first.
            // When an admin approves a request (edit, delete, role change, etc.) on their device,
            // the change needs to be pulled and applied to the requester's (and other members') local database
            // so that local Room records stay perfectly in sync with the remote source of truth.
            try {
                val remoteRequestsForProcessing = com.example.data.SupabaseClient.dbFetchChangeRequests()
                for (req in remoteRequestsForProcessing) {
                    if (req.status == "Approved") {
                        when (req.requestType) {
                            "Delete" -> {
                                // Comment: Point-query the row by ID instead of re-scanning the whole savings table for each approved request
                                val localSaving = savingsDao.getSavingsById(req.savingsId)
                                if (localSaving != null) {
                                    // Comment: Delete the saving locally since its deletion was approved by the admin
                                    savingsDao.deleteSavings(localSaving)
                                    // Comment: Recalculate and update the member's total savings locally
                                    val m = memberDao.getMemberById(localSaving.memberId)
                                    if (m != null) {
                                        val newTotal = maxOf(0.0, m.totalSavings - localSaving.amount)
                                        memberDao.updateMember(m.copy(totalSavings = newTotal))
                                    }
                                }
                            }
                            "Edit" -> {
                                // Comment: Point-query the row by ID instead of re-scanning the whole savings table for each approved request
                                val localSaving = savingsDao.getSavingsById(req.savingsId)
                                if (localSaving != null) {
                                    val originalAmt = localSaving.amount
                                    val newAmt = req.newAmount ?: originalAmt
                                    val updatedSaving = localSaving.copy(
                                        amount = newAmt,
                                        dateText = req.newDateText ?: localSaving.dateText
                                    )
                                    // Comment: Update the saving locally with the new edited amount and date approved by the admin
                                    savingsDao.updateSavings(updatedSaving)
                                    // Comment: Recalculate and update the member's total savings locally
                                    val m = memberDao.getMemberById(localSaving.memberId)
                                    if (m != null) {
                                        val newTotal = maxOf(0.0, m.totalSavings - originalAmt + newAmt)
                                        memberDao.updateMember(m.copy(totalSavings = newTotal))
                                    }
                                }
                            }
                            "RemoveMember" -> {
                                val localMember = memberDao.getMemberById(req.memberId)
                                if (localMember != null) {
                                    // Comment: Soft-delete remotely FIRST (status = "Removed") so the removal propagates
                                    // to every device, then remove it locally. Order matters — once deleted locally we
                                    // would no longer have the row to soft-delete remotely.
                                    com.example.data.SupabaseClient.dbSoftDeleteMember(localMember)
                                    memberDao.deleteMember(localMember)
                                }
                            }
                            "MakeAdmin" -> {
                                val localMember = memberDao.getMemberById(req.memberId)
                                if (localMember != null && localMember.role != "Admin") {
                                    // Comment: Upgrade the member to Admin locally as approved by the admin
                                    memberDao.updateMember(localMember.copy(role = "Admin"))
                                }
                            }
                            "RemoveAdmin" -> {
                                val localMember = memberDao.getMemberById(req.memberId)
                                if (localMember != null && localMember.role != "Member") {
                                    // Comment: Demote the Admin to Member locally as approved by the admin
                                    memberDao.updateMember(localMember.copy(role = "Member"))
                                }
                            }
                        }
                    }
                }
            } catch (processingEx: Exception) {
                Log.e("SupabaseSync", "Error processing approved change requests locally", processingEx)
            }

            // --- 1. Sync Members ---
            // Comment: Fetch ALL members including soft-deleted ones (status = "Removed") so this device can
            // detect removals approved elsewhere and apply them locally.
            val allRemoteMembers = com.example.data.SupabaseClient.dbFetchMembers(includeRemoved = true)
            val localMembers = memberDao.getAllMembersDirect()

            // Comment: Emails remotely marked "Removed" — these must be purged locally and never re-pushed
            val removedRemoteEmails = allRemoteMembers
                .filter { it.status == Constants.REMOVED_STATUS }
                .map { it.email.trim().lowercase() }
                .toSet()

            // Filter out seed members and remotely-removed members from the active remote set
            val filteredRemoteMembers = allRemoteMembers.filter {
                it.email.trim().lowercase() !in Constants.SEED_EMAILS && it.status != Constants.REMOVED_STATUS
            }
            val filteredLocalMembers = localMembers.filter { it.email.trim().lowercase() !in Constants.SEED_EMAILS }

            // Comment: Purge local members whose removal was approved on Supabase (runs before the push loop so
            // a removed member is never re-pushed back to Supabase)
            for (localMember in filteredLocalMembers) {
                if (localMember.email.trim().lowercase() in removedRemoteEmails) {
                    memberDao.deleteMember(localMember)
                }
            }
            // Re-read local members after the purge so the push/pull loops work on the current set
            val localMembersAfterPurge = memberDao.getAllMembersDirect()
                .filter { it.email.trim().lowercase() !in Constants.SEED_EMAILS }

            // Create remote mapping by email
            val remoteByEmail = filteredRemoteMembers.associateBy { it.email.trim().lowercase() }
            val memberIdMap = mutableMapOf<Int, Int>() // maps localMemberId -> remoteMemberId

            // Track IDs we insert/keep locally
            val deDuplicatedIds = mutableSetOf<Int>()

            for (localMember in localMembersAfterPurge) {
                val emailKey = localMember.email.trim().lowercase()
                val remoteMember = remoteByEmail[emailKey]

                if (remoteMember != null) {
                    // Member exists both locally and remotely
                    if (localMember.id != remoteMember.id) {
                        // Local ID doesn't match remote ID. Update references and local database.
                        memberIdMap[localMember.id] = remoteMember.id
                        memberDao.deleteMember(localMember)
                        memberDao.insertMember(remoteMember)
                    } else {
                        // IDs match, update local record with the remote record (remote is the source of truth for roles/status)
                        memberDao.insertMember(remoteMember)
                    }
                    deDuplicatedIds.add(remoteMember.id)
                } else {
                    // Local member does not exist in Supabase yet. Push it to Supabase!
                    val inserted = com.example.data.SupabaseClient.dbInsertMember(localMember)
                    if (inserted != null) {
                        if (inserted.id != localMember.id) {
                            memberIdMap[localMember.id] = inserted.id
                            memberDao.deleteMember(localMember)
                            memberDao.insertMember(inserted)
                        } else {
                            memberDao.insertMember(inserted)
                        }
                        deDuplicatedIds.add(inserted.id)
                    } else {
                        // If push failed, we keep it locally
                        deDuplicatedIds.add(localMember.id)
                    }
                }
            }

            // Pull any remote members who are not present locally
            val localEmails = filteredLocalMembers.map { it.email.trim().lowercase() }.toSet()
            for (remoteMember in filteredRemoteMembers) {
                val emailKey = remoteMember.email.trim().lowercase()
                if (emailKey !in localEmails) {
                    memberDao.insertMember(remoteMember)
                    deDuplicatedIds.add(remoteMember.id)
                }
            }

            // Clean up any stale manual members locally that are not in the active de-duplicated list
            val allLocalAfterSync = memberDao.getAllMembersDirect()
            for (m in allLocalAfterSync) {
                if (m.id !in deDuplicatedIds && m.email.trim().lowercase() !in Constants.SEED_EMAILS) {
                    memberDao.deleteMember(m)
                }
            }

            // Propagate any member ID updates to local savings and change requests
            if (memberIdMap.isNotEmpty()) {
                Log.d("SupabaseSync", "Updating local foreign key member IDs: $memberIdMap")
                val localSavingsList = savingsDao.getAllSavingsDirect()
                for (s in localSavingsList) {
                    val mappedId = memberIdMap[s.memberId]
                    if (mappedId != null) {
                        savingsDao.deleteSavings(s)
                        savingsDao.insertSavings(s.copy(memberId = mappedId))
                    }
                }

                // Comment: Propagate remapped member IDs to the bank ledger's depositor reference
                val localDepositsList = bankDepositDao.getAllBankDepositsDirect()
                for (d in localDepositsList) {
                    val mappedId = memberIdMap[d.depositedById]
                    if (mappedId != null) {
                        bankDepositDao.deleteBankDeposit(d)
                        bankDepositDao.insertBankDeposit(d.copy(depositedById = mappedId))
                    }
                }

                val localRequestsList = changeRequestDao.getAllChangeRequestsDirect()
                for (r in localRequestsList) {
                    val mappedId = memberIdMap[r.memberId]
                    if (mappedId != null) {
                        changeRequestDao.deleteChangeRequest(r)
                        changeRequestDao.insertChangeRequest(r.copy(memberId = mappedId))
                    }
                }
            }

            // --- 2. Sync Savings ---
            val remoteSavingsRaw = com.example.data.SupabaseClient.dbFetchSavings()
            val localSavingsRaw = savingsDao.getAllSavingsDirect()

            // Filter out savings records that reference deleted or seeded members
            // Comment: Reuse the active member ID set already built during the members sync above instead of
            // re-reading the whole members table; deDuplicatedIds already excludes deleted and seeded members
            val activeMemberIds = deDuplicatedIds

            val filteredRemoteSavingsRaw = remoteSavingsRaw.filter { it.memberId in activeMemberIds }
            val filteredLocalSavingsRaw = localSavingsRaw.filter { it.memberId in activeMemberIds }

            // Comment: One-time backfill that gives every pre-existing savings row a stable UUID match key,
            // so all subsequent syncs can match purely on sync_key instead of two independent numeric ID sequences
            val (remoteSavings, localSavings) = backfillSavingsSyncKeys(filteredRemoteSavingsRaw, filteredLocalSavingsRaw)

            // Comment: Remote savings that were soft-deleted must be removed locally, never re-pushed, or they would resurrect
            val deletedRemoteIds = com.example.data.SupabaseClient.dbFetchDeletedSavingsIds()
            val deletedRemoteKeys = com.example.data.SupabaseClient.dbFetchDeletedSavingsKeys()

            // Map remote and local savings by their stable sync key
            val remoteSavingsByKey = remoteSavings.associateBy { it.syncKey }
            val localSavingsByKey = localSavings.associateBy { it.syncKey }

            // Match and Push local savings that are not in Supabase
            for (localSaving in localSavings) {
                if (localSaving.syncKey.isBlank()) continue
                // Comment: A local row whose sync key (or legacy numeric id) was soft-deleted remotely is stale; drop it locally and adjust the member total instead of pushing it back
                val isStaleDelete = localSaving.syncKey in deletedRemoteKeys || localSaving.id in deletedRemoteIds
                if (isStaleDelete) {
                    savingsDao.deleteSavings(localSaving)
                    val m = memberDao.getMemberById(localSaving.memberId)
                    if (m != null) {
                        memberDao.updateMember(m.copy(totalSavings = maxOf(0.0, m.totalSavings - localSaving.amount)))
                    }
                    continue
                }
                val remoteSaving = remoteSavingsByKey[localSaving.syncKey]
                if (remoteSaving == null) {
                    // Push new saving contribution to remote (upserted on sync_key)
                    val inserted = com.example.data.SupabaseClient.dbUpsertSavings(localSaving)
                    if (inserted != null && inserted.id != localSaving.id) {
                        savingsDao.replaceSavingsWithRemote(localSaving, inserted)
                    }
                } else if (localSaving != remoteSaving) {
                    // Remote is the source of truth; align the local row without leaving a duplicate when the IDs differ
                    if (localSaving.id != remoteSaving.id) {
                        savingsDao.replaceSavingsWithRemote(localSaving, remoteSaving)
                    } else {
                        savingsDao.insertSavings(remoteSaving)
                    }
                }
            }

            // Pull any remote savings who are not present locally
            for (remoteSaving in remoteSavings) {
                if (remoteSaving.syncKey.isBlank()) continue
                if (localSavingsByKey[remoteSaving.syncKey] == null) {
                    savingsDao.insertSavings(remoteSaving)
                }
            }

            // --- 2b. Sync Bank Deposits ---
            // Comment: Same stable sync_key matching and remote soft-delete handling as savings, so a deposit
            // deleted on one device is never resurrected by another device's push
            val remoteDeposits = com.example.data.SupabaseClient.dbFetchBankDeposits()
            val localDeposits = bankDepositDao.getAllBankDepositsDirect()
            val deletedDepositKeys = com.example.data.SupabaseClient.dbFetchDeletedBankDepositKeys()

            val remoteDepositsByKey = remoteDeposits.associateBy { it.syncKey }
            val localDepositsByKey = localDeposits.associateBy { it.syncKey }

            // Push local deposits that are not in Supabase, dropping any that were soft-deleted remotely
            for (localDeposit in localDeposits) {
                if (localDeposit.syncKey.isBlank()) continue
                if (localDeposit.syncKey in deletedDepositKeys) {
                    bankDepositDao.deleteBankDeposit(localDeposit)
                    continue
                }
                val remoteDeposit = remoteDepositsByKey[localDeposit.syncKey]
                if (remoteDeposit == null) {
                    // Push new bank deposit to remote (upserted on sync_key)
                    val inserted = com.example.data.SupabaseClient.dbUpsertBankDeposit(localDeposit)
                    if (inserted != null && inserted.id != localDeposit.id) {
                        bankDepositDao.replaceBankDepositWithRemote(localDeposit, inserted)
                    }
                } else if (localDeposit != remoteDeposit) {
                    // Remote is the source of truth; align the local row without leaving a duplicate when the IDs differ
                    if (localDeposit.id != remoteDeposit.id) {
                        bankDepositDao.replaceBankDepositWithRemote(localDeposit, remoteDeposit)
                    } else {
                        bankDepositDao.insertBankDeposit(remoteDeposit)
                    }
                }
            }

            // Pull any remote deposits that are not present locally
            for (remoteDeposit in remoteDeposits) {
                if (remoteDeposit.syncKey.isBlank()) continue
                if (localDepositsByKey[remoteDeposit.syncKey] == null) {
                    bankDepositDao.insertBankDeposit(remoteDeposit)
                }
            }

            // --- 3. Sync Settings ---
            val remoteSettings = com.example.data.SupabaseClient.dbFetchSettings()
            val localSettings = appSettingsDao.getSettingsDirect()

            if (remoteSettings != null) {
                // Pull remote settings
                appSettingsDao.insertOrUpdateSettings(remoteSettings)
            } else if (localSettings != null) {
                // Push local settings
                com.example.data.SupabaseClient.dbUpsertSettings(localSettings)
            }

            // --- 3b. Sync Community Settings (per-member Weekly Savings Goals) ---
            val remoteGoals = com.example.data.SupabaseClient.dbFetchCommunitySettings()
            val localGoals = communitySettingsDao.getAllCommunitySettingsDirect()
            val remoteGoalsByEmail = remoteGoals.associateBy { it.memberEmail }
            val localGoalsByEmail = localGoals.associateBy { it.memberEmail }

            // Comment: Identify the admin from the member list already fetched for this sync (no extra query)
            val sessionEmail = com.example.data.SupabaseClient.getSessionEmail()
            val isCurrentUserAdmin = sessionEmail != null && localMembersAfterPurge.any {
                it.email.trim().equals(sessionEmail.trim(), ignoreCase = true) && it.role == "Admin"
            }

            // Comment: Align each member's goal with the shared table. Members only ever read the central rows, so every
            // device projects identical totals; an admin additionally pushes a goal this device holds that the server
            // does not have yet. A row this device never confirmed (remoteVersion 0) always yields to the central value,
            // so a fresh install can never push its 500৳ placeholder over a real goal.
            for (remoteGoal in remoteGoals) {
                val localGoal = localGoalsByEmail[remoteGoal.memberEmail]
                when {
                    localGoal == null ->
                        communitySettingsDao.insertOrUpdateCommunitySettings(remoteGoal)

                    localGoal.weeklyGoal == remoteGoal.weeklyGoal ->
                        // Comment: Already identical, so adopt the remote row to track the latest central version while
                        // keeping this device's member id/name for a central row that predates those columns
                        communitySettingsDao.insertOrUpdateCommunitySettings(remoteGoal.withLocalIdentityFallback(localGoal))

                    isCurrentUserAdmin && localGoal.remoteVersion > 0 -> {
                        // Comment: A confirmed row that was edited offline (or failed to push) is written back using the
                        // version this device last read as its precondition, so a stale device cannot revert a newer goal
                        val stored = com.example.data.SupabaseClient.dbPushCommunitySettings(localGoal)
                        if (stored != null) {
                            communitySettingsDao.insertOrUpdateCommunitySettings(stored)
                        } else {
                            // Comment: A failed push is either a lost race or a transient error, so re-read this member's
                            // row: only a version that moved on proves another admin changed that goal, in which case the
                            // newer central value wins. Otherwise the local edit is kept so the next sync retries it.
                            val latestRemote = com.example.data.SupabaseClient.dbFetchCommunitySettingsForMember(localGoal.memberEmail)
                            if (latestRemote != null && latestRemote.remoteVersion != localGoal.remoteVersion) {
                                communitySettingsDao.insertOrUpdateCommunitySettings(latestRemote)
                            }
                        }
                    }

                    else ->
                        communitySettingsDao.insertOrUpdateCommunitySettings(remoteGoal.withLocalIdentityFallback(localGoal))
                }
            }

            // Comment: Publish any goal the admin set for a member that has no central row yet (a brand-new member, or
            // an edit made offline), so the member reads the goal the admin chose instead of the 500৳ fallback
            if (isCurrentUserAdmin) {
                for (localGoal in localGoals) {
                    if (remoteGoalsByEmail.containsKey(localGoal.memberEmail)) continue
                    com.example.data.SupabaseClient.dbPushCommunitySettings(localGoal)
                        ?.let { communitySettingsDao.insertOrUpdateCommunitySettings(it) }
                }
            }

            // --- 4. Sync Change Requests ---
            val remoteRequests = com.example.data.SupabaseClient.dbFetchChangeRequests()
            val localRequests = changeRequestDao.getAllChangeRequestsDirect()

            val remoteRequestsById = remoteRequests.associateBy { it.id }
            val localRequestsById = localRequests.associateBy { it.id }

            // Push local requests not present remotely
            for (localReq in localRequests) {
                if (localReq.id !in remoteRequestsById) {
                    val isDuplicate = remoteRequests.any {
                        it.savingsId == localReq.savingsId &&
                                it.memberId == localReq.memberId &&
                                it.requestType == localReq.requestType &&
                                it.timestamp == localReq.timestamp
                    }
                    if (!isDuplicate) {
                        val inserted = com.example.data.SupabaseClient.dbInsertChangeRequest(localReq)
                        if (inserted != null && inserted.id != localReq.id) {
                            changeRequestDao.deleteChangeRequest(localReq)
                            changeRequestDao.insertChangeRequest(inserted)
                        }
                    }
                } else {
                    val remoteReq = remoteRequestsById[localReq.id]
                    if (remoteReq != null && localReq != remoteReq) {
                        changeRequestDao.insertChangeRequest(remoteReq)
                    }
                }
            }

            // Pull remote requests not present locally
            for (remoteReq in remoteRequests) {
                if (remoteReq.id !in localRequestsById) {
                    val isDuplicate = localRequests.any {
                        it.savingsId == remoteReq.savingsId &&
                                it.memberId == remoteReq.memberId &&
                                it.requestType == remoteReq.requestType &&
                                it.timestamp == remoteReq.timestamp
                    }
                    if (!isDuplicate) {
                        changeRequestDao.insertChangeRequest(remoteReq)
                        // Comment: Add newly fetched pending change requests to trigger status bar notifications
                        if (remoteReq.status == "Pending") {
                            newPendingRequests.add(remoteReq)
                        }
                    }
                }
            }

            Log.d("SupabaseSync", "Database bidirectional synchronization completed successfully!")
            newPendingRequests
        } catch (e: Exception) {
            Log.e("SupabaseSync", "Error during database bidirectional synchronization", e)
            emptyList()
        }
    }

    /**
     * Comment: Keep this device's member id/name on a Weekly Savings Goal when the central row does not carry them, so
     * adopting a central goal never blanks the owner details this device already knows. A local row is absent for the
     * first central row of a member, in which case the central values are used as-is.
     */
    private fun CommunitySettings.withLocalIdentityFallback(local: CommunitySettings?): CommunitySettings {
        if (local == null) return this
        return copy(
            memberId = if (memberId != 0) memberId else local.memberId,
            memberName = memberName.ifBlank { local.memberName }
        )
    }

    /**
     * Comment: Propagate the member name change across all related tables (savings, change_requests)
     * both locally in the Room database and remotely in the Supabase database.
     */
    private suspend fun propagateMemberNameChange(memberId: Int, newName: String) = withContext(Dispatchers.IO) {
        Log.d("SupabaseSync", "Propagating name change for member $memberId to '$newName'...")

        // 1. Fetch and update all savings contributions for this member
        val savingsList = savingsDao.getSavingsForMemberDirect(memberId)
        for (savings in savingsList) {
            val updatedSavings = savings.copy(memberName = newName)
            savingsDao.updateSavings(updatedSavings)

            // Sync updated savings entry to Supabase
            if (com.example.data.SupabaseClient.getAccessToken() != null) {
                com.example.data.SupabaseClient.dbUpdateSavings(updatedSavings)
            }
        }

        // 2. Fetch and update all change requests submitted by this member
        val requestsList = changeRequestDao.getChangeRequestsForMemberDirect(memberId)
        for (req in requestsList) {
            val updatedReq = req.copy(memberName = newName)
            changeRequestDao.updateChangeRequest(updatedReq)

            // Sync updated change request entry to Supabase
            if (com.example.data.SupabaseClient.getAccessToken() != null) {
                com.example.data.SupabaseClient.dbUpdateChangeRequest(updatedReq)
            }
        }
    }

    /**
     * Comment: Reconcile legacy savings rows before assigning keys so an identity mismatch
     * cannot be mistaken for a new contribution and duplicated during the same sync.
     */
    private suspend fun backfillSavingsSyncKeys(
        remoteSavings: List<Savings>,
        localSavings: List<Savings>
    ): Pair<List<Savings>, List<Savings>> {
        val updatedRemote = remoteSavings.toMutableList()
        val updatedLocal = localSavings.toMutableList()

        // Comment: Self-heal any remote rows still missing a key (server-side backfill not yet run).
        // Abort this sync if the patch fails; inventing a local key while the remote row remains blank
        // would make the next upsert create a duplicate.
        for (i in updatedRemote.indices) {
            val remote = updatedRemote[i]
            if (remote.syncKey.isBlank()) {
                val key = java.util.UUID.nameUUIDFromBytes("savings:${remote.id}".toByteArray(Charsets.UTF_8)).toString()
                val patched = com.example.data.SupabaseClient.dbPatchSavingsSyncKey(remote.id, key)
                if (!patched) {
                    throw IllegalStateException("Could not assign sync key to remote savings ${remote.id}")
                }
                updatedRemote[i] = remote.copy(syncKey = key)
            }
        }

        val remoteById = updatedRemote.associateBy { it.id }
        val remoteByKey = updatedRemote.associateBy { it.syncKey }
        val matchedRemoteIds = mutableSetOf<Int>()

        // Comment: Reconcile by stable key first, then legacy numeric ID, exact row details,
        // and finally a unique member/timestamp identity. Only proven local-only rows get a new key.
        for (i in updatedLocal.indices) {
            val local = updatedLocal[i]
            val keyMatch = remoteByKey[local.syncKey]
                ?.takeIf { local.syncKey.isNotBlank() && it.id !in matchedRemoteIds }
            val idMatch = remoteById[local.id]
                ?.takeIf { it.memberId == local.memberId && it.id !in matchedRemoteIds }
            val detailsMatches = updatedRemote
                .filter { it.id !in matchedRemoteIds }
                .filter {
                    it.memberId == local.memberId &&
                            it.amount == local.amount &&
                            it.dateText == local.dateText &&
                            it.timestamp == local.timestamp
                }
            val detailsMatch = detailsMatches.singleOrNull()
            val legacyIdentityMatches = updatedRemote
                .filter { it.id !in matchedRemoteIds }
                .filter { it.memberId == local.memberId && it.timestamp == local.timestamp }
            val legacyIdentityMatch = legacyIdentityMatches.singleOrNull()
            val matchedRemote = keyMatch ?: idMatch ?: detailsMatch ?: legacyIdentityMatch

            if (matchedRemote == null && (detailsMatches.size > 1 || legacyIdentityMatches.size > 1)) {
                throw IllegalStateException("Ambiguous legacy identity for local savings ${local.id}")
            }

            if (matchedRemote != null) {
                matchedRemoteIds.add(matchedRemote.id)
                // Comment: Use the remote row as the canonical record so its numeric ID and sync key
                // replace the local identity in one transaction when the two databases differ.
                if (local.id != matchedRemote.id) {
                    savingsDao.replaceSavingsWithRemote(local, matchedRemote)
                } else if (local != matchedRemote) {
                    savingsDao.insertSavings(matchedRemote)
                }
                updatedLocal[i] = matchedRemote
            } else if (local.syncKey.isBlank()) {
                // Comment: Do not push from the backfill itself; the normal sync loop must see the
                // final local key map before it performs the single local-only upsert.
                val freshKey = java.util.UUID.randomUUID().toString()
                val withKey = local.copy(syncKey = freshKey)
                savingsDao.updateSavings(withKey)
                updatedLocal[i] = withKey
            }
        }

        return Pair(updatedRemote, updatedLocal)
    }


}
