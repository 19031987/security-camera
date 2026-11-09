package com.securitycam.core.alerting

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Google account permission for uploading to Drive, through Google Play services on the phone.
 * The owner grants it once in Settings; afterwards a fresh token can be fetched in the background.
 */
class GoogleDriveAuth(private val context: Context) {

    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveUploader.SCOPE_DRIVE_FILE)))
            .build()

    /**
     * A current access token, or null if the owner has not granted permission (or it needs to be
     * granted again on screen). Blocks briefly; call off the main thread.
     */
    fun accessTokenOrNull(): String? {
        return try {
            val result = Tasks.await(
                Identity.getAuthorizationClient(context).authorize(authorizationRequest()),
                TOKEN_TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            if (result.hasResolution()) null else result.accessToken
        } catch (e: Exception) {
            Log.w(TAG, "Could not get a Google access token: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "GoogleDriveAuth"
        const val TOKEN_TIMEOUT_SECONDS = 20L
    }
}
