package com.example.data.local

import androidx.room.*
import com.example.data.model.Member
import com.example.data.model.Savings
import com.example.data.model.AppSettings
import com.example.data.model.ChangeRequest
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Member-related database operations.
 */
@Dao
interface MemberDao {
    @Query("SELECT * FROM members ORDER BY totalSavings DESC")
    fun getAllMembers(): Flow<List<Member>>

    @Query("SELECT * FROM members ORDER BY totalSavings DESC")
    suspend fun getAllMembersDirect(): List<Member>

    @Query("SELECT * FROM members WHERE id = :id")
    suspend fun getMemberById(id: Int): Member?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: Member): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<Member>)

    @Update
    suspend fun updateMember(member: Member)

    @Delete
    suspend fun deleteMember(member: Member)

    // Comment: Atomically swap a locally-inserted member (local auto-increment ID) for the remote row (SERIAL ID) so a crash cannot leave a missing row
    @Transaction
    suspend fun replaceMemberWithRemote(old: Member, new: Member) {
        deleteMember(old)
        insertMember(new)
    }
}

/**
 * Data Access Object for Savings-related database operations.
 */
@Dao
interface SavingsDao {
    @Query("SELECT * FROM savings ORDER BY timestamp DESC")
    fun getAllSavings(): Flow<List<Savings>>

    @Query("SELECT * FROM savings WHERE memberId = :memberId ORDER BY timestamp DESC")
    fun getSavingsForMember(memberId: Int): Flow<List<Savings>>

    @Query("SELECT * FROM savings WHERE memberId = :memberId")
    suspend fun getSavingsForMemberDirect(memberId: Int): List<Savings>

    @Query("SELECT * FROM savings WHERE id = :id")
    suspend fun getSavingsById(id: Int): Savings?

    // Comment: Fetch all local savings records directly for bidirectional sync
    @Query("SELECT * FROM savings ORDER BY timestamp DESC")
    suspend fun getAllSavingsDirect(): List<Savings>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavings(savings: Savings): Long

    @Update
    suspend fun updateSavings(savings: Savings)

    @Delete
    suspend fun deleteSavings(savings: Savings)

    // Comment: Atomically swap a locally-inserted savings row (local auto-increment ID) for the remote row (SERIAL ID) so a crash cannot leave a missing row
    @Transaction
    suspend fun replaceSavingsWithRemote(old: Savings, new: Savings) {
        deleteSavings(old)
        insertSavings(new)
    }
}

/**
 * Data Access Object for AppSettings-related database operations.
 */
@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<AppSettings?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettingsDirect(): AppSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSettings(settings: AppSettings)
}

/**
 * Data Access Object for ChangeRequest-related database operations.
 */
@Dao
interface ChangeRequestDao {
    @Query("SELECT * FROM change_requests ORDER BY timestamp DESC")
    fun getAllChangeRequests(): Flow<List<ChangeRequest>>

    @Query("SELECT * FROM change_requests WHERE id = :id")
    suspend fun getChangeRequestById(id: Int): ChangeRequest?

    @Query("SELECT * FROM change_requests WHERE memberId = :memberId")
    suspend fun getChangeRequestsForMemberDirect(memberId: Int): List<ChangeRequest>

    // Comment: Fetch all local change requests directly for bidirectional sync
    @Query("SELECT * FROM change_requests ORDER BY timestamp DESC")
    suspend fun getAllChangeRequestsDirect(): List<ChangeRequest>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChangeRequest(request: ChangeRequest): Long

    @Update
    suspend fun updateChangeRequest(request: ChangeRequest)

    @Delete
    suspend fun deleteChangeRequest(request: ChangeRequest)

    // Comment: Atomically swap a locally-inserted change request (local auto-increment ID) for the remote row (SERIAL ID) so a crash cannot leave a missing row
    @Transaction
    suspend fun replaceChangeRequestWithRemote(old: ChangeRequest, new: ChangeRequest) {
        deleteChangeRequest(old)
        insertChangeRequest(new)
    }
}
