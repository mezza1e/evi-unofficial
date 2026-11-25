package wtf.evi.mobile.ui.components

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wtf.evi.mobile.AppContainer
import wtf.evi.mobile.data.model.EVI_BASE_URL
import wtf.evi.mobile.ui.theme.LocalEviColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthSheet(
    container: AppContainer,
    onDismiss: () -> Unit,
    onSignedIn: () -> Unit
) {
    val colors = LocalEviColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ticketCode by remember { mutableStateOf<String?>(null) }
    var polling by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showWebView by remember { mutableStateOf(false) }
    var showTokenInput by remember { mutableStateOf(false) }
    var manualToken by remember { mutableStateOf("") }
    var pollJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { pollJob?.cancel() }
    }

    fun startTicketSignIn() {
        pollJob?.cancel()
        polling = true
        statusMessage = "Opening Discord login..."
        pollJob = scope.launch {
            val ticket = runCatching { container.apiClient.createTicket() }.getOrNull()
            if (ticket == null || ticket.id.isBlank()) {
                polling = false
                statusMessage = "Couldn't reach evi.wtf. Check your connection."
                return@launch
            }
            ticketCode = ticket.code
            statusMessage = "Confirm login in your browser..."
            val loginUrl = "$EVI_BASE_URL/api/auth/login?ticket=${ticket.id}"
            runCatching {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(context, Uri.parse(loginUrl))
            }.onFailure {
                showWebView = true
            }

            val deadline = System.currentTimeMillis() + 300_000L
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(1500L)
                val poll = runCatching { container.apiClient.pollTicket(ticket.id) }.getOrNull()
                if (poll?.ok == true) {
                    container.refreshSessionAndLibrary()
                    if (container.currentUser.value != null) {
                        polling = false
                        onSignedIn()
                        onDismiss()
                        return@launch
                    } else {
                        // Ticket approved on web; if session wasn't sent in Set-Cookie, let WebView grab cookie seamlessly
                        polling = false
                        statusMessage = "Approved! Finishing sign-in..."
                        showWebView = true
                        return@launch
                    }
                }
            }
            polling = false
            statusMessage = "Login timed out. Try again or use In-App Browser."
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            pollJob?.cancel()
            onDismiss()
        },
        containerColor = colors.surface,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EviSwirlLogo(modifier = Modifier.size(44.dp), tint = colors.accent)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Sign in to Evi",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Stream lossless audio, sync your library, likes, playlists, and daily mixes.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (showWebView) {
                EviWebLoginBox(
                    onSessionCaptured = { cookieHeader ->
                        container.cookieJar.importCookieHeader(cookieHeader)
                        scope.launch {
                            container.refreshSessionAndLibrary()
                            if (container.currentUser.value != null) {
                                onSignedIn()
                                onDismiss()
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = { showWebView = false }) {
                    Text("Back to Browser Sign-In", color = colors.textSecondary)
                }
            } else {
                if (ticketCode != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceElevated)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Pairing code", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = ticketCode.orEmpty(),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = colors.accent
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (!statusMessage.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        if (polling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.accent
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(statusMessage.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    }
                }

                Button(
                    onClick = { startTicketSignIn() },
                    enabled = !polling,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = if (polling) "Waiting for browser confirmation..." else "Continue with Discord",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = { showWebView = true },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("Sign in inside app", color = colors.textPrimary)
                }

                Spacer(modifier = Modifier.height(6.dp))

                TextButton(onClick = { showTokenInput = !showTokenInput }) {
                    Text(
                        text = if (showTokenInput) "Hide manual session input" else "Paste session cookie",
                        color = colors.textMuted
                    )
                }

                if (showTokenInput) {
                    OutlinedTextField(
                        value = manualToken,
                        onValueChange = { manualToken = it },
                        label = { Text("aural_session cookie value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            container.cookieJar.setSessionToken(manualToken)
                            scope.launch {
                                container.refreshSessionAndLibrary()
                                if (container.currentUser.value != null) {
                                    onSignedIn()
                                    onDismiss()
                                } else {
                                    statusMessage = "Invalid or expired session token"
                                }
                            }
                        },
                        enabled = manualToken.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Verify & Sign In")
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EviWebLoginBox(onSessionCaptured: (String) -> Unit) {
    var done by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .clip(RoundedCornerShape(14.dp)),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    private fun checkCookies() {
                        if (done) return
                        CookieManager.getInstance().flush()
                        val cookies = CookieManager.getInstance().getCookie(EVI_BASE_URL).orEmpty()
                        if (cookies.contains("aural_session=")) {
                            done = true
                            onSessionCaptured(cookies)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        checkCookies()
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        checkCookies()
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false
                    }
                }
                loadUrl("$EVI_BASE_URL/api/auth/login")
            }
        }
    )
}
