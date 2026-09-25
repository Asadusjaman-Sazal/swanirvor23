package com.example

import com.example.data.SupabaseClient
import com.example.data.model.Member
import com.example.data.model.Savings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SupabaseClientJsonTest {

    @Test
    fun `member serializes without a password field and round-trips`() {
        val member = Member(
            id = 42,
            name = "Asad",
            email = "asad@example.com",
            avatarUrl = null,
            totalSavings = 1500.0,
            role = "Admin",
            status = "Active",
            receivedAdminNotification = true,
            membershipNo = "LS-2023-042",
            mobileNo = "+8801712345678"
        )

        val json = SupabaseClient.memberToJson(member)
        assertFalse(json.has("password"))
        assertEquals("+8801712345678", json.optString("mobile_no"))

        val parsed = SupabaseClient.jsonToMember(json)
        assertEquals(member, parsed)
    }

    @Test
    fun `savings round-trips including sync key`() {
        val savings = Savings(
            id = 7,
            memberId = 42,
            memberName = "Asad",
            amount = 500.0,
            dateText = "15-08-2026",
            timestamp = 1755200000000L,
            syncKey = "key-123"
        )

        val json = SupabaseClient.savingsToJson(savings)
        val parsed = SupabaseClient.jsonToSavings(json)
        assertEquals(savings, parsed)
    }

    @Test
    fun `community settings parse the shared row version`() {
        val parsed = SupabaseClient.jsonToCommunitySettings(
            JSONObject()
                .put("id", 1)
                .put("weekly_goal", 800.0)
                .put("version", 4)
        )

        assertEquals(1, parsed.id)
        assertEquals(800.0, parsed.weeklyGoal, 0.0)
        assertEquals(4, parsed.remoteVersion)
    }

    @Test
    fun `community settings without a row version report never confirmed`() {
        val parsed = SupabaseClient.jsonToCommunitySettings(
            JSONObject().put("id", 1).put("weekly_goal", 500.0)
        )

        // Comment: 0 means this device has no confirmed baseline yet, so its next write must create the row
        assertEquals(0, parsed.remoteVersion)
    }

    @Test
    fun `savings without a sync key keeps it blank`() {
        val parsed = SupabaseClient.jsonToSavings(
            JSONObject()
                .put("id", 1)
                .put("member_id", 2)
                .put("member_name", "Member")
                .put("amount", 100.0)
                .put("date_text", "01-01-2026")
                .put("timestamp", 0L)
        )
        assertEquals("", parsed.syncKey)
    }
}
