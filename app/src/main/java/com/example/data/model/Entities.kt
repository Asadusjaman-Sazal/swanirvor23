package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a member of the Swanirvor-23 savings society.
 * Store details such as name, email, avatar, total savings, role and status.
 */
@Entity(tableName = "members")
data class Member(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val email: String,
    val avatarUrl: String? = null,
    val totalSavings: Double = 0.0,
    val role: String = "Member", // "Admin" or "Member"
    val status: String = "Active", // "Active" or "Suspended"
    // Comment: Field to track if an admin has sent a targeted notification reminder to this specific member
    val receivedAdminNotification: Boolean = false,
    // Comment: Store member's custom membership number assigned upon signup or registration
    val membershipNo: String = "",
    // Comment: Member's mobile number (with +880 country code) stored on the shared members row so admins can call/message; synced to the members.mobile_no column
    val mobileNo: String = ""
)

/**
 * Entity representing an individual savings contribution.
 * Tracks who made the contribution, the amount, date text, and system timestamp.
 */
@Entity(tableName = "savings")
data class Savings(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val memberId: Int,
    val memberName: String,
    val amount: Double,
    val dateText: String, // e.g., "Oct 17, 2023"
    val timestamp: Long = System.currentTimeMillis(),
    // Comment: Stable client-generated UUID used to match local and remote savings rows across two independent numeric ID sequences
    val syncKey: String = ""
)

/**
 * Entity representing app settings and configuration.
 * Stores appearance preferences, savings goals, notification scheduling, and user profile name/info.
 */
@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    // Comment: Default weekly savings goal set to 500.0 instead of 5000.0 per user request
    val personalGoal: Double = 500.0,
    val profileName: String = "John Doe",
    val membershipNo: String = "",
    // Comment: Member's mobile number stored with country code prefix (e.g. +8801XXXXXXXXX), synced to the app_settings.mobile_no column
    val mobileNo: String = "",
    val isDarkMode: Boolean = false,
    val notificationDay: String = "Thursday",
    val notificationTime: String = "09:00",
    val notificationText: String = "Reminder: Your weekly contribution of ৳500 is due tomorrow.",
    val enableNotifications: Boolean = true,
    val profileImageUrl: String? = null
)

/**
 * Entity representing a request to modify or delete a savings entry.
 * Created by users and approved/rejected by admins in the Admin Panel.
 */
@Entity(tableName = "change_requests")
data class ChangeRequest(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val savingsId: Int,
    val memberId: Int,
    val memberName: String,
    val requestType: String, // "Edit" or "Delete"
    val originalAmount: Double,
    val newAmount: Double? = null,
    val originalDateText: String,
    val newDateText: String? = null,
    val status: String = "Pending", // "Pending", "Approved", "Rejected"
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Entity representing one member's Weekly Savings Goal.
 * Every member of the collective saves a different amount each week, so instead of a single society-wide row this
 * table holds one goal per member, keyed by the member's lowercased email (the identity members are matched on across
 * devices). All Total Due / Projected Savings calculations read each member's own goal from this one shared table, so
 * every device computes identical totals. Deliberately separate from AppSettings, which is private to each user.
 */
@Entity(tableName = "community_settings")
data class CommunitySettings(
    // Comment: Member this goal belongs to, stored lowercased and trimmed so local rows match the remote rows across devices
    @PrimaryKey val memberEmail: String,
    // Comment: This member's Weekly Savings Goal; their Total Due / Projected Savings calculations use it
    val weeklyGoal: Double = 500.0,
    // Comment: Version of the remote row this goal was last read from (0 = never confirmed by the server). It is the
    // precondition sent with the next remote write, so an admin device holding an older value cannot overwrite a
    // newer goal set on another device. The explicit default keeps the column DDL in step with migration 13->14.
    @ColumnInfo(defaultValue = "0") val remoteVersion: Int = 0
)

/**
 * Entity representing a deposit of collected society funds into the bank account.
 * Distinct from Savings (money collected FROM members): this records money MOVED to the bank,
 * so "Cash in Hand" (collected minus banked) can be derived.
 */
@Entity(tableName = "bank_deposits")
data class BankDeposit(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val amount: Double,
    val dateText: String, // e.g., "17-10-2023", same DD-MM-YYYY convention as Savings.dateText
    val timestamp: Long = System.currentTimeMillis(),
    val depositedById: Int,
    // Comment: Denormalized depositor name so the ledger row stays readable offline and after a member is removed (mirrors Savings.memberName)
    val depositedByName: String,
    // Comment: Stable client-generated UUID used to match local and remote rows across two independent numeric ID sequences
    val syncKey: String = ""
)
