package com.securitycam.alerting

import com.securitycam.core.alerting.MmsPduEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MmsPduEncoderTest {

    private val jpeg = ByteArray(300) { (it % 251).toByte() }
    private val text = "Security Camera: Person detected at 2026-11-09 14:22:05. ✓"

    private fun uintvar(value: Long): ByteArray =
        ByteArrayOutputStream().also { MmsPduEncoder.writeUintvar(it, value) }.toByteArray()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun uintvarEncoding() {
        assertArrayEquals(bytes(0x00), uintvar(0))
        assertArrayEquals(bytes(0x7F), uintvar(127))
        assertArrayEquals(bytes(0x81, 0x00), uintvar(128))
        assertArrayEquals(bytes(0x82, 0x2C), uintvar(300))
        assertArrayEquals(bytes(0x87, 0xA5, 0x1A), uintvar(0x1D29A))
    }

    @Test
    fun messageStartsWithTheMandatoryHeadersInOrder() {
        val pdu = MmsPduEncoder.encodeSendRequest("Tabc123", listOf("+14155550123"), text, jpeg)

        // Message-Type: m-send-req
        assertArrayEquals(bytes(0x8C, 0x80), pdu.copyOfRange(0, 2))
        // Transaction-Id: "Tabc123" + NUL
        assertEquals(0x98, pdu[2].toInt() and 0xFF)
        assertEquals("Tabc123", String(pdu, 3, 7, Charsets.US_ASCII))
        assertEquals(0, pdu[10].toInt())
        // MMS-Version 1.2, then From: insert-address-token
        assertArrayEquals(bytes(0x8D, 0x92, 0x89, 0x01, 0x81), pdu.copyOfRange(11, 16))
        // To: number/TYPE=PLMN
        assertEquals(0x97, pdu[16].toInt() and 0xFF)
        assertEquals("+14155550123/TYPE=PLMN", String(pdu, 17, 22, Charsets.US_ASCII))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRecipientThatIsNotANumberIsRejected() {
        MmsPduEncoder.encodeSendRequest("T1", listOf("+1415\r\nBcc: someone"), text, jpeg)
    }

    @Test(expected = IllegalArgumentException::class)
    fun atLeastOneRecipientIsRequired() {
        MmsPduEncoder.encodeSendRequest("T1", emptyList(), text, jpeg)
    }

    /**
     * Reads the message back with Android's own MMS parser, the one the phone's messaging
     * service uses, to confirm that recipients, text and picture come out intact.
     */
    @Test
    fun androidsOwnParserReadsTheMessageBack() {
        val pdu = MmsPduEncoder.encodeSendRequest(
            "Tabc123", listOf("+14155550123", "+919876543210"), text, jpeg
        )

        val parserClass = Class.forName("com.google.android.mms.pdu.PduParser")
        val parser = parserClass.getConstructor(ByteArray::class.java, Boolean::class.javaPrimitiveType)
            .newInstance(pdu, true)
        val message = parserClass.getMethod("parse").invoke(parser)
        assertNotNull("Android's parser rejected the message", message)
        assertEquals("com.google.android.mms.pdu.SendReq", message!!.javaClass.name)

        // Message type 0x80 = send request
        assertEquals(0x80, message.javaClass.getMethod("getMessageType").invoke(message))
        assertEquals("Tabc123", String(message.javaClass.getMethod("getTransactionId").invoke(message) as ByteArray))

        val to = message.javaClass.getMethod("getTo").invoke(message) as Array<*>
        val numbers = to.map { it!!.javaClass.getMethod("getString").invoke(it) as String }
        // The parser recognises the "/TYPE=PLMN" phone-number marker and removes it.
        assertEquals(listOf("+14155550123", "+919876543210"), numbers)

        val body = message.javaClass.getMethod("getBody").invoke(message)!!
        assertEquals(2, body.javaClass.getMethod("getPartsNum").invoke(body))
        val getPart = body.javaClass.getMethod("getPart", Int::class.javaPrimitiveType)

        val textPart = getPart.invoke(body, 0)!!
        assertEquals("text/plain", String(textPart.javaClass.getMethod("getContentType").invoke(textPart) as ByteArray))
        // 106 is the registered number for UTF-8
        assertEquals(106, textPart.javaClass.getMethod("getCharset").invoke(textPart))
        assertEquals(text, String(textPart.javaClass.getMethod("getData").invoke(textPart) as ByteArray, Charsets.UTF_8))

        val imagePart = getPart.invoke(body, 1)!!
        assertEquals("image/jpeg", String(imagePart.javaClass.getMethod("getContentType").invoke(imagePart) as ByteArray))
        assertEquals("image.jpg", String(imagePart.javaClass.getMethod("getContentLocation").invoke(imagePart) as ByteArray))
        assertTrue(jpeg.contentEquals(imagePart.javaClass.getMethod("getData").invoke(imagePart) as ByteArray))
    }

    @Test
    fun aRealisticallySizedPictureSurvivesTheRoundTrip() {
        val big = ByteArray(200_000) { (it * 31 % 256).toByte() }
        val pdu = MmsPduEncoder.encodeSendRequest("Tbig", listOf("+14155550123"), "x", big)

        val parserClass = Class.forName("com.google.android.mms.pdu.PduParser")
        val message = parserClass.getMethod("parse").invoke(
            parserClass.getConstructor(ByteArray::class.java, Boolean::class.javaPrimitiveType).newInstance(pdu, true)
        )!!
        val body = message.javaClass.getMethod("getBody").invoke(message)!!
        val image = body.javaClass.getMethod("getPart", Int::class.javaPrimitiveType).invoke(body, 1)!!
        assertTrue(big.contentEquals(image.javaClass.getMethod("getData").invoke(image) as ByteArray))
    }
}
