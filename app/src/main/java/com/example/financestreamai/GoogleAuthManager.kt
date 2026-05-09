package com.example.financestreamai

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

object GoogleAuthManager {
    private const val TAG = "GoogleAuth"
    private const val PREFS_NAME = "GoogleAuthPrefs"
    private const val KEY_SIGNED_IN = "is_signed_in"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_PHOTO = "user_photo"

    // Web Client ID (OAuth 2.0 client of type "Web application") from the same
    // Firebase / Google Cloud project as google-services.json.
    //
    // IMPORTANT: This MUST be the *Web* client ID (client_type: 3 in
    // google-services.json), NOT the Android client ID (client_type: 1).
    // Credential Manager / GetGoogleIdOption.setServerClientId() always
    // expects the Web client ID — the Android client is matched implicitly
    // via the package name + SHA-1 fingerprint registered in Firebase.
    //
    // If you ever see "Caller has been temporarily blocked" or
    // "16: developer console is not set up correctly" / "ApiException 10",
    // it's almost always because the wrong client ID is used here OR the
    // SHA-1 of the running APK is not registered in Firebase.
    const val WEB_CLIENT_ID = "221128399172-0opj7q562kgnebtmu0gmfqj610hokh9j.apps.googleusercontent.com"

    fun isWebClientIdConfigured(): Boolean =
        WEB_CLIENT_ID.isNotBlank() &&
            !WEB_CLIENT_ID.startsWith("YOUR_") &&
            WEB_CLIENT_ID.endsWith(".apps.googleusercontent.com")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSignedIn(context: Context): Boolean = prefs(context).getBoolean(KEY_SIGNED_IN, false)

    fun getUserId(context: Context): String? = prefs(context).getString(KEY_USER_ID, null)
    fun getUserName(context: Context): String? = prefs(context).getString(KEY_USER_NAME, null)
    fun getUserEmail(context: Context): String? = prefs(context).getString(KEY_USER_EMAIL, null)
    fun getUserPhoto(context: Context): String? = prefs(context).getString(KEY_USER_PHOTO, null)

    fun saveUser(context: Context, userId: String?, name: String?, email: String?, photoUrl: String?) {
        prefs(context).edit()
            .putBoolean(KEY_SIGNED_IN, true)
            .putString(KEY_USER_ID, userId ?: email)
            .putString(KEY_USER_NAME, name)
            .putString(KEY_USER_EMAIL, email)
            .putString(KEY_USER_PHOTO, photoUrl)
            .apply()
        // Also set the session for API requests
        UserSession.userId = userId ?: email
    }

    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
        UserSession.userId = null
    }

    /**
     * Enter the app without Google Sign-In. Generates (or reuses) a stable
     * per-device guest id so backend data (watchlist, portfolio) remains
     * consistent across launches. Used as a temporary bypass while sign-in
     * is being configured.
     */
    fun signInAsGuest(context: Context) {
        val p = prefs(context)
        val existingId = p.getString(KEY_USER_ID, null)
        val guestId = if (!existingId.isNullOrBlank() && existingId.startsWith("guest-")) {
            existingId
        } else {
            "guest-" + java.util.UUID.randomUUID().toString().take(12)
        }
        p.edit()
            .putBoolean(KEY_SIGNED_IN, true)
            .putString(KEY_USER_ID, guestId)
            .putString(KEY_USER_NAME, "Guest")
            .putString(KEY_USER_EMAIL, null)
            .putString(KEY_USER_PHOTO, null)
            .apply()
        UserSession.userId = guestId
    }

    fun isGuest(context: Context): Boolean =
        prefs(context).getString(KEY_USER_ID, null)?.startsWith("guest-") == true

    fun buildGoogleIdOption(filterAuthorizedAccounts: Boolean = false): GetGoogleIdOption {
        return GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterAuthorizedAccounts)
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(true)
            .build()
    }

    fun handleSignInResult(context: Context, result: GetCredentialResponse): Boolean {
        return when (val credential = result.credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        saveUser(
                            context,
                            userId = googleIdTokenCredential.idToken.hashCode().toString(), // Unique ID derived from token
                            name = googleIdTokenCredential.displayName,
                            email = googleIdTokenCredential.id,
                            photoUrl = googleIdTokenCredential.profilePictureUri?.toString()
                        )
                        Log.d(TAG, "Sign-in successful: ${googleIdTokenCredential.displayName}")
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse Google credential: ${e.message}")
                        false
                    }
                } else {
                    Log.e(TAG, "Unexpected credential type: ${credential.type}")
                    false
                }
            }
            else -> {
                Log.e(TAG, "Unexpected credential class: ${credential.javaClass}")
                false
            }
        }
    }
}
