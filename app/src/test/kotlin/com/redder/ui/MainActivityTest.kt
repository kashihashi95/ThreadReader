package com.redder.ui

import android.content.Intent
import com.redder.settings.RedderPreferences
import com.redder.tts.EngineVoices
import com.redder.tts.VoiceEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MainActivityTest {
    private lateinit var fakeActionHandler: FakeMainDashboardActionHandler
    private lateinit var controller: MainDashboardController

    @BeforeEach
    fun setUp() {
        fakeActionHandler =
            FakeMainDashboardActionHandler(
                overlayPermissionGranted = false,
            )
        controller = MainDashboardController(actionHandler = fakeActionHandler)
    }

    // =========================================================================
    // 1. Initial Dashboard State
    // =========================================================================

    @Test
    fun `initial dashboard state when overlay permission is denied`() {
        val state = controller.state.value

        assertEquals("", state.inputUrl)
        assertFalse(state.hasOverlayPermission)
        assertNull(state.errorMessageRes)
        assertFalse(state.playbackStarted)
    }

    @Test
    fun `initial dashboard state when overlay permission is granted`() {
        fakeActionHandler.overlayPermissionGranted = true
        val grantedController = MainDashboardController(actionHandler = fakeActionHandler)
        val state = grantedController.state.value

        assertEquals("", state.inputUrl)
        assertTrue(state.hasOverlayPermission)
        assertNull(state.errorMessageRes)
        assertFalse(state.playbackStarted)
    }

    @Test
    fun `initial dashboard state reflects notification permission granted`() {
        fakeActionHandler.notificationPermissionGranted = true
        val ctrl = MainDashboardController(actionHandler = fakeActionHandler)
        assertTrue(ctrl.state.value.hasNotificationPermission)
    }

    @Test
    fun `initial dashboard state reflects notification permission denied`() {
        fakeActionHandler.notificationPermissionGranted = false
        val ctrl = MainDashboardController(actionHandler = fakeActionHandler)
        assertFalse(ctrl.state.value.hasNotificationPermission)
    }

    @Test
    fun `onRefreshPermission updates notification permission state`() {
        fakeActionHandler.notificationPermissionGranted = false
        val ctrl = MainDashboardController(actionHandler = fakeActionHandler)
        assertFalse(ctrl.state.value.hasNotificationPermission)

        fakeActionHandler.notificationPermissionGranted = true
        ctrl.onRefreshPermission()
        assertTrue(ctrl.state.value.hasNotificationPermission)
    }

    // =========================================================================
    // 2. Manual URL Input Handling & Trimming
    // =========================================================================

    @Test
    fun `onUrlChanged updates inputUrl in state`() {
        val testUrl = "https://www.reddit.com/r/technology/comments/12345"
        controller.onUrlChanged(testUrl)

        assertEquals(testUrl, controller.state.value.inputUrl)
    }

    @Test
    fun `onUrlChanged clears existing errorMessage`() {
        controller.onUrlChanged("")
        controller.onPlayClicked()
        assertNotNull(controller.state.value.errorMessageRes)

        controller.onUrlChanged("https://reddit.com/r/android")
        assertNull(controller.state.value.errorMessageRes)
    }

    @Test
    fun `onUrlChanged resets playbackStarted flag`() {
        controller.onUrlChanged("https://reddit.com/r/android")
        controller.onPlayClicked()
        assertTrue(controller.state.value.playbackStarted)

        controller.onUrlChanged("https://reddit.com/r/kotlin")
        assertFalse(controller.state.value.playbackStarted)
    }

    @Test
    fun `onPlayClicked trims leading and trailing whitespace from input URL`() {
        val cleanUrl = "https://reddit.com/r/technology/comments/123/title/"
        val paddedUrl = "   \t  $cleanUrl  \n "
        controller.onUrlChanged(paddedUrl)
        controller.onPlayClicked()

        assertEquals(listOf(cleanUrl), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
        assertNull(controller.state.value.errorMessageRes)
    }

    @Test
    fun `onPlayClicked strips trailing punctuation from candidate URL`() {
        val cleanUrl = "https://reddit.com/r/science/comments/456/sample"
        val punctuatedUrl = "$cleanUrl).?!"
        controller.onUrlChanged(punctuatedUrl)
        controller.onPlayClicked()

        assertEquals(listOf(cleanUrl), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
        assertNull(controller.state.value.errorMessageRes)
    }

    // =========================================================================
    // 3. Valid Reddit URL Formats & Service Launching
    // =========================================================================

    @Test
    fun `valid standard reddit com thread URL starts audio service`() {
        val url = "https://reddit.com/r/android/comments/111/first_post/"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
        assertNull(controller.state.value.errorMessageRes)
    }

    @Test
    fun `valid www reddit com thread URL starts audio service`() {
        val url = "https://www.reddit.com/r/science/comments/222/discovery/"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
    }

    @Test
    fun `valid old reddit com thread URL starts audio service`() {
        val url = "https://old.reddit.com/r/kotlin/comments/333/coroutines/"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
    }

    @Test
    fun `valid redd it shortlink starts audio service`() {
        val url = "https://redd.it/xyz999"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
    }

    @Test
    fun `valid v redd it media URL starts audio service`() {
        val url = "https://v.redd.it/vid12345"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
    }

    @Test
    fun `valid subdomain np reddit com starts audio service`() {
        val url = "https://np.reddit.com/r/AskReddit/comments/444/question/"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
    }

    @Test
    fun `valid language subdomain de reddit com starts audio service`() {
        val url = "https://de.reddit.com/r/worldnews/comments/555/german/"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
    }

    @Test
    fun `valid reddit URL with query parameters starts audio service`() {
        val url = "https://www.reddit.com/r/android/comments/666/p/?utm_source=share&utm_medium=web2x"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
    }

    @Test
    fun `valid reddit URL with fragment starts audio service`() {
        val url = "https://reddit.com/r/funny/comments/777/joke/#c123"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
    }

    @Test
    fun `valid subreddit landing URL starts audio service`() {
        val url = "https://reddit.com/r/technology"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertTrue(controller.state.value.playbackStarted)
    }

    // =========================================================================
    // 4. Overlay Permission Branching
    // =========================================================================

    @Test
    fun `valid URL with overlay permission granted starts both audio and overlay services`() {
        fakeActionHandler.overlayPermissionGranted = true
        controller.onRefreshPermission()

        val url = "https://reddit.com/r/technology/comments/123"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(1, fakeActionHandler.overlayServiceStartCount)
        assertTrue(controller.state.value.playbackStarted)
        assertTrue(controller.state.value.hasOverlayPermission)
    }

    @Test
    fun `valid URL with overlay permission denied starts only audio service`() {
        fakeActionHandler.overlayPermissionGranted = false
        controller.onRefreshPermission()

        val url = "https://reddit.com/r/technology/comments/123"
        controller.onUrlChanged(url)
        controller.onPlayClicked()

        assertEquals(listOf(url), fakeActionHandler.startedAudioUrls)
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
        assertTrue(controller.state.value.playbackStarted)
        assertFalse(controller.state.value.hasOverlayPermission)
    }

    @Test
    fun `multiple play clicks with overlay permission trigger services repeatedly`() {
        fakeActionHandler.overlayPermissionGranted = true
        controller.onRefreshPermission()

        val url1 = "https://reddit.com/r/technology/comments/1"
        val url2 = "https://reddit.com/r/technology/comments/2"

        controller.onUrlChanged(url1)
        controller.onPlayClicked()
        controller.onUrlChanged(url2)
        controller.onPlayClicked()

        assertEquals(listOf(url1, url2), fakeActionHandler.startedAudioUrls)
        assertEquals(2, fakeActionHandler.overlayServiceStartCount)
    }

    @Test
    fun `permission status refreshed during onPlayClicked reflects latest state`() {
        fakeActionHandler.overlayPermissionGranted = false
        val localController = MainDashboardController(actionHandler = fakeActionHandler)
        assertFalse(localController.state.value.hasOverlayPermission)

        fakeActionHandler.overlayPermissionGranted = true
        localController.onUrlChanged("https://reddit.com/r/test")
        localController.onPlayClicked()

        assertTrue(localController.state.value.hasOverlayPermission)
        assertEquals(1, fakeActionHandler.overlayServiceStartCount)
    }

    // =========================================================================
    // 5. Invalid URLs & Security Rejections
    // =========================================================================

    @Test
    fun `invalid non-Reddit domain sets error and starts no services`() {
        controller.onUrlChanged("https://google.com/search?q=reddit")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_INVALID_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
    }

    @Test
    fun `phishing spoof domain prefix sets error and starts no services`() {
        controller.onUrlChanged("https://reddit.com.attacker.org/r/fake")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_INVALID_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
    }

    @Test
    fun `lookalike non-Reddit domain sets error and starts no services`() {
        controller.onUrlChanged("https://notreddit.com/r/post")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_INVALID_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
    }

    @Test
    fun `insecure HTTP scheme sets error and starts no services`() {
        controller.onUrlChanged("http://reddit.com/r/technology")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_INVALID_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
    }

    @Test
    fun `non-HTTP protocol sets error and starts no services`() {
        controller.onUrlChanged("ftp://reddit.com/r/data")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_INVALID_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
    }

    @Test
    fun `userinfo injection attack sets error and starts no services`() {
        controller.onUrlChanged("https://reddit.com@evil.com/fake")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_INVALID_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
    }

    @Test
    fun `URL-encoded userinfo injection sets error and starts no services`() {
        controller.onUrlChanged("https://user%40reddit.com/fake")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_INVALID_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
    }

    @Test
    fun `backslash host confusion sets error and starts no services`() {
        controller.onUrlChanged("https://reddit.com\\fake.com")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_INVALID_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
    }

    @Test
    fun `SSRF localhost attack sets error and starts no services`() {
        controller.onUrlChanged("https://localhost/r/test")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_INVALID_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
    }

    @Test
    fun `SSRF private IPv4 attack sets error and starts no services`() {
        controller.onUrlChanged("https://192.168.1.1/r/test")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_INVALID_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
    }

    @Test
    fun `malformed URL syntax sets error and starts no services`() {
        controller.onUrlChanged("not-a-valid-url-format")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_INVALID_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
    }

    // =========================================================================
    // 6. Empty & Blank URL Handling
    // =========================================================================

    @Test
    fun `empty URL input sets empty error message and starts no services`() {
        controller.onUrlChanged("")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_EMPTY_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
        assertEquals(0, fakeActionHandler.overlayServiceStartCount)
    }

    @Test
    fun `whitespace-only URL input sets empty error message and starts no services`() {
        controller.onUrlChanged("   \t \n  ")
        controller.onPlayClicked()

        assertEquals(MainDashboardController.ERROR_EMPTY_URL, controller.state.value.errorMessageRes)
        assertFalse(controller.state.value.playbackStarted)
        assertTrue(fakeActionHandler.startedAudioUrls.isEmpty())
    }

    @Test
    fun `empty URL error message differs appropriately from invalid URL error message`() {
        controller.onUrlChanged("")
        controller.onPlayClicked()
        val emptyError = controller.state.value.errorMessageRes

        controller.onUrlChanged("https://google.com")
        controller.onPlayClicked()
        val invalidError = controller.state.value.errorMessageRes

        assertNotNull(emptyError)
        assertNotNull(invalidError)
        assertNotEquals(emptyError, invalidError)
    }

    // =========================================================================
    // 7. Error Dismissal
    // =========================================================================

    @Test
    fun `onDismissError clears active error message`() {
        controller.onUrlChanged("")
        controller.onPlayClicked()
        assertNotNull(controller.state.value.errorMessageRes)

        controller.onDismissError()
        assertNull(controller.state.value.errorMessageRes)
    }

    @Test
    fun `onDismissError is safe when no error exists`() {
        assertNull(controller.state.value.errorMessageRes)
        controller.onDismissError()
        assertNull(controller.state.value.errorMessageRes)
    }

    // =========================================================================
    // 8. Permission State Refresh
    // =========================================================================

    @Test
    fun `onRefreshPermission transitions from denied to granted when permission granted`() {
        assertFalse(controller.state.value.hasOverlayPermission)

        fakeActionHandler.overlayPermissionGranted = true
        controller.onRefreshPermission()

        assertTrue(controller.state.value.hasOverlayPermission)
    }

    @Test
    fun `onRefreshPermission transitions from granted to denied when permission revoked`() {
        fakeActionHandler.overlayPermissionGranted = true
        controller.onRefreshPermission()
        assertTrue(controller.state.value.hasOverlayPermission)

        fakeActionHandler.overlayPermissionGranted = false
        controller.onRefreshPermission()
        assertFalse(controller.state.value.hasOverlayPermission)
    }

    @Test
    fun `onRefreshPermission preserves existing input URL and error message`() {
        controller.onUrlChanged("https://reddit.com/r/android")
        fakeActionHandler.overlayPermissionGranted = true
        controller.onRefreshPermission()

        assertEquals("https://reddit.com/r/android", controller.state.value.inputUrl)
        assertTrue(controller.state.value.hasOverlayPermission)
        assertNull(controller.state.value.errorMessageRes)
    }

    // =========================================================================
    // 9. Overlay Settings Action Trigger
    // =========================================================================

    @Test
    fun `onOpenSettingsClicked triggers openOverlaySettings in action handler`() {
        assertEquals(0, fakeActionHandler.openSettingsCount)
        controller.onOpenSettingsClicked()
        assertEquals(1, fakeActionHandler.openSettingsCount)
    }

    @Test
    fun `multiple settings clicks trigger action handler repeatedly`() {
        controller.onOpenSettingsClicked()
        controller.onOpenSettingsClicked()
        controller.onOpenSettingsClicked()
        assertEquals(3, fakeActionHandler.openSettingsCount)
    }

    // =========================================================================
    // 10. AndroidManifest.xml Compliance Verification
    // =========================================================================

    @Test
    fun `manifest declares all required permissions including INTERNET`() {
        val manifestFile = findManifestFile()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val permissions = doc.getElementsByTagName("uses-permission")

        val permissionNames = mutableSetOf<String>()
        for (i in 0 until permissions.length) {
            val element = permissions.item(i) as Element
            permissionNames.add(element.getAttribute("android:name"))
        }

        assertTrue(
            permissionNames.contains("android.permission.INTERNET"),
            "Manifest must declare android.permission.INTERNET",
        )
        assertTrue(
            permissionNames.contains("android.permission.FOREGROUND_SERVICE"),
            "Manifest must declare android.permission.FOREGROUND_SERVICE",
        )
        assertTrue(
            permissionNames.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"),
            "Manifest must declare android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        )
        assertTrue(
            permissionNames.contains("android.permission.POST_NOTIFICATIONS"),
            "Manifest must declare android.permission.POST_NOTIFICATIONS",
        )
        assertTrue(
            permissionNames.contains("android.permission.SYSTEM_ALERT_WINDOW"),
            "Manifest must declare android.permission.SYSTEM_ALERT_WINDOW",
        )
    }

    @Test
    fun `manifest declares zero accessibility permissions and zero accessibility services`() {
        val manifestFile = findManifestFile()
        val content = manifestFile.readText()

        assertFalse(
            content.contains("BIND_ACCESSIBILITY_SERVICE", ignoreCase = true),
            "Manifest must contain ZERO BIND_ACCESSIBILITY_SERVICE declarations for Google Play policy",
        )
        assertFalse(
            content.contains("AccessibilityService", ignoreCase = true),
            "Manifest must contain ZERO AccessibilityService declarations",
        )
    }

    @Test
    fun `manifest declares MainActivity as exported with MAIN and LAUNCHER intent filter`() {
        val manifestFile = findManifestFile()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val activities = doc.getElementsByTagName("activity")

        var mainActivityElement: Element? = null
        for (i in 0 until activities.length) {
            val element = activities.item(i) as Element
            val name = element.getAttribute("android:name")
            if (name == ".ui.MainActivity" || name == "com.redder.ui.MainActivity") {
                mainActivityElement = element
                break
            }
        }

        assertNotNull(mainActivityElement, "MainActivity must be declared in AndroidManifest.xml")
        assertEquals(
            "true",
            mainActivityElement!!.getAttribute("android:exported"),
            "MainActivity must be exported",
        )

        val intentFilters = mainActivityElement.getElementsByTagName("intent-filter")
        assertTrue(intentFilters.length > 0, "MainActivity must declare at least one intent-filter")

        var hasMainAction = false
        var hasLauncherCategory = false

        for (i in 0 until intentFilters.length) {
            val filter = intentFilters.item(i) as Element
            val actions = filter.getElementsByTagName("action")
            for (j in 0 until actions.length) {
                val action = actions.item(j) as Element
                if (action.getAttribute("android:name") == "android.intent.action.MAIN") {
                    hasMainAction = true
                }
            }

            val categories = filter.getElementsByTagName("category")
            for (k in 0 until categories.length) {
                val category = categories.item(k) as Element
                if (category.getAttribute("android:name") == "android.intent.category.LAUNCHER") {
                    hasLauncherCategory = true
                }
            }
        }

        assertTrue(hasMainAction, "MainActivity must declare android.intent.action.MAIN")
        assertTrue(hasLauncherCategory, "MainActivity must declare android.intent.category.LAUNCHER")
    }

    @Test
    fun `manifest preserves RedderAudioService FloatingOverlayService and ShareReceiverActivity`() {
        val manifestFile = findManifestFile()
        val content = manifestFile.readText()

        assertTrue(
            content.contains("RedderAudioService"),
            "Manifest must retain RedderAudioService declaration",
        )
        assertTrue(
            content.contains("FloatingOverlayService"),
            "Manifest must retain FloatingOverlayService declaration",
        )
        assertTrue(
            content.contains("ShareReceiverActivity"),
            "Manifest must retain ShareReceiverActivity declaration",
        )
        assertTrue(
            content.contains("android.intent.action.SEND"),
            "Manifest must retain ACTION_SEND intent filter for ShareReceiverActivity",
        )
    }

    @Test
    fun `manifest declares application label as ThreadReader`() {
        val manifestFile = findManifestFile()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val appElement = doc.getElementsByTagName("application").item(0) as Element
        assertEquals("ThreadReader", appElement.getAttribute("android:label"))
    }

    @Test
    fun `strings xml defines app_name and legal disclaimer`() {
        val stringsFile = File("app/src/main/res/values/strings.xml")
        val fallbackStringsFile = File("src/main/res/values/strings.xml")
        val targetFile = if (stringsFile.exists()) stringsFile else fallbackStringsFile
        assertTrue(targetFile.exists(), "strings.xml must exist")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(targetFile)
        val stringNodes = doc.getElementsByTagName("string")
        val stringMap = mutableMapOf<String, String>()
        for (i in 0 until stringNodes.length) {
            val el = stringNodes.item(i) as Element
            stringMap[el.getAttribute("name")] = el.textContent
        }
        assertEquals("ThreadReader", stringMap["app_name"])
        assertTrue(stringMap.containsKey("disclaimer"))
        assertTrue(stringMap["disclaimer"]!!.contains("ThreadReader is an independent dialogue reader"))
    }

    private fun findManifestFile(): File {
        val candidates =
            listOf(
                File("src/main/AndroidManifest.xml"),
                File("app/src/main/AndroidManifest.xml"),
                File("../app/src/main/AndroidManifest.xml"),
            )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("AndroidManifest.xml not found in candidates: $candidates")
    }

    @Test
    fun `onOpenNotificationSettingsClicked calls actionHandler openNotificationSettings`() {
        controller.onOpenNotificationSettingsClicked()
        assertEquals(1, fakeActionHandler.openNotificationSettingsCount)
    }

    // =========================================================================
    // DefaultMainDashboardActionHandler Platform Seam Tests
    // =========================================================================

    @Test
    fun `DefaultMainDashboardActionHandler canDrawOverlays queries overlayPermissionChecker`() {
        var queried = false
        val handler =
            DefaultMainDashboardActionHandler(
                overlayPermissionChecker = {
                    queried = true
                    true
                },
            )
        assertTrue(handler.canDrawOverlays())
        assertTrue(queried)
    }

    @Test
    fun `DefaultMainDashboardActionHandler hasNotificationPermission queries notificationPermissionChecker`() {
        var queried = false
        val handler =
            DefaultMainDashboardActionHandler(
                notificationPermissionChecker = {
                    queried = true
                    false
                },
            )
        assertFalse(handler.hasNotificationPermission())
        assertTrue(queried)
    }

    @Test
    fun `DefaultMainDashboardActionHandler openOverlaySettings launches intent`() {
        var launchedIntent: Intent? = null
        val handler =
            DefaultMainDashboardActionHandler(
                activityStarter = { intent -> launchedIntent = intent },
                packageNameProvider = { "com.redder.test" },
            )
        handler.openOverlaySettings()
        assertNotNull(launchedIntent)
    }

    @Test
    fun `DefaultMainDashboardActionHandler openNotificationSettings launches app notification settings intent`() {
        var launchedIntent: Intent? = null
        val handler =
            DefaultMainDashboardActionHandler(
                activityStarter = { intent -> launchedIntent = intent },
                packageNameProvider = { "com.redder.test" },
                isAtLeastOreo = true,
            )
        handler.openNotificationSettings()
        assertNotNull(launchedIntent)
    }

    @Test
    fun `DefaultMainDashboardActionHandler startAudioService launches foreground service`() {
        var launchedIntent: Intent? = null
        val handler =
            DefaultMainDashboardActionHandler(
                foregroundServiceStarter = { intent -> launchedIntent = intent },
            )
        handler.startAudioService("https://reddit.com/r/test/comments/123")
        assertNotNull(launchedIntent)
    }

    @Test
    fun `DefaultMainDashboardActionHandler startOverlayService launches overlay service`() {
        var launchedIntent: Intent? = null
        val handler =
            DefaultMainDashboardActionHandler(
                serviceStarter = { intent -> launchedIntent = intent },
                packageNameProvider = { "com.redder.test" },
            )
        handler.startOverlayService()
        assertNotNull(launchedIntent)
    }

    @Test
    fun `DefaultMainDashboardActionHandler handles settings launch exceptions gracefully`() {
        val handler =
            DefaultMainDashboardActionHandler(
                activityStarter = { throw SecurityException("Cannot launch settings") },
                packageNameProvider = { "com.redder.test" },
            )
        // Must not throw
        handler.openOverlaySettings()
        handler.openNotificationSettings()
    }

    @Test
    fun `DefaultMainDashboardActionHandler openUrl launches ACTION_VIEW intent`() {
        var launchedIntent: Intent? = null
        val mockUri = org.mockito.Mockito.mock(android.net.Uri::class.java)
        val handler =
            DefaultMainDashboardActionHandler(
                activityStarter = { intent -> launchedIntent = intent },
                uriParser = { mockUri },
            )

        handler.openUrl("https://www.reddit.com/prefs/apps")

        assertNotNull(launchedIntent)
    }

    @Test
    fun `onPlayClicked starts playback for a valid thread URL`() {
        val targetUrl = "https://reddit.com/r/androiddev/comments/123/kotlin"
        controller.onUrlChanged(targetUrl)
        controller.onPlayClicked()

        assertTrue(controller.state.value.playbackStarted)
        assertNull(controller.state.value.errorMessageRes)
        assertEquals(listOf(targetUrl), fakeActionHandler.startedAudioUrls)
    }

    // =========================================================================
    // Paste From Clipboard
    // =========================================================================

    @Test
    fun `onPasteFromClipboard picks the Reddit link out of shared text`() {
        controller.onPasteFromClipboard(
            "Look at https://example.com/a and https://www.reddit.com/r/kotlin/comments/abc/title/. Wild",
        )
        assertEquals("https://www.reddit.com/r/kotlin/comments/abc/title/", controller.state.value.inputUrl)
    }

    @Test
    fun `onPasteFromClipboard falls back to first link then raw text`() {
        controller.onPasteFromClipboard("see https://example.com/page")
        assertEquals("https://example.com/page", controller.state.value.inputUrl)

        controller.onPasteFromClipboard("  just some words  ")
        assertEquals("just some words", controller.state.value.inputUrl)
    }

    @Test
    fun `onPasteFromClipboard ignores blank clipboard and clears error on paste`() {
        controller.onUrlChanged("keep me")
        controller.onPasteFromClipboard(null)
        controller.onPasteFromClipboard("   ")
        assertEquals("keep me", controller.state.value.inputUrl)

        controller.onUrlChanged("")
        controller.onPlayClicked()
        assertNotNull(controller.state.value.errorMessageRes)
        controller.onPasteFromClipboard("https://redd.it/abc123")
        assertNull(controller.state.value.errorMessageRes)
        assertEquals("https://redd.it/abc123", controller.state.value.inputUrl)
    }

    @Test
    fun `playback speeds include 1_25x and format compactly`() {
        assertTrue(RedderPreferences.PLAYBACK_SPEEDS.contains(1.25f))
        assertEquals("1x", RedderPreferences.formatPlaybackSpeed(1.0f))
        assertEquals("1.25x", RedderPreferences.formatPlaybackSpeed(1.25f))
        assertEquals("0.5x", RedderPreferences.formatPlaybackSpeed(0.5f))
        assertEquals("2x", RedderPreferences.formatPlaybackSpeed(2.0f))
    }

    @Test
    fun `strings xml defines the dashboard error messages`() {
        val stringsFile = File("app/src/main/res/values/strings.xml")
        val targetFile = if (stringsFile.exists()) stringsFile else File("src/main/res/values/strings.xml")
        val content = targetFile.readText()
        assertTrue(content.contains("name=\"error_empty_url\""))
        assertTrue(content.contains("name=\"error_invalid_url\""))
        assertTrue(content.contains("name=\"about_line\""))
    }

    @Test
    fun `onToggleReadUsernames updates state and saves to actionHandler`() {
        val testController = MainDashboardController(actionHandler = fakeActionHandler)
        assertTrue(testController.state.value.readUsernames)

        testController.onToggleReadUsernames(false)
        assertFalse(testController.state.value.readUsernames)
        assertFalse(fakeActionHandler.readUsernamesEnabled)

        testController.onToggleReadUsernames(true)
        assertTrue(testController.state.value.readUsernames)
        assertTrue(fakeActionHandler.readUsernamesEnabled)
    }

    @Test
    fun `onPlaybackSpeedChanged updates state and saves to actionHandler`() {
        val testController = MainDashboardController(actionHandler = fakeActionHandler)
        assertEquals(1.0f, testController.state.value.playbackSpeed)

        testController.onPlaybackSpeedChanged(1.5f)
        assertEquals(1.5f, testController.state.value.playbackSpeed)
        assertEquals(1.5f, fakeActionHandler.savedPlaybackSpeed)

        testController.onPlaybackSpeedChanged(2.0f)
        assertEquals(2.0f, testController.state.value.playbackSpeed)
        assertEquals(2.0f, fakeActionHandler.savedPlaybackSpeed)
    }

    @Test
    fun `onQualityStepsChanged updates state and saves to actionHandler`() {
        val testController = MainDashboardController(actionHandler = fakeActionHandler)
        assertEquals(8, testController.state.value.supertonicQualitySteps)

        testController.onQualityStepsChanged(4)
        assertEquals(4, testController.state.value.supertonicQualitySteps)
        assertEquals(4, fakeActionHandler.savedQualitySteps)
    }

    @Test
    fun `initial quality steps are loaded from actionHandler`() {
        val handler = FakeMainDashboardActionHandler(savedQualitySteps = 4)
        val testController = MainDashboardController(actionHandler = handler)

        assertEquals(4, testController.state.value.supertonicQualitySteps)
    }

    // =========================================================================
    // Voice engine picker
    // =========================================================================

    @Test
    fun `voice engine defaults to Supertonic with its default narrator`() {
        val state = controller.state.value

        assertEquals(VoiceEngine.SUPERTONIC, state.voiceEngine)
        assertEquals("M1", state.narratorVoiceId)
        assertEquals(VoicePackStatus.READY, state.voicePackStatus)
    }

    @Test
    fun `saved voice engine and narrator are loaded on start`() {
        val handler = FakeMainDashboardActionHandler(savedNarrators = mutableMapOf(VoiceEngine.SUPERTONIC to "F2"))

        val state = MainDashboardController(actionHandler = handler).state.value

        assertEquals(VoiceEngine.SUPERTONIC, state.voiceEngine)
        assertEquals("F2", state.narratorVoiceId)
    }

    @Test
    fun `onNarratorVoiceChanged saves per engine and ignores voices of other engines`() {
        controller.onNarratorVoiceChanged("F2")
        assertEquals("F2", controller.state.value.narratorVoiceId)
        assertEquals("F2", fakeActionHandler.savedNarrators[VoiceEngine.SUPERTONIC])

        controller.onNarratorVoiceChanged("not-a-supertonic-voice")
        assertEquals("F2", controller.state.value.narratorVoiceId)
        assertEquals("F2", fakeActionHandler.savedNarrators[VoiceEngine.SUPERTONIC])
    }

    @Test
    fun `finishing voice preparation refreshes the pack status`() {
        fakeActionHandler.packStatuses[VoiceEngine.SUPERTONIC] = VoicePackStatus.PREPARING
        controller.onVoicesPreparing(true)
        assertEquals(VoicePackStatus.PREPARING, controller.state.value.voicePackStatus)

        fakeActionHandler.packStatuses[VoiceEngine.SUPERTONIC] = VoicePackStatus.READY
        controller.onVoicesPreparing(false)

        assertEquals(VoicePackStatus.READY, controller.state.value.voicePackStatus)
    }

    @Test
    fun `settings card shows voice quality for Supertonic`() {
        assertTrue(SettingsCardRules.showsVoiceQuality(VoiceEngine.SUPERTONIC))
    }

    @Test
    fun `narrator picker string exists`() {
        val file = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml")).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        val strings =
            (0 until nodes.length).associate { i ->
                val el = nodes.item(i) as Element
                el.getAttribute("name") to el.textContent
            }

        assertNotNull(strings["narrator_voice_title"], "strings.xml is missing narrator_voice_title")
    }
}

/**
 * Fake implementation of [MainDashboardActionHandler] for pure JVM unit testing.
 */
class FakeMainDashboardActionHandler(
    var overlayPermissionGranted: Boolean = false,
    var notificationPermissionGranted: Boolean = true,
    var readUsernamesEnabled: Boolean = true,
    var savedPlaybackSpeed: Float = 1.0f,
    var savedQualitySteps: Int = 8,
    var savedVoiceEngine: VoiceEngine = VoiceEngine.DEFAULT,
    val savedNarrators: MutableMap<VoiceEngine, String> = mutableMapOf(),
    val packStatuses: MutableMap<VoiceEngine, VoicePackStatus> = mutableMapOf(),
) : MainDashboardActionHandler {
    val startedAudioUrls = mutableListOf<String>()
    var overlayServiceStartCount = 0
    var openSettingsCount = 0
    var openNotificationSettingsCount = 0
    var lastOpenedUrl: String? = null

    override fun canDrawOverlays(): Boolean = overlayPermissionGranted

    override fun hasNotificationPermission(): Boolean = notificationPermissionGranted

    override fun openOverlaySettings() {
        openSettingsCount++
    }

    override fun openNotificationSettings() {
        openNotificationSettingsCount++
    }

    override fun startAudioService(redditUrl: String) {
        startedAudioUrls.add(redditUrl)
    }

    override fun startOverlayService() {
        overlayServiceStartCount++
    }

    override fun isReadUsernamesEnabled(): Boolean = readUsernamesEnabled

    override fun saveReadUsernames(enabled: Boolean) {
        readUsernamesEnabled = enabled
    }

    override fun getPlaybackSpeed(): Float = savedPlaybackSpeed

    override fun savePlaybackSpeed(speed: Float) {
        savedPlaybackSpeed = speed
    }

    override fun getQualitySteps(): Int = savedQualitySteps

    override fun saveQualitySteps(steps: Int) {
        savedQualitySteps = steps
    }

    override fun openUrl(url: String) {
        lastOpenedUrl = url
    }

    override fun getVoiceEngine(): VoiceEngine = savedVoiceEngine

    override fun getNarratorVoice(engine: VoiceEngine): String = savedNarrators[engine] ?: EngineVoices.defaultNarrator(engine)

    override fun saveNarratorVoice(
        engine: VoiceEngine,
        voiceId: String,
    ) {
        savedNarrators[engine] = voiceId
    }

    override fun voicePackStatus(engine: VoiceEngine): VoicePackStatus = packStatuses[engine] ?: VoicePackStatus.READY
}
