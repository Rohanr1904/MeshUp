package com.bitchat.android.ui

import com.bitchat.android.ui.media.PhotoMenuItem
import com.bitchat.android.ui.media.isCameraPermissionBlocked
import com.bitchat.android.ui.media.photoMenuItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMenuLogicTest {
    @Test fun withCameraShowsBothItems() {
        assertEquals(listOf(PhotoMenuItem.TAKE_PHOTO, PhotoMenuItem.CHOOSE_PHOTO), photoMenuItems(true))
    }

    @Test fun withoutCameraHidesTakePhoto() {
        assertEquals(listOf(PhotoMenuItem.CHOOSE_PHOTO), photoMenuItems(false))
    }

    @Test fun blockedWhenNoRationale() {
        assertTrue(isCameraPermissionBlocked(false))
        assertFalse(isCameraPermissionBlocked(true))
    }
}
