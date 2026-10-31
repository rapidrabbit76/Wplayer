package dev.wplayer.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code the app runs at startup and on its common screens, so it is
 * compiled ahead of time on install instead of interpreted on first use.
 *
 * Run with `./gradlew :app:generateBaselineProfile` on a connected device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun browseAndSettings() = rule.collect(packageName = PACKAGE) {
        // Lets the journey list device storage without going through the permission screen.
        device.executeShellCommand("appops set $PACKAGE MANAGE_EXTERNAL_STORAGE allow")
        pressHome()
        startActivityAndWait()

        if (tapText("내부 저장소")) {
            scrollDown()
            if (tapText("Download") || tapText("Movies")) scrollDown()
            device.pressBack()
            device.pressBack()
        }
        for (tab in listOf("재생목록", "다운로드", "설정")) tapText(tab)
        for (category in listOf("일반", "재생", "자막", "네트워크")) tapText(category)
        tapText("보관함")
    }

    /** Taps the element showing [text]; false when it does not appear (e.g. missing test data). */
    private fun MacrobenchmarkScope.tapText(text: String): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), TIMEOUT_MS) ?: return false
        node.click()
        device.waitForIdle()
        return true
    }

    private fun MacrobenchmarkScope.scrollDown() {
        device.wait(Until.findObject(By.scrollable(true)), TIMEOUT_MS)?.fling(Direction.DOWN)
        device.waitForIdle()
    }

    private companion object {
        const val PACKAGE = "dev.wplayer"
        const val TIMEOUT_MS = 5_000L
    }
}
