package com.mobileappauto.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 取消机制与重试策略的单元测试（REQ-011） */
class OpControlTest {

    @Before
    fun setUp() {
        OpRegistry.clear()
    }

    @Test
    fun `注册后可被取消`() {
        OpRegistry.begin("op-1")
        assertFalse(OpRegistry.isCancelled("op-1"))
        assertTrue(OpRegistry.cancel("op-1"))
        assertTrue(OpRegistry.isCancelled("op-1"))
    }

    @Test
    fun `取消不存在的操作返回 false`() {
        assertFalse(OpRegistry.cancel("nope"))
    }

    @Test
    fun `空 id 不注册也不可取消`() {
        OpRegistry.begin(null)
        OpRegistry.begin("")
        assertFalse(OpRegistry.isCancelled(null))
        assertFalse(OpRegistry.isCancelled(""))
        assertTrue(OpRegistry.running().isEmpty())
    }

    @Test
    fun `结束后不再出现在运行列表`() {
        OpRegistry.begin("op-2")
        OpRegistry.begin("op-3")
        assertEquals(setOf("op-2", "op-3"), OpRegistry.running().toSet())
        OpRegistry.end("op-2")
        assertEquals(listOf("op-3"), OpRegistry.running())
        assertFalse("已注销的 id 不应残留取消位", OpRegistry.isCancelled("op-2"))
    }

    @Test
    fun `全部取消`() {
        OpRegistry.begin("a")
        OpRegistry.begin("b")
        assertEquals(2, OpRegistry.cancelAll())
        assertTrue(OpRegistry.isCancelled("a"))
        assertTrue(OpRegistry.isCancelled("b"))
    }

    // ---------- 重试策略 ----------

    @Test
    fun `attempts 计算`() {
        assertEquals(1, RetryPolicy.attempts(0))
        assertEquals(3, RetryPolicy.attempts(2))
        assertEquals("上限 5 次重试 + 首次", 6, RetryPolicy.attempts(99))
        assertEquals(1, RetryPolicy.attempts(-3))
    }

    @Test
    fun `可重试错误`() {
        assertTrue(RetryPolicy.shouldRetry(0, 2, "NOT_FOUND"))
        assertTrue(RetryPolicy.shouldRetry(0, 2, "ACTION_FAILED"))
        assertTrue(RetryPolicy.shouldRetry(0, 2, "TIMEOUT"))
    }

    @Test
    fun `不可重试错误`() {
        assertFalse(RetryPolicy.shouldRetry(0, 2, "CANCELLED"))
        assertFalse(RetryPolicy.shouldRetry(0, 2, "BAD_ARGS"))
        assertFalse(RetryPolicy.shouldRetry(0, 2, null))
    }

    @Test
    fun `次数用尽不再重试`() {
        assertTrue(RetryPolicy.shouldRetry(1, 2, "NOT_FOUND"))
        assertFalse(RetryPolicy.shouldRetry(2, 2, "NOT_FOUND"))
    }
}
