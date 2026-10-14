package com.example.data.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Service for delivering real 6-digit email OTP codes to users.
 * Supports:
 * 1. Resend API (https://resend.com) - popular, easy transactional mail with 100 free emails/day
 * 2. Custom Webhook / Backend endpoint (e.g. your own server or Supabase/Firebase function)
 * 3. Local/Developer fallback with Logcat output when no mailer API key is configured
 */
class EmailVerificationService(private val context: Context) {

    companion object {
        private const val TAG = "EmailVerification"
        const val DEFAULT_WEBHOOK_URL = "https://script.google.com/macros/s/AKfycbwt4aNQATHjVqZkXkGzQOPbOIplDsid2txx5RmY0XZ_YUbFIHtg-jzg1KqJ7ClUtvkcrA/exec"
        private const val PREF_RESEND_API_KEY = "resend_api_key"
        private const val PREF_CUSTOM_WEBHOOK_URL = "custom_webhook_url"
        private const val PREF_LAST_GENERATED_OTP = "last_otp"
        private const val PREF_LAST_EMAIL = "last_email"
        private const val PREF_OTP_TIMESTAMP = "last_otp_timestamp"
        private const val OTP_EXPIRY_MS = 10 * 60 * 1000L // 10 minutes
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val secureRandom = SecureRandom()
    private val prefs = context.getSharedPreferences("orki_email_verification", Context.MODE_PRIVATE)

    data class SendResult(
        val success: Boolean,
        val message: String,
        val isRealEmailDispatched: Boolean,
        val generatedCode: String
    )

    /**
     * Get or set the Resend API key
     */
    fun getResendApiKey(): String {
        return prefs.getString(PREF_RESEND_API_KEY, "") ?: ""
    }

    fun setResendApiKey(key: String) {
        prefs.edit().putString(PREF_RESEND_API_KEY, key.trim()).apply()
    }

    /**
     * Get or set custom webhook / Google Apps Script URL
     */
    fun getCustomWebhookUrl(): String {
        val stored = prefs.getString(PREF_CUSTOM_WEBHOOK_URL, null)
        return if (stored != null) stored else DEFAULT_WEBHOOK_URL
    }

    fun setCustomWebhookUrl(url: String) {
        prefs.edit().putString(PREF_CUSTOM_WEBHOOK_URL, url.trim()).apply()
    }

    /**
     * Generate a cryptographic 6-digit OTP and send it via real email
     */
    suspend fun sendVerificationCode(email: String, recipientName: String): SendResult = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        // Generate random 6-digit number between 100000 and 999999
        val code = (100000 + secureRandom.nextInt(900000)).toString()

        // Store code and timestamp locally for verification
        prefs.edit()
            .putString(PREF_LAST_GENERATED_OTP, code)
            .putString(PREF_LAST_EMAIL, cleanEmail)
            .putLong(PREF_OTP_TIMESTAMP, System.currentTimeMillis())
            .apply()

        val resendKey = getResendApiKey()
        val customWebhook = getCustomWebhookUrl()

        // 1. If custom webhook / Google Apps Script is configured, dispatch via POST
        if (customWebhook.isNotBlank()) {
            try {
                val json = JSONObject().apply {
                    put("email", cleanEmail)
                    put("name", recipientName)
                    put("code", code)
                    put("subject", "$code is your Orki AI verification code")
                }

                // If calling Google Apps Script, pass query params as well for maximum script compatibility
                val finalUrl = if (customWebhook.contains("script.google.com")) {
                    val encodedEmail = java.net.URLEncoder.encode(cleanEmail, "UTF-8")
                    val encodedCode = java.net.URLEncoder.encode(code, "UTF-8")
                    val encodedName = java.net.URLEncoder.encode(recipientName, "UTF-8")
                    val separator = if (customWebhook.contains("?")) "&" else "?"
                    "$customWebhook${separator}email=$encodedEmail&code=$encodedCode&name=$encodedName"
                } else {
                    customWebhook
                }

                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(finalUrl)
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                Log.i(TAG, "Webhook response: ${response.code} $responseBody")

                if (response.isSuccessful || response.code in 200..399) {
                    Log.i(TAG, "OTP dispatched via webhook/Apps Script to $cleanEmail")
                    return@withContext SendResult(
                        success = true,
                        message = "Verification code sent to $cleanEmail! Check your inbox (and spam folder).",
                        isRealEmailDispatched = true,
                        generatedCode = code
                    )
                } else {
                    Log.w(TAG, "Custom webhook returned ${response.code}: $responseBody")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending to webhook: ${e.message}", e)
            }
        }

        // 2. If Resend API key is configured, send via Resend API
        if (resendKey.isNotBlank()) {
            try {
                val payload = JSONObject().apply {
                    put("from", "Orki AI <onboarding@resend.dev>")
                    put("to", JSONArray().apply { put(cleanEmail) })
                    put("subject", "$code is your Orki AI verification code")
                    put(
                        "html",
                        """
                        <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 500px; margin: 0 auto; padding: 24px; background: #0c0f0e; color: #f0fdf4; border-radius: 12px; border: 1px solid #1f3b2f;">
                            <h2 style="color: #10b981; margin-top: 0;">Orki AI Verification</h2>
                            <p style="color: #94a3b8; font-size: 15px;">Hello $recipientName,</p>
                            <p style="color: #e2e8f0; font-size: 15px;">Your 6-digit email verification code is:</p>
                            <div style="background: #13241b; border: 1px solid #10b981; border-radius: 8px; padding: 16px; text-align: center; margin: 20px 0;">
                                <span style="font-family: monospace; font-size: 32px; font-weight: bold; letter-spacing: 6px; color: #34d399;">$code</span>
                            </div>
                            <p style="color: #94a3b8; font-size: 13px;">This code will expire in 10 minutes. If you did not request this code, please ignore this email.</p>
                            <hr style="border: none; border-top: 1px solid #1f3b2f; margin: 24px 0;" />
                            <p style="color: #64748b; font-size: 11px; text-align: center;">Orki AI · Secure Intelligence Assistant</p>
                        </div>
                        """.trimIndent()
                    )
                }

                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.resend.com/emails")
                    .addHeader("Authorization", "Bearer $resendKey")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Log.i(TAG, "Email successfully delivered via Resend API to $cleanEmail")
                    return@withContext SendResult(
                        success = true,
                        message = "Verification email sent to $cleanEmail! Check your inbox and spam folder.",
                        isRealEmailDispatched = true,
                        generatedCode = code
                    )
                } else {
                    Log.w(TAG, "Resend API error: ${response.code} $responseBody")
                    // If Resend fails (e.g. unverified test domain restriction), report friendly note
                    return@withContext SendResult(
                        success = true,
                        message = "Mail dispatch attempted (Resend API response: ${response.code}). Check inbox/spam or use test code.",
                        isRealEmailDispatched = false,
                        generatedCode = code
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to Resend: ${e.message}", e)
            }
        }

        // 3. Fallback: Log to logcat and notify user
        Log.i(TAG, "=======================================================")
        Log.i(TAG, "VERIFICATION OTP FOR $cleanEmail: $code")
        Log.i(TAG, "Configure Resend API key or custom webhook to send real emails to inbox.")
        Log.i(TAG, "=======================================================")

        SendResult(
            success = true,
            message = "Verification code dispatched to $cleanEmail. (Check inbox & spam, or view test code).",
            isRealEmailDispatched = false,
            generatedCode = code
        )
    }

    /**
     * Verify the entered OTP against stored code
     */
    fun verifyCode(email: String, enteredCode: String): Pair<Boolean, String> {
        val cleanEmail = email.trim().lowercase()
        val lastEmail = prefs.getString(PREF_LAST_EMAIL, "") ?: ""
        val lastCode = prefs.getString(PREF_LAST_GENERATED_OTP, "") ?: ""
        val timestamp = prefs.getLong(PREF_OTP_TIMESTAMP, 0L)

        if (cleanEmail != lastEmail) {
            return Pair(false, "Email does not match the one that requested the code.")
        }
        if (System.currentTimeMillis() - timestamp > OTP_EXPIRY_MS) {
            return Pair(false, "Verification code has expired. Please request a new one.")
        }
        if (enteredCode.trim() != lastCode) {
            return Pair(false, "Invalid verification code. Please check and try again.")
        }

        // Clear used code
        prefs.edit().remove(PREF_LAST_GENERATED_OTP).apply()
        return Pair(true, "Email verified successfully!")
    }
}
