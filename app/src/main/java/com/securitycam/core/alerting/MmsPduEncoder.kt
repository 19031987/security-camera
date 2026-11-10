package com.securitycam.core.alerting

import java.io.ByteArrayOutputStream

/**
 * Builds the binary message (an "M-Send.req" PDU, as defined by the OMA MMS encapsulation
 * standard) that the phone hands to the mobile network to send a picture message: one or more
 * recipients, a text part and a JPEG part.
 *
 * Android has no public class for this, so the few fields needed are written out here.
 */
object MmsPduEncoder {

    // Header field codes (already carrying the "short integer" high bit).
    private const val HEADER_MESSAGE_TYPE = 0x8C
    private const val HEADER_TRANSACTION_ID = 0x98
    private const val HEADER_MMS_VERSION = 0x8D
    private const val HEADER_FROM = 0x89
    private const val HEADER_TO = 0x97
    private const val HEADER_CONTENT_TYPE = 0x84

    private const val MESSAGE_TYPE_SEND_REQ = 0x80
    private const val MMS_VERSION_1_2 = 0x92
    private const val FROM_INSERT_ADDRESS_TOKEN = 0x81

    // Well-known content types.
    private const val CT_MULTIPART_MIXED = 0xA3
    private const val CT_TEXT_PLAIN = 0x83
    private const val CT_IMAGE_JPEG = 0x9E
    private const val PARAM_CHARSET = 0x81
    private const val CHARSET_UTF8 = 0xEA
    private const val PART_HEADER_CONTENT_LOCATION = 0x8E

    fun encodeSendRequest(
        transactionId: String,
        recipients: List<String>,
        text: String,
        jpeg: ByteArray
    ): ByteArray {
        require(recipients.isNotEmpty()) { "At least one recipient is required" }
        require(transactionId.isNotEmpty() && transactionId.all { it.code in 0x21..0x7E }) {
            "Transaction id must be printable ASCII"
        }
        val out = ByteArrayOutputStream(jpeg.size + 512)

        out.write(HEADER_MESSAGE_TYPE)
        out.write(MESSAGE_TYPE_SEND_REQ)

        out.write(HEADER_TRANSACTION_ID)
        writeTextString(out, transactionId)

        out.write(HEADER_MMS_VERSION)
        out.write(MMS_VERSION_1_2)

        // "From" is left for the network to fill in with this SIM's number.
        out.write(HEADER_FROM)
        out.write(0x01)
        out.write(FROM_INSERT_ADDRESS_TOKEN)

        for (recipient in recipients) {
            require(recipient.all { it == '+' || it.isDigit() }) { "Recipient must be a phone number" }
            out.write(HEADER_TO)
            writeTextString(out, "$recipient/TYPE=PLMN")
        }

        // Content-Type must be the last header; the parts follow it directly.
        out.write(HEADER_CONTENT_TYPE)
        out.write(CT_MULTIPART_MIXED)

        writeUintvar(out, 2)
        writePart(
            out,
            contentType = byteArrayOf(0x03, CT_TEXT_PLAIN.toByte(), PARAM_CHARSET.toByte(), CHARSET_UTF8.toByte()),
            location = "text.txt",
            data = text.toByteArray(Charsets.UTF_8)
        )
        writePart(out, contentType = byteArrayOf(CT_IMAGE_JPEG.toByte()), location = "image.jpg", data = jpeg)
        return out.toByteArray()
    }

    private fun writePart(out: ByteArrayOutputStream, contentType: ByteArray, location: String, data: ByteArray) {
        val headers = ByteArrayOutputStream()
        headers.write(contentType)
        headers.write(PART_HEADER_CONTENT_LOCATION)
        writeTextString(headers, location)

        writeUintvar(out, headers.size().toLong())
        writeUintvar(out, data.size.toLong())
        out.write(headers.toByteArray())
        out.write(data)
    }

    /** ASCII text followed by a zero byte. */
    private fun writeTextString(out: ByteArrayOutputStream, text: String) {
        out.write(text.toByteArray(Charsets.US_ASCII))
        out.write(0x00)
    }

    /** Variable-length unsigned integer: 7 bits per byte, high bit set on all but the last. */
    internal fun writeUintvar(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "uintvar cannot be negative" }
        val groups = ArrayList<Int>()
        var remaining = value
        do {
            groups.add((remaining and 0x7F).toInt())
            remaining = remaining ushr 7
        } while (remaining > 0)
        for (i in groups.indices.reversed()) {
            out.write(if (i > 0) groups[i] or 0x80 else groups[i])
        }
    }
}
