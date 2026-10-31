package com.securitycam.alerting

import com.securitycam.core.alerting.JakartaEmailDispatcher
import com.securitycam.core.alerting.SmtpTransportSender
import com.securitycam.core.config.SecuritySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.mail.MessagingException
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

@OptIn(ExperimentalCoroutinesApi::class)
class EmailDispatcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private val sampleSettings = SecuritySettings(
        smtpHost = "smtp.test.com",
        smtpPort = 587,
        smtpUsername = "tester@test.com",
        smtpPassword = "testPassword123",
        smtpSenderEmail = "alerts@test.com",
        smtpSenderName = "Test Cam"
    )

    @Test
    fun testSuccessfulEmailDispatchWithAttachment() = runTest(testDispatcher) {
        val testFile = tempFolder.newFile("motion_snapshot.jpg").apply {
            writeBytes(ByteArray(1024) { 0x55.toByte() })
        }

        var sentMessage: MimeMessage? = null
        val mockSender = SmtpTransportSender { msg ->
            sentMessage = msg
        }

        val dispatcher = JakartaEmailDispatcher(
            settingsProvider = { sampleSettings },
            transportSender = mockSender,
            ioDispatcher = testDispatcher
        )

        val recipients = listOf("owner@example.com", "guard@example.com")
        val result = dispatcher.sendEmail(
            emailAddresses = recipients,
            subject = "[ALERT] Motion Detected",
            bodyHtml = "<h1>Intruder Alert</h1>",
            attachmentFile = testFile
        )

        assertTrue(result.isSuccess)
        assertEquals(2, result.deliveredCount)
        assertEquals(0, result.failedCount)

        assertNotNull(sentMessage)
        assertEquals("[ALERT] Motion Detected", sentMessage!!.subject)
        assertEquals(2, sentMessage!!.allRecipients.size)

        // Verify Multipart
        val content = sentMessage!!.content as MimeMultipart
        assertEquals(2, content.count) // HTML Part + Image Attachment

        val htmlPart = content.getBodyPart(0)
        assertTrue(htmlPart.contentType.contains("text/html"))

        val attachPart = content.getBodyPart(1)
        assertEquals("motion_snapshot.jpg", attachPart.fileName)
        assertTrue(attachPart.contentType.contains("image/jpeg"))
    }

    @Test
    fun testEmailDispatchWithoutAttachment() = runTest(testDispatcher) {
        var sentMessage: MimeMessage? = null
        val mockSender = SmtpTransportSender { msg ->
            sentMessage = msg
        }

        val dispatcher = JakartaEmailDispatcher(
            settingsProvider = { sampleSettings },
            transportSender = mockSender,
            ioDispatcher = testDispatcher
        )

        val result = dispatcher.sendEmail(
            emailAddresses = listOf("owner@example.com"),
            subject = "[DIAGNOSTIC] Test Email",
            bodyHtml = "<p>Diagnostic body</p>",
            attachmentFile = null
        )

        assertTrue(result.isSuccess)
        assertNotNull(sentMessage)
        val content = sentMessage!!.content as MimeMultipart
        assertEquals(1, content.count) // HTML Part only
    }

    @Test
    fun testEmptyRecipientsFailsEarly() = runTest(testDispatcher) {
        var sentCalled = false
        val mockSender = SmtpTransportSender { _ -> sentCalled = true }

        val dispatcher = JakartaEmailDispatcher(
            settingsProvider = { sampleSettings },
            transportSender = mockSender,
            ioDispatcher = testDispatcher
        )

        val result = dispatcher.sendEmail(
            emailAddresses = emptyList(),
            subject = "Subject",
            bodyHtml = "Body",
            attachmentFile = null
        )

        assertFalse(result.isSuccess)
        assertFalse(sentCalled)
        assertEquals(0, result.deliveredCount)
        assertEquals(0, result.failedCount)
    }

    @Test
    fun testSmtpExceptionHandling() = runTest(testDispatcher) {
        val mockSender = SmtpTransportSender { _ ->
            throw MessagingException("Authentication Failed: Bad Credentials")
        }

        val dispatcher = JakartaEmailDispatcher(
            settingsProvider = { sampleSettings },
            transportSender = mockSender,
            ioDispatcher = testDispatcher
        )

        val result = dispatcher.sendEmail(
            emailAddresses = listOf("owner@example.com"),
            subject = "Test",
            bodyHtml = "Body",
            attachmentFile = null
        )

        assertFalse(result.isSuccess)
        assertEquals(0, result.deliveredCount)
        assertEquals(1, result.failedCount)
        assertTrue(result.errorMessage!!.contains("Authentication Failed"))
    }
}
