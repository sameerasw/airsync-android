package com.sameerasw.airsync.utils

import kotlin.random.Random

/**
 * Utility class for generating realistic test notifications for developer mode
 */
object TestNotificationUtil {

    private data class AppInfo(
        val appName: String,
        val packageName: String,
        val sampleTitles: List<String>,
        val sampleBodies: List<String>
    )

    private val apps = listOf(
        AppInfo(
            appName = "WhatsApp",
            packageName = "com.whatsapp",
            sampleTitles = listOf(
                "Alice Johnson",
                "Michael Chen",
                "Sarah Williams",
                "David Martinez",
                "Emma Thompson",
                "Family Group",
                "Work Team",
                "Study Group"
            ),
            sampleBodies = listOf(
                "Hey! Are you free for lunch today?",
                "Can you send me the document we discussed?",
                "Running 10 minutes late to the meeting",
                "Thanks for your help yesterday! 😊",
                "Did you see the news about the project?",
                "Let's catch up this weekend",
                "The deadline has been moved to Friday",
                "Great job on the presentation!"
            )
        ),
        AppInfo(
            appName = "Facebook",
            packageName = "com.facebook.katana",
            sampleTitles = listOf(
                "Jennifer Lee",
                "Mark Rodriguez",
                "Lisa Park",
                "Alex Kumar",
                "Rachel Green",
                "Facebook",
                "Memories",
                "Friends"
            ),
            sampleBodies = listOf(
                "commented on your post",
                "liked your photo",
                "You have 3 new friend requests",
                "shared a memory with you",
                "posted in the group you follow",
                "Your post from last year",
                "invited you to an event",
                "tagged you in a photo"
            )
        ),
        AppInfo(
            appName = "GitHub",
            packageName = "com.github.android",
            sampleTitles = listOf(
                "GitHub",
                "Pull Request",
                "Issue Update",
                "Repository",
                "Workflow",
                "Security Alert",
                "Dependabot",
                "Actions"
            ),
            sampleBodies = listOf(
                "New pull request in airsync-android",
                "Issue #42 has been updated",
                "Workflow run completed successfully",
                "Security vulnerability detected",
                "Dependabot created a pull request",
                "Review requested on PR #123",
                "Build failed in main branch",
                "New release v2.1.0 is available"
            )
        ),
        AppInfo(
            appName = "Gmail",
            packageName = "com.google.android.gm",
            sampleTitles = listOf(
                "team@company.com",
                "noreply@github.com",
                "Google",
                "Amazon",
                "Netflix",
                "PayPal",
                "LinkedIn",
                "Apple"
            ),
            sampleBodies = listOf(
                "Meeting agenda for tomorrow's call",
                "Your monthly statement is ready",
                "Password changed successfully",
                "New sign-in from Chrome on Windows",
                "Your order has been shipped",
                "Invoice #12345 - Payment received",
                "Weekly team update digest",
                "Reminder: Task due tomorrow"
            )
        )
    )

    /**
     * Generates a random test notification with realistic data
     */
    fun generateRandomNotification(): TestNotification {
        val selectedApp = apps.random()
        val randomId = Random.nextInt(100000, 999999).toString()
        val randomTitle = selectedApp.sampleTitles.random()
        val randomBody = selectedApp.sampleBodies.random()
        
        // Randomly choose priority
        val priority = if (Random.nextBoolean()) "alerting" else "silent"
        
        // Add random mock actions
        val actions = mutableListOf<Pair<String, String>>()
        if (Random.nextBoolean()) {
            actions.add("Reply" to "reply")
            if (Random.nextBoolean()) {
                actions.add("Mark as read" to "button")
            }
        } else if (Random.nextBoolean()) {
            actions.add("Open" to "button")
            actions.add("Dismiss" to "button")
        }

        return TestNotification(
            id = randomId,
            title = randomTitle,
            body = randomBody,
            appName = selectedApp.appName,
            packageName = selectedApp.packageName,
            priority = priority,
            actions = actions
        )
    }

    /**
     * Data class representing a test notification
     */
    data class TestNotification(
        val id: String,
        val title: String,
        val body: String,
        val appName: String,
        val packageName: String,
        val priority: String,
        val actions: List<Pair<String, String>>
    )
}
