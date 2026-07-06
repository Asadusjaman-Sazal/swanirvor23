package com.example.data.repository

import com.example.data.local.MemberDao
import com.example.data.local.SavingsDao
import com.example.data.local.AppSettingsDao
import com.example.data.local.ChangeRequestDao
import com.example.data.model.AppSettings
import com.example.data.model.Member
import com.example.data.model.Savings
import com.example.data.model.ChangeRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * Repository class that abstracts data sources and provides clean access to DAOs.
 * Also handles initial database seeding with high-fidelity mock data on first launch.
 */
class SavingsRepository(
    private val memberDao: MemberDao,
    private val savingsDao: SavingsDao,
    private val appSettingsDao: AppSettingsDao,
    private val changeRequestDao: ChangeRequestDao
) {
    val allMembers: Flow<List<Member>> = memberDao.getAllMembers()
    val allSavings: Flow<List<Savings>> = savingsDao.getAllSavings()
    val appSettings: Flow<AppSettings?> = appSettingsDao.getSettingsFlow()
    val allChangeRequests: Flow<List<ChangeRequest>> = changeRequestDao.getAllChangeRequests()

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
                // Comment: Update local record with the auto-generated SERIAL remote database ID to avoid primary key conflicts
                memberDao.deleteMember(localMemberWithId)
                memberDao.insertMember(inserted)
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
        // Comment: Securely delete the member profile from Supabase remote database table if user is authenticated
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            com.example.data.SupabaseClient.dbDeleteMember(member)
        }
    }

    suspend fun insertSavings(savings: Savings) = withContext(Dispatchers.IO) {
        val localId = savingsDao.insertSavings(savings).toInt()
        val localSavingsWithId = savings.copy(id = localId)
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
        // Comment: Sync savings contribution to Supabase remote database table
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            val inserted = com.example.data.SupabaseClient.dbInsertSavings(localSavingsWithId)
            if (inserted != null && inserted.id != localSavingsWithId.id) {
                // Comment: Update local record with the auto-generated SERIAL remote database ID to avoid primary key conflicts
                savingsDao.deleteSavings(localSavingsWithId)
                savingsDao.insertSavings(inserted)
            }
        }
    }

    suspend fun updateSavings(savings: Savings, oldAmount: Double) = withContext(Dispatchers.IO) {
        savingsDao.updateSavings(savings)
        // Dynamically update the member's totalSavings in the database
        val member = memberDao.getMemberById(savings.memberId)
        if (member != null) {
            val updatedMember = member.copy(totalSavings = member.totalSavings - oldAmount + savings.amount)
            memberDao.updateMember(updatedMember)
            // Comment: Sync updated member totalSavings to Supabase remote database table
            if (com.example.data.SupabaseClient.getAccessToken() != null) {
                com.example.data.SupabaseClient.dbUpdateMember(updatedMember)
            }
        }
        // Comment: Sync updated savings contribution to Supabase remote database table
        if (com.example.data.SupabaseClient.getAccessToken() != null) {
            com.example.data.SupabaseClient.dbUpdateSavings(savings)
        }
    }

    suspend fun deleteSavings(savings: Savings) = withContext(Dispatchers.IO) {
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
            com.example.data.SupabaseClient.dbDeleteSavings(savings)
        }
    }

    suspend fun getSettingsDirect(): AppSettings? = withContext(Dispatchers.IO) {
        appSettingsDao.getSettingsDirect()
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
            val preseededMembers = allLocalMembers.filter { it.email.trim().lowercase() in seedEmails }
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
                // Comment: Update local record with the auto-generated SERIAL remote database ID to avoid primary key conflicts
                changeRequestDao.deleteChangeRequest(localRequestWithId)
                changeRequestDao.insertChangeRequest(inserted)
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
    suspend fun syncWithSupabase(): List<ChangeRequest> = withContext(Dispatchers.IO) {
        val newPendingRequests = mutableListOf<ChangeRequest>()
        val token = com.example.data.SupabaseClient.getAccessToken() ?: return@withContext emptyList()
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
                                val localSaving = savingsDao.getAllSavingsDirect().find { it.id == req.savingsId }
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
                                val localSaving = savingsDao.getAllSavingsDirect().find { it.id == req.savingsId }
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
                                    // Comment: Remove the member locally since their removal was approved by the admin
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
            val remoteMembers = com.example.data.SupabaseClient.dbFetchMembers()
            val localMembers = memberDao.getAllMembersDirect()

            // Filter out seed members
            val filteredRemoteMembers = remoteMembers.filter { it.email.trim().lowercase() !in seedEmails }
            val filteredLocalMembers = localMembers.filter { it.email.trim().lowercase() !in seedEmails }

            // Create remote mapping by email
            val remoteByEmail = filteredRemoteMembers.associateBy { it.email.trim().lowercase() }
            val memberIdMap = mutableMapOf<Int, Int>() // maps localMemberId -> remoteMemberId

            // Track IDs we insert/keep locally
            val deDuplicatedIds = mutableSetOf<Int>()

            for (localMember in filteredLocalMembers) {
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
                if (m.id !in deDuplicatedIds && m.email.trim().lowercase() !in seedEmails) {
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
            val remoteSavings = com.example.data.SupabaseClient.dbFetchSavings()
            val localSavings = savingsDao.getAllSavingsDirect()

            // Filter out savings records that reference deleted or seeded members
            val currentLocalMembers = memberDao.getAllMembersDirect()
            val activeMemberIds = currentLocalMembers.map { it.id }.toSet()

            val filteredRemoteSavings = remoteSavings.filter { it.memberId in activeMemberIds }
            val filteredLocalSavings = localSavings.filter { it.memberId in activeMemberIds }

            // Map remote savings by ID
            val remoteSavingsById = filteredRemoteSavings.associateBy { it.id }
            val localSavingsById = filteredLocalSavings.associateBy { it.id }

            // Match and Push local savings that are not in Supabase
            for (localSaving in filteredLocalSavings) {
                val remoteSaving = remoteSavingsById[localSaving.id]
                if (remoteSaving == null) {
                    // Check if there is a matching remote record by details to avoid duplicate insertions
                    val isDuplicate = filteredRemoteSavings.any {
                        it.memberId == localSaving.memberId &&
                                it.amount == localSaving.amount &&
                                it.dateText == localSaving.dateText &&
                                it.timestamp == localSaving.timestamp
                    }
                    if (!isDuplicate) {
                        // Push new saving contribution to remote
                        val inserted = com.example.data.SupabaseClient.dbInsertSavings(localSaving)
                        if (inserted != null && inserted.id != localSaving.id) {
                            savingsDao.deleteSavings(localSaving)
                            savingsDao.insertSavings(inserted)
                        }
                    }
                } else {
                    // If remote exists but differs, update local record with the remote record
                    if (localSaving != remoteSaving) {
                        savingsDao.insertSavings(remoteSaving)
                    }
                }
            }

            // Pull any remote savings who are not present locally
            for (remoteSaving in filteredRemoteSavings) {
                if (remoteSaving.id !in localSavingsById) {
                    // Check if duplicate exists locally by details
                    val isDuplicate = filteredLocalSavings.any {
                        it.memberId == remoteSaving.memberId &&
                                it.amount == remoteSaving.amount &&
                                it.dateText == remoteSaving.dateText &&
                                it.timestamp == remoteSaving.timestamp
                    }
                    if (!isDuplicate) {
                        savingsDao.insertSavings(remoteSaving)
                    }
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
