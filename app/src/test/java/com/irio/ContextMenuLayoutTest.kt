package com.irio

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class ContextMenuLayoutTest {
    @Test
    fun ordinaryStatsKeepTheirContentHuggingWidth() {
        val label = FixedMeasurable(60, 24)
        val value = FixedMeasurable(80, 24)
        val result = measure("contextStatMeasurePolicy", listOf(label, value), Constraints(maxWidth = 400))
        assertEquals(156, result.width)
        assertEquals(24, result.height)
        assertEquals(60, label.width)
        assertEquals(80, value.width)
    }

    @Test
    fun layoutsRespectTinyAndUnboundedConstraintCombinations() {
        for (maxWidth in listOf(0, 8, 32, 180, 400, Constraints.Infinity)) {
            for (maxHeight in listOf(0, 5, 60, 300, Constraints.Infinity)) {
                val constraints = Constraints(maxWidth = maxWidth, maxHeight = maxHeight)
                val rows = List(6) { FixedMeasurable(120, 48) }
                val stats = measure("contextStatMeasurePolicy", rows, constraints)
                assertTrue(stats.width in 0..maxWidth)
                assertTrue(stats.height in 0..maxHeight)
                rows.chunked(2).forEach { (label, value) ->
                    assertTrue(label.width + value.width <= stats.width)
                }
                val children = listOf(FixedMeasurable(240, 160), FixedMeasurable(180, 44))
                val menu = measure("contextRingMenuMeasurePolicy", children, constraints)
                assertTrue(menu.width in 0..maxWidth)
                assertTrue(menu.height in 0..maxHeight)
                assertTrue(children.sumOf { it.height } <= menu.height)
            }
        }
    }

    @Test
    fun narrowStatRowsDoNotOverlap() {
        val label = FixedMeasurable(150, 24)
        val value = FixedMeasurable(80, 24)
        val result = measure("contextStatMeasurePolicy", listOf(label, value), Constraints(maxWidth = 180))
        assertTrue("Label and value must have separate width budgets", label.width + 16 + value.width <= result.width)
    }

    @Test
    fun statRowsRespectAvailableHeight() {
        val result = measure(
            "contextStatMeasurePolicy",
            List(6) { FixedMeasurable(60, 32) },
            Constraints(maxWidth = 180, maxHeight = 60),
        )
        assertTrue("Statistics cannot report a height beyond the popup", result.height <= 60)
    }

    @Test
    fun menuBudgetsHeightBetweenStatsAndButton() {
        val stats = FixedMeasurable(180, 200)
        val button = FixedMeasurable(180, 44)
        val result = measure(
            "contextRingMenuMeasurePolicy", listOf(stats, button),
            Constraints(maxWidth = 180, maxHeight = 120),
        )
        assertTrue("Menu cannot exceed the popup height", result.height <= 120)
        assertTrue("Children and gap must fit the reported height", stats.height + 10 + button.height <= result.height)
    }

    @Test
    fun exactWidthStretchesStatisticsToTheGivenWidth() {
        val label = FixedMeasurable(60, 24)
        val value = FixedMeasurable(80, 24)
        val result = measure(
            "contextStatMeasurePolicy",
            listOf(label, value),
            Constraints(minWidth = 220, maxWidth = 220),
        )
        assertEquals(220, result.width)
        assertTrue(label.width + value.width <= result.width)
    }

    private fun measure(name: String, children: List<Measurable>, constraints: Constraints): MeasureResult {
        val policy = Class.forName("com.irio.ContextRingKt").getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(null) as MeasurePolicy
        // Only layout dimensions are needed; no Android owner or device input.
        val scope = Proxy.newProxyInstance(
            MeasureScope::class.java.classLoader, arrayOf(MeasureScope::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getDensity", "getFontScale" -> 1f
                "getLayoutDirection" -> LayoutDirection.Ltr
                "isLookingAhead" -> false
                "layout" -> object : MeasureResult {
                    override val width = args!![0] as Int
                    override val height = args[1] as Int
                    override val alignmentLines = emptyMap<androidx.compose.ui.layout.AlignmentLine, Int>()
                    override fun placeChildren() = Unit
                }
                else -> if (method.isDefault) {
                    java.lang.reflect.InvocationHandler::class.java.getMethod(
                        "invokeDefault", Any::class.java, java.lang.reflect.Method::class.java, Array<Any>::class.java,
                    ).invoke(null, proxy, method, args ?: emptyArray<Any>())
                } else {
                    error("Unexpected MeasureScope method: ${method.name}")
                }
            }
        } as MeasureScope
        return with(policy) { scope.measure(children, constraints) }
    }

    private class FixedMeasurable(private val naturalWidth: Int, private val naturalHeight: Int) : Measurable {
        var width = 0
        var height = 0
        override val parentData: Any? = null
        override fun minIntrinsicWidth(height: Int) = naturalWidth
        override fun maxIntrinsicWidth(height: Int) = naturalWidth
        override fun minIntrinsicHeight(width: Int) = naturalHeight
        override fun maxIntrinsicHeight(width: Int) = naturalHeight
        override fun measure(constraints: Constraints): Placeable {
            width = constraints.constrainWidth(naturalWidth)
            height = constraints.constrainHeight(naturalHeight)
            return object : Placeable() {
                init {
                    measuredSize = IntSize(this@FixedMeasurable.width, this@FixedMeasurable.height)
                    measurementConstraints = constraints
                }
                override fun get(alignmentLine: androidx.compose.ui.layout.AlignmentLine) =
                    androidx.compose.ui.layout.AlignmentLine.Unspecified
                override fun placeAt(position: IntOffset, zIndex: Float, layerBlock: (GraphicsLayerScope.() -> Unit)?) = Unit
            }
        }
    }
}
