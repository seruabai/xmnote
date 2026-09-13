package com.purenote.local.ui

import androidx.compose.animation.core.Spring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动效令牌参数化测试（用户 2026-09-13 要求"慢速、高级"）：
 * 锁定节奏与弹簧性格——慢而不拖、弹而不跳。
 */
class MotionTokensTest {

    @Test
    fun `页面转场是慢速节奏`() {
        assertTrue("推入应>=380ms(慢速)", Motion.SCREEN_IN in 380..500)
        assertTrue("退出应比推入快", Motion.SCREEN_OUT < Motion.SCREEN_IN)
        assertTrue("退出不拖沓", Motion.SCREEN_OUT in 250..400)
    }

    @Test
    fun `微交互是快节奏`() {
        assertTrue("按压/勾选微交互<=200ms", Motion.FAST in 100..200)
        assertTrue("淡入淡出基线在中间档", Motion.FADE in 180..320)
    }

    @Test
    fun `推入弹簧轻微过冲不夸张`() {
        val s = Motion.screenSpring<Float>()
        assertTrue("阻尼0.8-0.95之间:有过冲但不弹跳", s.dampingRatio in 0.8f..0.95f)
        assertTrue("慢刚度(高级感的慢)", s.stiffness <= 450f)
        assertTrue("刚度不至于软塌", s.stiffness >= 250f)
    }

    @Test
    fun `弹层弹簧几乎无过冲`() {
        val s = Motion.sheetSpring<Float>()
        assertTrue(s.dampingRatio >= 0.9f)
        assertTrue(s.stiffness <= 400f)
    }

    @Test
    fun `微交互弹簧是标准中弹`() {
        val s = Motion.pressSpring<Float>()
        assertEquals(Spring.DampingRatioMediumBouncy, s.dampingRatio, 0.0001f)
        assertEquals(Spring.StiffnessMedium, s.stiffness, 0.0001f)
    }

    @Test
    fun `缓动曲线端点正确(0进1出)`() {
        listOf(Motion.EaseOut, Motion.Emphasized, Motion.EaseIn).forEach { e ->
            assertEquals(0f, e.transform(0f), 0.0001f)
            assertEquals(1f, e.transform(1f), 0.0001f)
        }
        // EaseOut 中段应过半(快出)
        assertTrue(Motion.EaseOut.transform(0.5f) > 0.6f)
    }
}
