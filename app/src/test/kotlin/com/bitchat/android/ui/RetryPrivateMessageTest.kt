package com.bitchat.android.ui

import com.bitchat.android.services.MessageRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P2-PR9 (D5): the ViewModel's Retry hand-off reaches the router with the same message ID. */
class RetryPrivateMessageTest {
    @Test
    fun `retry passes the message id to the router`() {
        val calls = mutableListOf<String>()
        val result = retryPrivateMessageVia({ id -> calls += id; MessageRouter.RouteResult.MESH }, "m1")
        assertEquals(listOf("m1"), calls)
        assertEquals(MessageRouter.RouteResult.MESH, result)
    }

    @Test
    fun `router failure never reaches the UI`() {
        assertNull(retryPrivateMessageVia({ throw IllegalStateException("boom") }, "m1"))
    }
}
