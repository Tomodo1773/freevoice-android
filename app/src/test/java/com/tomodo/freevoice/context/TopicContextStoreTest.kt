package com.tomodo.freevoice.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicContextStoreTest {
    @Test fun `ttl expires entries`() {
        var time = 0L; val store = TopicContextStore { time }
        store.put("app", "topic"); time = TopicContextStore.TTL_MS + 1
        assertNull(store.get("app"))
    }
    @Test fun `least recently used entry is evicted after twenty`() {
        val store = TopicContextStore()
        repeat(20) { store.put("app$it", "$it") }; store.get("app0"); store.put("new", "new")
        assertEquals("0", store.get("app0")); assertNull(store.get("app1")); assertEquals("new", store.get("new"))
    }
    @Test fun `in flight guard is exclusive until finished`() {
        val store = TopicContextStore()
        assertTrue(store.beginDistill("app")); assertFalse(store.beginDistill("app")); store.finishDistill("app"); assertTrue(store.beginDistill("app"))
    }
}
