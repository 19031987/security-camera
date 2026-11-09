package com.securitycam.core.alerting

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Keeps a copy of each alert picture somewhere off the phone. */
interface CloudUploader {
    suspend fun upload(file: File, name: String): DispatchResult
}

/**
 * Uploads alert pictures to a "Security Camera" folder in the owner's own Google Drive.
 *
 * Uses the narrow "drive.file" permission: the app can only see files it created itself, not
 * the rest of the Drive. Files are private to the account; nothing is shared by link.
 */
class DriveUploader(
    /** A current Google access token, or null when the owner hasn't connected their account. */
    private val tokenProvider: () -> String?,
    private val folderIdStore: FolderIdStore,
    private val client: OkHttpClient = defaultClient(),
    private val apiBase: String = "https://www.googleapis.com",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gson: Gson = Gson()
) : CloudUploader {

    /** Remembers the Drive folder so it is only looked up once. */
    interface FolderIdStore {
        fun get(): String
        fun set(id: String)
    }

    private class FileRef(val id: String? = null)
    private class FileList(val files: List<FileRef>? = null)
    private class ApiError(val error: ApiErrorBody? = null)
    private class ApiErrorBody(val message: String? = null)

    private class DriveException(val code: Int, message: String) : Exception(message)

    override suspend fun upload(file: File, name: String): DispatchResult = withContext(ioDispatcher) {
        val token = tokenProvider()
            ?: return@withContext failure("Google Drive is not connected. Connect it in Settings.")
        if (!file.isFile || file.length() == 0L) {
            return@withContext failure("There is no picture to upload")
        }
        try {
            try {
                uploadInto(folderId(token), file, name, token)
            } catch (e: DriveException) {
                // The folder was deleted in Drive: forget it, make a new one and try once more.
                if (e.code != 404) throw e
                folderIdStore.set("")
                uploadInto(folderId(token), file, name, token)
            }
            DispatchResult(isSuccess = true, deliveredCount = 1, failedCount = 0)
        } catch (e: DriveException) {
            failure(
                when (e.code) {
                    401 -> "Google sign-in has expired. Connect Google Drive again in Settings."
                    else -> "Google Drive answered ${e.code}: ${e.message}"
                }
            )
        } catch (e: Exception) {
            failure("Could not reach Google Drive: ${e.message}")
        }
    }

    /** Finds or creates the folder. Used by Settings to confirm the connection works. */
    suspend fun checkConnection(): DispatchResult = withContext(ioDispatcher) {
        val token = tokenProvider() ?: return@withContext failure("Google Drive is not connected.")
        try {
            folderId(token)
            DispatchResult(isSuccess = true, deliveredCount = 0, failedCount = 0)
        } catch (e: DriveException) {
            failure("Google Drive answered ${e.code}: ${e.message}")
        } catch (e: Exception) {
            failure("Could not reach Google Drive: ${e.message}")
        }
    }

    private fun folderId(token: String): String {
        folderIdStore.get().takeIf { it.isNotEmpty() }?.let { return it }

        val query = "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
        val listUrl = "$apiBase/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("spaces", "drive")
            .build()
        val existing = execute(Request.Builder().url(listUrl).get(), token)
            .let { gson.fromJson(it, FileList::class.java) }?.files?.firstOrNull()?.id

        val id = existing ?: run {
            val body = gson.toJson(mapOf("name" to FOLDER_NAME, "mimeType" to FOLDER_MIME))
                .toRequestBody(JSON)
            val created = execute(
                Request.Builder().url("$apiBase/drive/v3/files?fields=id").post(body), token
            )
            gson.fromJson(created, FileRef::class.java)?.id
                ?: throw DriveException(500, "No folder id was returned")
        }
        folderIdStore.set(id)
        return id
    }

    private fun uploadInto(folderId: String, file: File, name: String, token: String) {
        val metadata = gson.toJson(mapOf("name" to name, "parents" to listOf(folderId)))
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody(JSON))
            .addPart(file.asRequestBody("image/jpeg".toMediaType()))
            .build()
        execute(
            Request.Builder().url("$apiBase/upload/drive/v3/files?uploadType=multipart&fields=id").post(body),
            token
        )
    }

    private fun execute(builder: Request.Builder, token: String): String {
        client.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = try {
                    gson.fromJson(text, ApiError::class.java)?.error?.message
                } catch (_: Exception) {
                    null
                }
                throw DriveException(response.code, message ?: "request failed")
            }
            return text
        }
    }

    private fun failure(message: String) =
        DispatchResult(isSuccess = false, deliveredCount = 0, failedCount = 1, errorMessage = message)

    companion object {
        const val FOLDER_NAME = "Security Camera"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val JSON = "application/json; charset=UTF-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
