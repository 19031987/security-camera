package com.securitycam.push

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.securitycam.core.alerting.DispatchResult
import com.securitycam.core.push.FcmPushNotifier
import com.securitycam.core.push.FcmSender
import com.securitycam.core.push.FirestorePictures
import com.securitycam.core.push.PictureLedger
import com.securitycam.core.push.PushMessage
import com.securitycam.core.push.ReceivedAlertStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FirestorePicturesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val code = "aBcdEFgh2345kmnpQRstuvwx"
    private lateinit var server: MockWebServer
    private lateinit var pictures: FirestorePictures

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        pictures = FirestorePictures(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun whatTheCameraStoresIsExactlyWhatAFamilyPhoneReadsBack() {
        val jpeg = ByteArray(50_000) { (it * 7 % 256).toByte() }
        server.enqueue(MockResponse().setBody("{}"))

        val stored = pictures.upload("my-project", "token-1", code, jpeg, 1234L)

        assertNotNull(stored.pictureId)
        val write = server.takeRequest()
        assertEquals("PATCH", write.method)
        assertEquals(
            "/v1/projects/my-project/databases/(default)/documents/cams/$code/pictures/${stored.pictureId}",
            write.requestUrl!!.encodedPath.replace("%28", "(").replace("%29", ")")
        )
        assertEquals("Bearer token-1", write.getHeader("Authorization"))
        val writtenBody = write.body.readUtf8()
        val fields = Gson().fromJson(writtenBody, JsonObject::class.java)["fields"].asJsonObject
        assertEquals("1234", fields["time"].asJsonObject["integerValue"].asString)

        // The database returns the document as it was written.
        server.enqueue(MockResponse().setBody("""{"name":"x","fields":${fields}}"""))
        val fetched = pictures.fetch("my-project", "api-key-1", code, stored.pictureId!!)

        assertArrayEquals(jpeg, fetched)
        val read = server.takeRequest()
        assertEquals("GET", read.method)
        assertEquals("api-key-1", read.requestUrl!!.queryParameter("key"))
        // Family phones read without signing in.
        assertNull(read.getHeader("Authorization"))
    }

    @Test
    fun pictureIdsAreRandomAndSafeToPutInAnAddress() {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        val a = pictures.upload("p", "t", code, byteArrayOf(1), 1L).pictureId!!
        val b = pictures.upload("p", "t", code, byteArrayOf(1), 1L).pictureId!!

        assertTrue(a != b)
        assertEquals(32, a.length)
        assertTrue(FirestorePictures.isSafeSegment(a))
    }

    @Test
    fun aRefusedWriteReportsGooglesReason() {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"The database (default) does not exist for project my-project"}}""")
        )

        val result = pictures.upload("my-project", "t", code, byteArrayOf(1, 2), 1L)

        assertNull(result.pictureId)
        assertTrue(result.error!!.contains("404"))
        assertTrue(result.error!!.contains("does not exist"))
        assertFalse(result.toDispatchResult().isSuccess)
    }

    @Test
    fun emptyOversizedOrBadlyAddressedPicturesAreNotSent() {
        assertNull(pictures.upload("p", "t", code, ByteArray(0), 1L).pictureId)
        assertNull(pictures.upload("p", "t", code, ByteArray(FirestorePictures.MAX_PICTURE_BYTES + 1), 1L).pictureId)
        assertNull(pictures.upload("p", "t", "bad/code", byteArrayOf(1), 1L).pictureId)
        assertNull(pictures.fetch("p", "k", code, "../../secrets"))
        assertNull(pictures.fetch("p", "k", "a/b", "abcdefgh12345678"))
        assertFalse(pictures.delete("p", "t", code, "x y"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aMissingForbiddenOrMalformedPictureReadsAsNothing() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"Missing or insufficient permissions."}}"""))
        server.enqueue(MockResponse().setBody("""{"fields":{}}"""))
        server.enqueue(MockResponse().setBody("""{"fields":{"jpeg":{"bytesValue":"***"}}}"""))
        server.enqueue(MockResponse().setBody("not json"))

        repeat(5) { assertNull(pictures.fetch("p", "k", code, "abcdefgh12345678")) }
    }

    @Test
    fun deletingRemovesAndTreatsAlreadyGoneAsDone() {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(pictures.delete("p", "tok", code, "abcdefgh12345678"))
        assertTrue(pictures.delete("p", "tok", code, "abcdefgh12345678"))
        assertFalse(pictures.delete("p", "tok", code, "abcdefgh12345678"))
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    @Test
    fun anUnreachableDatabaseIsAFailureNotACrash() {
        server.shutdown()
        assertNull(pictures.upload("p", "t", code, byteArrayOf(1), 1L).pictureId)
        assertNull(pictures.fetch("p", "k", code, "abcdefgh12345678"))
        assertFalse(pictures.delete("p", "t", code, "abcdefgh12345678"))
    }

    @Test
    fun theRulesOnlyAllowFetchingOnePictureByItsAddress() {
        val rules = FirestorePictures.RULES
        assertTrue(rules.contains("match /cams/{code}/pictures/{picture}"))
        assertTrue(rules.contains("allow get: if true;"))
        // No listing, no writing, nothing wider than the pictures.
        assertFalse(rules.contains("allow read"))
        assertFalse(rules.contains("allow list"))
        assertFalse(rules.contains("write"))
        assertFalse(rules.contains("{document=**}"))
    }

    // Ledger: old pictures are removed so the database doesn't grow for ever

    @Test
    fun theLedgerReturnsTheOldestOnceOverItsLimitAndSurvivesARestart() {
        val file = File(tempFolder.root, "ledger/online.json")
        val ledger = PictureLedger(file, capacity = 3)

        assertTrue(ledger.add(code, "p1").isEmpty())
        assertTrue(ledger.add(code, "p2").isEmpty())
        assertTrue(ledger.add("oldcodeoldcodeoldcode1234", "p3").isEmpty())
        val expired = ledger.add(code, "p4")

        assertEquals(listOf(PictureLedger.Entry(code, "p1")), expired)
        assertEquals(listOf("p2", "p3", "p4"), PictureLedger(file, 3).entries().map { it.pictureId })
        // A picture stored under an earlier link code is still deleted under that code.
        assertEquals("oldcodeoldcodeoldcode1234", PictureLedger(file, 3).entries()[1].linkCode)
    }

    @Test
    fun deletionsThatFailedAreKeptToTryAgain() {
        val ledger = PictureLedger(File(tempFolder.root, "online.json"), capacity = 2)
        ledger.add(code, "p1")
        ledger.add(code, "p2")
        val expired = ledger.add(code, "p3")

        ledger.restore(expired)

        assertEquals(listOf("p1", "p2", "p3"), ledger.entries().map { it.pictureId })
        // Next time, the same one comes up for deletion again.
        assertEquals("p1", ledger.add(code, "p4").first().pictureId)
    }

    @Test
    fun aCorruptLedgerStartsEmpty() {
        val file = File(tempFolder.root, "online.json").apply { writeText("{oops") }
        assertTrue(PictureLedger(file).entries().isEmpty())
    }

    // Message and family-phone list

    @Test
    fun thePushMessageCarriesThePictureIdAlongsideTheThumbnail() {
        val data = PushMessage.build(PushMessage.TYPE_ALERT, "Person at your door", "m", 1L, ByteArray(2000), "abcdefgh12345678")

        assertEquals("abcdefgh12345678", data[PushMessage.KEY_PICTURE_ID])
        assertNotNull(PushMessage.thumbnail(data))
        assertTrue(PushMessage.sizeOf(data) <= PushMessage.MAX_DATA_BYTES)
        // An id that isn't plain letters and digits is left out.
        assertNull(PushMessage.build(PushMessage.TYPE_ALERT, "t", "m", 1L, null, "../x")[PushMessage.KEY_PICTURE_ID])
        assertNull(PushMessage.build(PushMessage.TYPE_ALERT, "t", "m", 1L, null)[PushMessage.KEY_PICTURE_ID])
    }

    @Test
    fun aThumbnailInTheListIsReplacedByTheFullPictureOnceFetched() {
        val store = ReceivedAlertStore(File(tempFolder.root, "received"))
        val alert = store.add("alert", "Person at your door", "", 1L, byteArrayOf(1, 2), pictureId = "abcdefgh12345678")
        assertFalse(alert.isFullPicture)
        assertEquals("abcdefgh12345678", alert.pictureId)

        val full = ByteArray(5000) { 9 }
        val updated = store.setFullPicture(alert.id, full)!!

        assertTrue(updated.isFullPicture)
        assertArrayEquals(full, store.pictureOf(updated)!!.readBytes())
        assertTrue(ReceivedAlertStore(File(tempFolder.root, "received")).current().single().isFullPicture)
        assertNull(store.setFullPicture("no-such-alert", full))
        assertNull(store.setFullPicture(alert.id, ByteArray(0)))
    }

    @Test
    fun anAlertStoredWithTheFullPictureIsMarkedSo() {
        val store = ReceivedAlertStore(File(tempFolder.root, "received2"))
        assertTrue(store.add("alert", "t", "", 1L, byteArrayOf(1), "abcdefgh12345678", isFullPicture = true).isFullPicture)
        // "Full" with no picture at all makes no sense and is not recorded as such.
        assertFalse(store.add("alert", "t", "", 2L, null, "abcdefgh12345678", isFullPicture = true).isFullPicture)
    }
}
