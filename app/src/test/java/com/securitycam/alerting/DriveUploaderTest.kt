package com.securitycam.alerting

import com.securitycam.core.alerting.DriveUploader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DriveUploaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var image: File
    private var token: String? = "token-123"
    private var storedFolderId = ""

    private val store = object : DriveUploader.FolderIdStore {
        override fun get() = storedFolderId
        override fun set(id: String) { storedFolderId = id }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        image = tempFolder.newFile("shot.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun uploader() = DriveUploader(
        { token }, store, OkHttpClient(), server.url("/").toString().trimEnd('/'), testDispatcher
    )

    @Test
    fun firstUploadCreatesTheFolderThenUploadsIntoIt() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        server.enqueue(MockResponse().setBody("""{"id":"folder-1"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"file-1"}"""))

        val result = uploader().upload(image, "alert_2026-11-09_14-22-05_person.jpg")

        assertTrue(result.isSuccess)
        assertEquals("folder-1", storedFolderId)

        val search = server.takeRequest()
        assertEquals("GET", search.method)
        assertEquals("Bearer token-123", search.getHeader("Authorization"))
        assertTrue(search.requestUrl!!.queryParameter("q")!!.contains("name = 'Security Camera'"))
        assertTrue(search.requestUrl!!.queryParameter("q")!!.contains("trashed = false"))

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertTrue(create.body.readUtf8().contains("application/vnd.google-apps.folder"))

        val upload = server.takeRequest()
        assertEquals("POST", upload.method)
        assertTrue(upload.path!!.startsWith("/upload/drive/v3/files"))
        assertEquals("multipart", upload.requestUrl!!.queryParameter("uploadType"))
        assertTrue(upload.getHeader("Content-Type")!!.startsWith("multipart/related"))
        val body = upload.body.readByteArray()
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("alert_2026-11-09_14-22-05_person.jpg"))
        assertTrue(text.contains("\"parents\":[\"folder-1\"]"))
        assertTrue(text.contains("image/jpeg"))
        assertTrue(text.contains(String(byteArrayOf(1, 2, 3, 4, 5), Charsets.ISO_8859_1)))
    }

    @Test
    fun anExistingFolderIsReused() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"folder-9"}]}"""))
        server.enqueue(MockResponse().setBody("""{"id":"file-1"}"""))

        assertTrue(uploader().upload(image, "a.jpg").isSuccess)

        assertEquals("folder-9", storedFolderId)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRememberedFolderNeedsNoLookup() = runTest(testDispatcher) {
        storedFolderId = "folder-known"
        server.enqueue(MockResponse().setBody("""{"id":"file-1"}"""))

        assertTrue(uploader().upload(image, "a.jpg").isSuccess)

        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains("folder-known"))
    }

    @Test
    fun ifTheFolderWasDeletedANewOneIsMadeAndTheUploadRetried() = runTest(testDispatcher) {
        storedFolderId = "folder-gone"
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"File not found"}}"""))
        server.enqueue(MockResponse().setBody("""{"files":[]}"""))
        server.enqueue(MockResponse().setBody("""{"id":"folder-new"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"file-1"}"""))

        assertTrue(uploader().upload(image, "a.jpg").isSuccess)
        assertEquals("folder-new", storedFolderId)
    }

    @Test
    fun notConnectedIsReportedWithoutAnyRequest() = runTest(testDispatcher) {
        token = null

        val result = uploader().upload(image, "a.jpg")

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("not connected"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anExpiredSignInAsksToReconnect() = runTest(testDispatcher) {
        storedFolderId = "folder-1"
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid Credentials"}}"""))

        val result = uploader().upload(image, "a.jpg")

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Connect Google Drive again"))
    }

    @Test
    fun googlesOwnErrorMessageIsPassedOn() = runTest(testDispatcher) {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"message":"Google Drive API has not been used in project 123 before or it is disabled."}}""")
        )

        val result = uploader().checkConnection()

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("403"))
        assertTrue(result.errorMessage!!.contains("disabled"))
    }

    @Test
    fun aMissingOrEmptyPictureIsNotUploaded() = runTest(testDispatcher) {
        assertFalse(uploader().upload(File(tempFolder.root, "missing.jpg"), "a.jpg").isSuccess)
        assertFalse(uploader().upload(tempFolder.newFile("empty.jpg"), "a.jpg").isSuccess)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anUnreachableServiceIsAFailureNotACrash() = runTest(testDispatcher) {
        val u = uploader()
        server.shutdown()
        assertFalse(u.upload(image, "a.jpg").isSuccess)
        assertFalse(u.checkConnection().isSuccess)
    }

    @Test
    fun checkConnectionFindsOrCreatesTheFolder() = runTest(testDispatcher) {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"folder-3"}]}"""))
        assertTrue(uploader().checkConnection().isSuccess)
        assertEquals("folder-3", storedFolderId)
    }
}
