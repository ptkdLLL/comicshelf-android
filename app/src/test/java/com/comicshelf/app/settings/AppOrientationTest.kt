package com.comicshelf.app.settings

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrientationTest {
    @Test
    fun persistedChoicesRestoreTheirOrientationRequests() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, AppOrientation.fromSetting("portrait").request)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, AppOrientation.fromSetting("landscape").request)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, AppOrientation.fromSetting("system").request)
        assertEquals(AppOrientation.SYSTEM, AppOrientation.fromSetting(""))
    }
}
