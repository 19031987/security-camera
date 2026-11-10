package com.securitycam.push

import com.securitycam.core.push.FcmSender
import com.securitycam.core.push.PushMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMessageTest {

    @Test
    fun aNormalAlertCarriesItsTextTimeAndPicture() {
        val thumb = ByteArray(2000) { (it % 200).toByte() }

        val data = PushMessage.build(PushMessage.TYPE_ALERT, "Person at your door", "Detected at 14:22.", 1234L, thumb)

        assertEquals("alert", data[PushMessage.KEY_TYPE])
        assertEquals("Person at your door", data[PushMessage.KEY_TITLE])
        assertEquals("Detected at 14:22.", data[PushMessage.KEY_MESSAGE])
        assertEquals("1234", data[PushMessage.KEY_TIME])
        assertArrayEquals(thumb, PushMessage.thumbnail(data))
        assertTrue(PushMessage.sizeOf(data) <= PushMessage.MAX_DATA_BYTES)
    }

    @Test
    fun theLargestAllowedThumbnailStillFitsUnderGooglesLimit() {
        val data = PushMessage.build(
            PushMessage.TYPE_ALERT, "T".repeat(500), "M".repeat(2000), Long.MAX_VALUE,
            ByteArray(PushMessage.MAX_THUMBNAIL_BYTES)
        )
        // Over-long text is cut to its limits, and the picture is still carried.
        assertEquals(PushMessage.MAX_TITLE_CHARS, data.getValue(PushMessage.KEY_TITLE).length)
        assertEquals(PushMessage.MAX_MESSAGE_CHARS, data.getValue(PushMessage.KEY_MESSAGE).length)
        assertNotNull(PushMessage.thumbnail(data))
        assertTrue(PushMessage.sizeOf(data) <= PushMessage.MAX_DATA_BYTES)
        assertTrue(PushMessage.sizeOf(data) < 4096)
    }

    @Test
    fun anOversizedPictureIsLeftOutButTheAlertStillGoes() {
        val data = PushMessage.build(PushMessage.TYPE_ALERT, "Title", "Message", 1L, ByteArray(10_000))
        assertNull(PushMessage.thumbnail(data))
        assertEquals("Title", data[PushMessage.KEY_TITLE])
    }

    @Test
    fun wideCharactersNeverPushTheMessageOverTheLimit() {
        // Each of these characters is three bytes in UTF-8.
        val data = PushMessage.build(
            PushMessage.TYPE_ARRIVAL, "आ".repeat(120), "आ".repeat(400), 1L, ByteArray(PushMessage.MAX_THUMBNAIL_BYTES)
        )
        assertTrue(PushMessage.sizeOf(data) <= PushMessage.MAX_DATA_BYTES)
        assertTrue(data.getValue(PushMessage.KEY_TITLE).isNotEmpty())
    }

    @Test
    fun noPictureAndCorruptPictureAreHandled() {
        assertNull(PushMessage.thumbnail(PushMessage.build(PushMessage.TYPE_TEST, "t", "m", 1L, null)))
        assertNull(PushMessage.thumbnail(mapOf(PushMessage.KEY_THUMBNAIL to "***not base64***")))
    }

    @Test
    fun linkCodesAreLongRandomAndMapToAValidTopic() {
        val a = PushMessage.newLinkCode()
        val b = PushMessage.newLinkCode()
        assertEquals(24, a.length)
        assertNotEquals(a, b)
        // No characters that are easily confused when read out or typed.
        assertFalse(a.any { it in "0O1lI" })
        assertEquals("cam_$a", PushMessage.topicFor(a))
        assertTrue(FcmSender.isValidTopic(PushMessage.topicFor(a)!!))
    }

    @Test
    fun aCodeTypedWithSurroundingSpacesStillWorksButJunkDoesNot() {
        val code = PushMessage.newLinkCode()
        assertEquals("cam_$code", PushMessage.topicFor("  $code "))
        assertNull(PushMessage.topicFor(""))
        assertNull(PushMessage.topicFor("short"))
        assertNull(PushMessage.topicFor("has spaces inside the code!!"))
        assertNull(PushMessage.topicFor("abcdefghijkmnpqrstuv/../x"))
    }
}
