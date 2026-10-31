package com.securitycam.core.alerting

import com.securitycam.core.config.SecuritySettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Transport sender abstraction for JavaMail/Jakarta Mail session, enabling headless mock verification.
 */
fun interface SmtpTransportSender {
    @Throws(MessagingException::class)
    fun send(message: MimeMessage)
}

class JakartaEmailDispatcher(
    private val settingsProvider: () -> SecuritySettings,
    private val transportSender: SmtpTransportSender = SmtpTransportSender { Transport.send(it) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : EmailDispatcher {

    override suspend fun sendEmail(
        emailAddresses: List<String>,
        subject: String,
        bodyHtml: String,
        attachmentFile: File?
    ): DispatchResult = withContext(ioDispatcher) {
        if (emailAddresses.isEmpty()) {
            return@withContext DispatchResult(
                isSuccess = false,
                deliveredCount = 0,
                failedCount = 0,
                errorMessage = "No recipient email addresses provided"
            )
        }

        val settings = settingsProvider()
        if (settings.smtpHost.isBlank() || settings.smtpUsername.isBlank()) {
            return@withContext DispatchResult(
                isSuccess = false,
                deliveredCount = 0,
                failedCount = emailAddresses.size,
                errorMessage = "SMTP host or credentials not configured"
            )
        }

        return@withContext try {
            val session = createSession(settings)
            val mimeMessage = buildMimeMessage(session, settings, emailAddresses, subject, bodyHtml, attachmentFile)
            transportSender.send(mimeMessage)
            DispatchResult(
                isSuccess = true,
                deliveredCount = emailAddresses.size,
                failedCount = 0
            )
        } catch (e: Exception) {
            DispatchResult(
                isSuccess = false,
                deliveredCount = 0,
                failedCount = emailAddresses.size,
                errorMessage = "SMTP dispatch failed: ${e.message}"
            )
        }
    }

    private fun createSession(settings: SecuritySettings): Session {
        val props = Properties().apply {
            put("mail.smtp.host", settings.smtpHost)
            put("mail.smtp.port", settings.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", settings.smtpTimeoutMs.toString())
            put("mail.smtp.timeout", settings.smtpTimeoutMs.toString())
            put("mail.smtp.writetimeout", settings.smtpTimeoutMs.toString())

            if (settings.smtpUseSsl || settings.smtpPort == 465) {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.port", settings.smtpPort.toString())
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            } else if (settings.smtpUseTls || settings.smtpPort == 587) {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
        }

        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(settings.smtpUsername, settings.smtpPassword)
            }
        })
    }

    fun buildMimeMessage(
        session: Session,
        settings: SecuritySettings,
        recipients: List<String>,
        subject: String,
        bodyHtml: String,
        attachmentFile: File?
    ): MimeMessage {
        val message = MimeMessage(session)
        val fromEmail = if (settings.smtpSenderEmail.isNotBlank()) settings.smtpSenderEmail else settings.smtpUsername
        message.setFrom(InternetAddress(fromEmail, settings.smtpSenderName, "UTF-8"))

        val recipientAddresses = recipients.map { InternetAddress(it.trim()) }.toTypedArray()
        message.setRecipients(Message.RecipientType.TO, recipientAddresses)
        message.subject = subject

        val multipart = MimeMultipart("mixed")

        // 1. HTML Body part
        val htmlBodyPart = MimeBodyPart().apply {
            setContent(bodyHtml, "text/html; charset=utf-8")
            setHeader("Content-Type", "text/html; charset=utf-8")
        }
        multipart.addBodyPart(htmlBodyPart)

        // 2. Attachment part (if present and exists)
        if (attachmentFile != null && attachmentFile.exists() && attachmentFile.length() > 0) {
            val attachmentBodyPart = MimeBodyPart().apply {
                val source = FileDataSource(attachmentFile)
                dataHandler = DataHandler(source)
                fileName = attachmentFile.name
                setHeader("Content-Type", "image/jpeg")
                setHeader("Content-Disposition", "attachment; filename=\"${attachmentFile.name}\"")
            }
            multipart.addBodyPart(attachmentBodyPart)
        }

        message.setContent(multipart)
        message.saveChanges()
        return message
    }
}
