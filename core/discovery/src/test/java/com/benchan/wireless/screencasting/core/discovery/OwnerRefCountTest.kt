package com.benchan.wireless.screencasting.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerRefCountTest {
    @Test
    fun secondOwnerKeepsDiscoveryAlive() {
        val c = OwnerRefCount()
        val main = Any()
        val share = Any()
        assertTrue(c.acquire(main))
        assertFalse(c.acquire(share))
        // 分享页关闭：主界面仍在发现
        assertFalse(c.release(share))
        assertTrue(c.release(main))
    }

    @Test
    fun repeatedAcquireCountsOnce() {
        val c = OwnerRefCount()
        val o = Any()
        c.acquire(o)
        c.acquire(o)
        assertEquals(1, c.count)
        assertTrue(c.release(o))
        // 多余的释放不出错
        assertTrue(c.release(o))
    }
}
