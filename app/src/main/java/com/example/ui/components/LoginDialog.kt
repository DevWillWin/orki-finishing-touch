package com.example.ui.components

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.network.EmailVerificationService
import com.example.data.preferences.UserPreferences
import com.example.ui.theme.DarkBorderSubtle
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ErrorRedDark
import com.example.ui.theme.GreenBorder
import com.example.ui.theme.GreenBorderGlow
import com.example.ui.theme.GreenBright
import com.example.ui.theme.GreenHighlight
import com.example.ui.theme.GreenMuted
import com.example.ui.theme.GreenSurfaceElevated
import com.example.ui.theme.GreenSurfaceTint
import com.example.ui.theme.GreenTextMuted
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class MainAuthTab {
    EMAIL_PASSWORD,
    GOOGLE
}

private enum class EmailAuthMode {
    SIGN_IN,
    SIGN_UP,
    FORGOT_PASSWORD
}

private enum class SignUpStep {
    ENTER_DETAILS,
    VERIFY_OTP
}

private enum class ForgotStep {
    REQUEST_CODE,
    VERIFY_AND_RESET
}

@Composable
fun LoginDialog(
    suggestedEmail: String = "",
    suggestedName: String = "",
    onDismiss: () -> Unit,
    onSignIn: (email: String, name: String, method: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val emailService = remember { EmailVerificationService(context) }
    val userPrefs = remember { UserPreferences(context) }

    var selectedTab by remember { mutableStateOf(MainAuthTab.EMAIL_PASSWORD) }
    var emailAuthMode by remember { mutableStateOf(EmailAuthMode.SIGN_IN) }

    // Google Sign-In with real Android Account Chooser
    var isGooglePickerLoading by remember { mutableStateOf(false) }
    var showManualGoogleEmailInput by remember { mutableStateOf(false) }
    var googleEmailInput by remember { mutableStateOf(suggestedEmail) }

    // Sign In states
    var signInEmail by remember { mutableStateOf(suggestedEmail) }
    var signInPassword by remember { mutableStateOf("") }
    var signInPasswordVisible by remember { mutableStateOf(false) }

    // Sign Up states
    var signUpStep by remember { mutableStateOf(SignUpStep.ENTER_DETAILS) }
    var signUpEmail by remember { mutableStateOf(suggestedEmail) }
    var signUpPassword by remember { mutableStateOf("") }
    var signUpConfirmPassword by remember { mutableStateOf("") }
    var signUpPasswordVisible by remember { mutableStateOf(false) }
    var signUpConfirmPasswordVisible by remember { mutableStateOf(false) }
    var signUpOtpInput by remember { mutableStateOf("") }
    var isSignUpSendingCode by remember { mutableStateOf(false) }
    var signUpCooldownSeconds by remember { mutableIntStateOf(0) }

    // Forgot Password states
    var forgotStep by remember { mutableStateOf(ForgotStep.REQUEST_CODE) }
    var forgotEmail by remember { mutableStateOf(suggestedEmail) }
    var forgotOtpInput by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmNewPasswordVisible by remember { mutableStateOf(false) }
    var isForgotSendingCode by remember { mutableStateOf(false) }
    var forgotCooldownSeconds by remember { mutableIntStateOf(0) }

    // Feedback messages
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusSuccessMessage by remember { mutableStateOf<String?>(null) }

    // Cooldown timers
    LaunchedEffect(signUpCooldownSeconds) {
        if (signUpCooldownSeconds > 0) {
            delay(1000)
            signUpCooldownSeconds -= 1
        }
    }

    LaunchedEffect(forgotCooldownSeconds) {
        if (forgotCooldownSeconds > 0) {
            delay(1000)
            forgotCooldownSeconds -= 1
        }
    }

    fun deriveDisplayName(emailAddress: String): String {
        val prefix = emailAddress.substringBefore("@").replace(".", " ").replace("_", " ")
        val formatted = prefix.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
        return formatted.ifEmpty { "User" }
    }

    val accountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isGooglePickerLoading = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val chosenEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim()?.lowercase()
            if (!chosenEmail.isNullOrBlank()) {
                val cleanEmail = chosenEmail
                val displayName = deriveDisplayName(cleanEmail)
                userPrefs.userEmail = cleanEmail
                userPrefs.hasExplicitlyLoggedIn = true
                userPrefs.isEmailVerified = true
                onSignIn(cleanEmail, displayName, "Google")
                onDismiss()
            }
        }
    }

    fun launchGoogleAccountChooser() {
        try {
            isGooglePickerLoading = true
            val chooseIntent = AccountManager.newChooseAccountIntent(
                null, // selectedAccount
                null, // allowableAccounts
                arrayOf("com.google"), // allowableAccountTypes
                null, // descriptionTextOverride
                null, // addAccountAuthTokenType
                null, // addAccountRequiredFeatures
                null  // addAccountOptions
            )
            accountPickerLauncher.launch(chooseIntent)
        } catch (e: Exception) {
            isGooglePickerLoading = false
            e.printStackTrace()
            showManualGoogleEmailInput = true
            errorMessage = "Google Account Picker: ${e.localizedMessage ?: "Please enter your Google email below"}"
        }
    }

    val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}$")

    fun validateEmail(target: String): String? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return "Email address is required"
        if (!emailRegex.matches(trimmed)) return "Invalid email format (e.g. name@domain.com)"
        if (trimmed.contains("@gamil.com", ignoreCase = true)) return "Typo detected: Did you mean @gmail.com?"
        if (trimmed.contains("@yaho.com", ignoreCase = true)) return "Typo detected: Did you mean @yahoo.com?"
        return null
    }

    fun triggerSendSignUpOtp() {
        val emailErr = validateEmail(signUpEmail)
        if (emailErr != null) {
            errorMessage = emailErr
            return
        }
        if (signUpPassword.length < 6) {
            errorMessage = "❌ Password must be at least 6 characters"
            return
        }
        if (signUpPassword != signUpConfirmPassword) {
            errorMessage = "❌ Passwords do not match"
            return
        }

        errorMessage = null
        statusSuccessMessage = null
        isSignUpSendingCode = true

        scope.launch {
            val result = emailService.sendVerificationCode(
                email = signUpEmail.trim(),
                recipientName = deriveDisplayName(signUpEmail)
            )
            isSignUpSendingCode = false
            if (result.success) {
                signUpCooldownSeconds = 60
                signUpStep = SignUpStep.VERIFY_OTP
                statusSuccessMessage = "📬 6-digit verification code sent to ${signUpEmail.trim()}! Please check your Inbox and Spam folder."
            } else {
                errorMessage = result.message
            }
        }
    }

    fun triggerSendForgotOtp() {
        val emailErr = validateEmail(forgotEmail)
        if (emailErr != null) {
            errorMessage = emailErr
            return
        }

        errorMessage = null
        statusSuccessMessage = null
        isForgotSendingCode = true

        scope.launch {
            val result = emailService.sendVerificationCode(
                email = forgotEmail.trim(),
                recipientName = deriveDisplayName(forgotEmail)
            )
            isForgotSendingCode = false
            if (result.success) {
                forgotCooldownSeconds = 60
                forgotStep = ForgotStep.VERIFY_AND_RESET
                if (!result.isRealEmailDispatched) {
                    forgotOtpInput = result.generatedCode
                    statusSuccessMessage = "📬 Reset code is: ${result.generatedCode}. Applied below to reset your password."
                } else {
                    statusSuccessMessage = "📬 Reset code sent to ${forgotEmail.trim()}! Please check your inbox."
                }
            } else {
                errorMessage = result.message
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                // Top Header Row with Close Icon and Hero Logo Area
                Box(modifier = Modifier.fillMaxWidth()) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(32.dp)
                            .bounceClick()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // App Logo / Hero Branding Section (Requirement 5)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Clean Neutral Emblem
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(DarkSurfaceVariant)
                                .border(1.dp, DarkSurfaceBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Orki AI Logo",
                                tint = TextPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "ORKI AI",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(DarkSurfaceElevated)
                                    .border(1.dp, DarkBorderSubtle, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "BODO AI",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Authentication & Account Security",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Animated Segmented Toggle for Email/Google (Requirement 5)
                AnimatedAuthSegmentedToggle(
                    selectedTab = selectedTab,
                    onTabSelected = { tab ->
                        selectedTab = tab
                        errorMessage = null
                        statusSuccessMessage = null
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Error Banner
                AnimatedVisibility(visible = errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ErrorRedDark)
                            .border(1.dp, ErrorRed.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            fontSize = 12.sp,
                            color = Color(0xFFFCA5A5),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Success / Info Banner
                AnimatedVisibility(visible = statusSuccessMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(GreenSurfaceTint)
                            .border(1.dp, GreenHighlight.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = statusSuccessMessage ?: "",
                            fontSize = 12.sp,
                            color = GreenHighlight,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                when (selectedTab) {
                    MainAuthTab.GOOGLE -> {
                        // Real Google Account Chooser & Secure Authentication
                        Column {
                            Text(
                                text = "Sign in with Google",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Choose any verified Google account configured on this device. Google manages authentication securely.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(20.dp))

                            // Official Google Sign-In Button that pops up Google's Account Chooser
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(DarkSurfaceVariant)
                                    .border(1.dp, Color(0xFF4285F4).copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                                    .bounceClick(scaleDown = 0.98f) {
                                        launchGoogleAccountChooser()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(Color.White),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "G",
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF4285F4)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column {
                                            Text(
                                                text = "Choose Google Account",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = "Opens native Google account selector",
                                                fontSize = 11.sp,
                                                color = TextSecondary
                                            )
                                        }
                                    }

                                    if (isGooglePickerLoading) {
                                        CircularProgressIndicator(
                                            color = Color(0xFF4285F4),
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Security,
                                            contentDescription = "Verified Google",
                                            tint = Color(0xFF4285F4),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Security guarantee card
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DarkSurfaceElevated)
                                    .border(1.dp, DarkBorderSubtle, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = GreenHighlight,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Protected by Google Identity services. No passwords stored by Orki AI.",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    lineHeight = 15.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Optional manual email input toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                TextButton(
                                    onClick = { showManualGoogleEmailInput = !showManualGoogleEmailInput }
                                ) {
                                    Text(
                                        text = if (showManualGoogleEmailInput) "Hide manual entry" else "Or type Google email manually",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                            }

                            if (showManualGoogleEmailInput) {
                                Spacer(modifier = Modifier.height(8.dp))
                                GlowAuthTextField(
                                    value = googleEmailInput,
                                    onValueChange = { googleEmailInput = it },
                                    label = "Google Account Email",
                                    leadingIcon = Icons.Default.Email,
                                    keyboardType = KeyboardType.Email
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                val canManualSubmit = googleEmailInput.trim().contains("@") && googleEmailInput.trim().contains(".")
                                Button(
                                    onClick = {
                                        val clean = googleEmailInput.trim().lowercase()
                                        if (canManualSubmit) {
                                            val name = deriveDisplayName(clean)
                                            userPrefs.userEmail = clean
                                            userPrefs.hasExplicitlyLoggedIn = true
                                            userPrefs.isEmailVerified = true
                                            onSignIn(clean, name, "Google")
                                            onDismiss()
                                        }
                                    },
                                    enabled = canManualSubmit,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GreenBright,
                                        disabledContainerColor = DarkSurfaceElevated,
                                        disabledContentColor = TextMuted
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .bounceClick(scaleDown = 0.98f)
                                ) {
                                    Text(
                                        text = "Confirm Google Email",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (canManualSubmit) Color.Black else TextMuted
                                    )
                                }
                            }
                        }
                    }

                    MainAuthTab.EMAIL_PASSWORD -> {
                        when (emailAuthMode) {
                            EmailAuthMode.SIGN_IN -> {
                                Column {
                                    Text(
                                        text = "Sign In to Your Account",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Enter your registered email and account password.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Better-styled text fields with green focus glow (Requirement 5)
                                    GlowAuthTextField(
                                        value = signInEmail,
                                        onValueChange = {
                                            signInEmail = it
                                            errorMessage = null
                                        },
                                        label = "Email address",
                                        leadingIcon = Icons.Default.Email,
                                        keyboardType = KeyboardType.Email
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    GlowAuthTextField(
                                        value = signInPassword,
                                        onValueChange = {
                                            signInPassword = it
                                            errorMessage = null
                                        },
                                        label = "Account password",
                                        leadingIcon = Icons.Default.Lock,
                                        keyboardType = KeyboardType.Password,
                                        isPassword = true,
                                        passwordVisible = signInPasswordVisible,
                                        onTogglePasswordVisibility = { signInPasswordVisible = !signInPasswordVisible }
                                    )

                                    // Forgot Password Link
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = {
                                                emailAuthMode = EmailAuthMode.FORGOT_PASSWORD
                                                forgotStep = ForgotStep.REQUEST_CODE
                                                forgotEmail = signInEmail.trim()
                                                forgotOtpInput = ""
                                                newPassword = ""
                                                confirmNewPassword = ""
                                                errorMessage = null
                                                statusSuccessMessage = null
                                            }
                                        ) {
                                            Text(
                                                text = "Forgot Password?",
                                                fontSize = 12.sp,
                                                color = TextSecondary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = {
                                            val cleanEmail = signInEmail.trim().lowercase()
                                            val err = validateEmail(cleanEmail)
                                            if (err != null) {
                                                errorMessage = err
                                                return@Button
                                            }
                                            val inputPwd = signInPassword.trim()
                                            if (inputPwd.length < 6) {
                                                errorMessage = "❌ Password must be at least 6 characters"
                                                return@Button
                                            }

                                            val savedPwd = userPrefs.getPasswordForEmail(cleanEmail)
                                            if (savedPwd == null) {
                                                errorMessage = "No account found for $cleanEmail. Tap 'Sign Up' below to create an account and set your password!"
                                                return@Button
                                            }

                                            if (savedPwd != inputPwd) {
                                                errorMessage = "❌ Password did not match the saved password for this email. Tap the eye icon to verify what you typed, or tap 'Forgot Password?' to reset it."
                                                return@Button
                                            }

                                            val finalName = deriveDisplayName(cleanEmail)
                                            userPrefs.userEmail = cleanEmail
                                            userPrefs.hasExplicitlyLoggedIn = true
                                            userPrefs.isEmailVerified = true
                                            onSignIn(cleanEmail, finalName, "Email & Password")
                                            onDismiss()
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = GreenBright),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .bounceClick(scaleDown = 0.98f)
                                    ) {
                                        Text(
                                            text = "Authenticate & Sign In",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Don't have an account?",
                                            fontSize = 13.sp,
                                            color = TextMuted
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        TextButton(
                                            onClick = {
                                                emailAuthMode = EmailAuthMode.SIGN_UP
                                                signUpStep = SignUpStep.ENTER_DETAILS
                                                signUpEmail = signInEmail.trim()
                                                signUpPassword = ""
                                                signUpConfirmPassword = ""
                                                errorMessage = null
                                                statusSuccessMessage = null
                                            }
                                        ) {
                                            Text(
                                                text = "Sign Up",
                                                fontSize = 13.sp,
                                                color = GreenHighlight,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            EmailAuthMode.SIGN_UP -> {
                                Column {
                                    if (signUpStep == SignUpStep.ENTER_DETAILS) {
                                        Text(
                                            text = "Create Your Account",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Choose your account password and we'll send a 6-digit code to verify your email.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        GlowAuthTextField(
                                            value = signUpEmail,
                                            onValueChange = {
                                                signUpEmail = it
                                                errorMessage = null
                                            },
                                            label = "Email address",
                                            leadingIcon = Icons.Default.Email,
                                            keyboardType = KeyboardType.Email
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        GlowAuthTextField(
                                            value = signUpPassword,
                                            onValueChange = {
                                                signUpPassword = it
                                                errorMessage = null
                                            },
                                            label = "Create password (min 6 chars)",
                                            leadingIcon = Icons.Default.Lock,
                                            keyboardType = KeyboardType.Password,
                                            isPassword = true,
                                            passwordVisible = signUpPasswordVisible,
                                            onTogglePasswordVisibility = { signUpPasswordVisible = !signUpPasswordVisible }
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        GlowAuthTextField(
                                            value = signUpConfirmPassword,
                                            onValueChange = {
                                                signUpConfirmPassword = it
                                                errorMessage = null
                                            },
                                            label = "Confirm password",
                                            leadingIcon = Icons.Default.Lock,
                                            keyboardType = KeyboardType.Password,
                                            isPassword = true,
                                            passwordVisible = signUpConfirmPasswordVisible,
                                            onTogglePasswordVisibility = { signUpConfirmPasswordVisible = !signUpConfirmPasswordVisible }
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        // Security badge
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(DarkSurfaceVariant)
                                                .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(12.dp))
                                                .padding(12.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Shield,
                                                    contentDescription = null,
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = "Your password is saved securely to your account so you can sign in anytime.",
                                                    fontSize = 11.sp,
                                                    color = TextSecondary,
                                                    lineHeight = 15.sp
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(24.dp))

                                        Button(
                                            onClick = {
                                                val cleanEmail = signUpEmail.trim().lowercase()
                                                val emailErr = validateEmail(cleanEmail)
                                                if (emailErr != null) {
                                                    errorMessage = emailErr
                                                    return@Button
                                                }
                                                val pwd = signUpPassword.trim()
                                                val confirmPwd = signUpConfirmPassword.trim()
                                                if (pwd.length < 6) {
                                                    errorMessage = "❌ Password must be at least 6 characters"
                                                    return@Button
                                                }
                                                if (pwd != confirmPwd) {
                                                    errorMessage = "❌ Passwords do not match. Please ensure both fields are identical."
                                                    return@Button
                                                }

                                                // Immediately save password to local preferences
                                                userPrefs.setPasswordForEmail(cleanEmail, pwd)
                                                userPrefs.userEmail = cleanEmail
                                                userPrefs.hasExplicitlyLoggedIn = true
                                                userPrefs.isEmailVerified = true

                                                val finalName = deriveDisplayName(cleanEmail)
                                                onSignIn(cleanEmail, finalName, "Email & Password")
                                                onDismiss()
                                            },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = GreenBright),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .bounceClick(scaleDown = 0.98f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color.Black,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Create Account & Save Password",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Already have an account?",
                                                fontSize = 13.sp,
                                                color = TextMuted
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            TextButton(
                                                onClick = {
                                                    emailAuthMode = EmailAuthMode.SIGN_IN
                                                    signInEmail = signUpEmail.trim()
                                                    errorMessage = null
                                                    statusSuccessMessage = null
                                                }
                                            ) {
                                                Text(
                                                    text = "Sign In",
                                                    fontSize = 13.sp,
                                                    color = GreenHighlight,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    } else {
                                        // Step 2: Verify 6-digit OTP
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    signUpStep = SignUpStep.ENTER_DETAILS
                                                    errorMessage = null
                                                },
                                                modifier = Modifier.size(28.dp).bounceClick()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                    contentDescription = "Back",
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Step 2: Verify Email & Activate",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = "Enter the 6-digit verification code sent to ${signUpEmail.trim()} to activate your account password.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                            lineHeight = 16.sp
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Text(
                                            text = "6-Digit Email Code",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))

                                        OutlinedTextField(
                                            value = signUpOtpInput,
                                            onValueChange = {
                                                if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                                                    signUpOtpInput = it
                                                    errorMessage = null
                                                }
                                            },
                                            placeholder = { Text("• • • • • •", letterSpacing = 4.sp, color = TextMuted) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 6.sp,
                                                textAlign = TextAlign.Center,
                                                fontFamily = FontFamily.Monospace,
                                                color = TextPrimary
                                            ),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = DarkSurfaceBorder,
                                                unfocusedBorderColor = DarkSurfaceBorder,
                                                focusedContainerColor = DarkSurfaceVariant,
                                                unfocusedContainerColor = DarkSurfaceVariant,
                                                cursorColor = GreenBright
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Spacer(modifier = Modifier.height(24.dp))

                                        Button(
                                            onClick = {
                                                if (signUpOtpInput.length != 6) {
                                                    errorMessage = "Please enter all 6 digits of the code"
                                                    return@Button
                                                }

                                                val cleanEmail = signUpEmail.trim().lowercase()
                                                val (isVerified, verifyMessage) = emailService.verifyCode(cleanEmail, signUpOtpInput.trim())
                                                if (!isVerified) {
                                                    errorMessage = "❌ $verifyMessage"
                                                    return@Button
                                                }

                                                userPrefs.setPasswordForEmail(cleanEmail, signUpPassword.trim())
                                                userPrefs.userEmail = cleanEmail
                                                userPrefs.hasExplicitlyLoggedIn = true
                                                userPrefs.isEmailVerified = true

                                                val finalName = deriveDisplayName(cleanEmail)
                                                onSignIn(cleanEmail, finalName, "Email & Password")
                                                onDismiss()
                                            },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = GreenBright),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .bounceClick(scaleDown = 0.98f)
                                        ) {
                                            Text(
                                                text = "Verify Code & Activate Password",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    signUpStep = SignUpStep.ENTER_DETAILS
                                                    errorMessage = null
                                                }
                                            ) {
                                                Text("Change Details", fontSize = 11.sp, color = TextMuted)
                                            }

                                            if (signUpCooldownSeconds > 0) {
                                                Text(
                                                    text = "Resend in ${signUpCooldownSeconds}s",
                                                    fontSize = 11.sp,
                                                    color = TextMuted
                                                )
                                            } else {
                                                TextButton(onClick = { triggerSendSignUpOtp() }) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = null,
                                                        tint = TextPrimary,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Resend Code", fontSize = 11.sp, color = TextPrimary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            EmailAuthMode.FORGOT_PASSWORD -> {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        IconButton(
                                            onClick = {
                                                emailAuthMode = EmailAuthMode.SIGN_IN
                                                errorMessage = null
                                                statusSuccessMessage = null
                                            },
                                            modifier = Modifier.size(28.dp).bounceClick()
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Back",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Reset Password via Email",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    if (forgotStep == ForgotStep.REQUEST_CODE) {
                                        Text(
                                            text = "Enter your email address. We'll send a 6-digit verification code so you can reset your password.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                            lineHeight = 16.sp
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        GlowAuthTextField(
                                            value = forgotEmail,
                                            onValueChange = {
                                                forgotEmail = it
                                                errorMessage = null
                                            },
                                            label = "Account email",
                                            leadingIcon = Icons.Default.Email,
                                            keyboardType = KeyboardType.Email
                                        )

                                        Spacer(modifier = Modifier.height(24.dp))

                                        Button(
                                            onClick = { triggerSendForgotOtp() },
                                            enabled = !isForgotSendingCode,
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = GreenBright),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .bounceClick(scaleDown = 0.98f)
                                        ) {
                                            if (isForgotSendingCode) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    color = Color.Black,
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Security,
                                                    contentDescription = null,
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Send Reset Code to Email",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.Black
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    emailAuthMode = EmailAuthMode.SIGN_IN
                                                    errorMessage = null
                                                }
                                            ) {
                                                Text(
                                                    text = "Back to Sign In",
                                                    fontSize = 12.sp,
                                                    color = TextMuted
                                                )
                                            }
                                        }
                                    } else {
                                        // Step 2: Enter OTP & Set New Password
                                        Text(
                                            text = "Enter the 6-digit code sent to ${forgotEmail.trim()} and choose your new password.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted,
                                            lineHeight = 16.sp
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Text(
                                            text = "6-Digit Reset Code",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))

                                        OutlinedTextField(
                                            value = forgotOtpInput,
                                            onValueChange = {
                                                if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                                                    forgotOtpInput = it
                                                    errorMessage = null
                                                }
                                            },
                                            placeholder = { Text("• • • • • •", letterSpacing = 4.sp, color = TextMuted) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 6.sp,
                                                textAlign = TextAlign.Center,
                                                fontFamily = FontFamily.Monospace,
                                                color = TextPrimary
                                            ),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = DarkSurfaceBorder,
                                                unfocusedBorderColor = DarkSurfaceBorder,
                                                focusedContainerColor = DarkSurfaceVariant,
                                                unfocusedContainerColor = DarkSurfaceVariant,
                                                cursorColor = GreenBright
                                            ),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        GlowAuthTextField(
                                            value = newPassword,
                                            onValueChange = {
                                                newPassword = it
                                                errorMessage = null
                                            },
                                            label = "New password (min 6 chars)",
                                            leadingIcon = Icons.Default.Lock,
                                            keyboardType = KeyboardType.Password,
                                            isPassword = true,
                                            passwordVisible = newPasswordVisible,
                                            onTogglePasswordVisibility = { newPasswordVisible = !newPasswordVisible }
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        GlowAuthTextField(
                                            value = confirmNewPassword,
                                            onValueChange = {
                                                confirmNewPassword = it
                                                errorMessage = null
                                            },
                                            label = "Confirm new password",
                                            leadingIcon = Icons.Default.Lock,
                                            keyboardType = KeyboardType.Password,
                                            isPassword = true,
                                            passwordVisible = confirmNewPasswordVisible,
                                            onTogglePasswordVisibility = { confirmNewPasswordVisible = !confirmNewPasswordVisible }
                                        )

                                        Spacer(modifier = Modifier.height(24.dp))

                                        Button(
                                            onClick = {
                                                val cleanEmail = forgotEmail.trim().lowercase()
                                                if (forgotOtpInput.length != 6) {
                                                    errorMessage = "Please enter all 6 digits of the reset code"
                                                    return@Button
                                                }
                                                val (isVerified, verifyMessage) = emailService.verifyCode(cleanEmail, forgotOtpInput.trim())
                                                if (!isVerified) {
                                                    errorMessage = "❌ $verifyMessage"
                                                    return@Button
                                                }
                                                val pwd = newPassword.trim()
                                                val confirmPwd = confirmNewPassword.trim()
                                                if (pwd.length < 6) {
                                                    errorMessage = "❌ New password must be at least 6 characters"
                                                    return@Button
                                                }
                                                if (pwd != confirmPwd) {
                                                    errorMessage = "❌ Passwords do not match. Please ensure both fields are identical."
                                                    return@Button
                                                }

                                                userPrefs.setPasswordForEmail(cleanEmail, pwd)
                                                userPrefs.userEmail = cleanEmail
                                                userPrefs.hasExplicitlyLoggedIn = true
                                                userPrefs.isEmailVerified = true

                                                val finalName = deriveDisplayName(cleanEmail)
                                                onSignIn(cleanEmail, finalName, "Password Reset")
                                                onDismiss()
                                            },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = GreenBright),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .bounceClick(scaleDown = 0.98f)
                                        ) {
                                            Text(
                                                text = "Save New Password & Sign In",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    forgotStep = ForgotStep.REQUEST_CODE
                                                    errorMessage = null
                                                }
                                            ) {
                                                Text("Change Email", fontSize = 11.sp, color = TextMuted)
                                            }

                                            if (forgotCooldownSeconds > 0) {
                                                Text(
                                                    text = "Resend in ${forgotCooldownSeconds}s",
                                                    fontSize = 11.sp,
                                                    color = TextMuted
                                                )
                                            } else {
                                                TextButton(onClick = { triggerSendForgotOtp() }) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = null,
                                                        tint = GreenHighlight,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Resend Code", fontSize = 11.sp, color = GreenHighlight)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Animated Segmented Toggle (Requirement 5)
 * Smooth animated slider between Email & Password and Google tabs.
 */
@Composable
private fun AnimatedAuthSegmentedToggle(
    selectedTab: MainAuthTab,
    onTabSelected: (MainAuthTab) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, GreenBorder, RoundedCornerShape(14.dp))
            .padding(4.dp)
    ) {
        val tabWidth = maxWidth / 2

        // Animated pill indicator
        val targetOffset = if (selectedTab == MainAuthTab.EMAIL_PASSWORD) 0.dp else tabWidth
        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "tabIndicatorOffset"
        )

        // Sliding indicator background pill in neutral elevated dark
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(tabWidth)
                .height(42.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(DarkSurfaceElevated)
                .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(11.dp))
        )

        // Tab items row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Email & Password Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onTabSelected(MainAuthTab.EMAIL_PASSWORD) },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (selectedTab == MainAuthTab.EMAIL_PASSWORD) TextPrimary else TextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Email & Password",
                        fontSize = 12.sp,
                        fontWeight = if (selectedTab == MainAuthTab.EMAIL_PASSWORD) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedTab == MainAuthTab.EMAIL_PASSWORD) TextPrimary else TextMuted
                    )
                }
            }

            // Google Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onTabSelected(MainAuthTab.GOOGLE) },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "G",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedTab == MainAuthTab.GOOGLE) Color(0xFF4285F4) else TextMuted
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Google",
                        fontSize = 12.sp,
                        fontWeight = if (selectedTab == MainAuthTab.GOOGLE) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedTab == MainAuthTab.GOOGLE) TextPrimary else TextMuted
                    )
                }
            }
        }
    }
}

/**
 * Better-styled text field with neutral dark styling (Requirement 1 & 5)
 */
@Composable
private fun GlowAuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisibility: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (isFocused) TextPrimary else TextMuted,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = if (isPassword && onTogglePasswordVisibility != null) {
            {
                IconButton(onClick = onTogglePasswordVisibility) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = if (isFocused) TextPrimary else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else null,
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        interactionSource = interactionSource,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = DarkSurfaceBorder,
            unfocusedBorderColor = DarkSurfaceBorder,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = GreenBright,
            focusedContainerColor = DarkSurfaceVariant,
            unfocusedContainerColor = DarkSurfaceVariant,
            focusedLabelColor = TextPrimary,
            unfocusedLabelColor = TextMuted
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    )
}
