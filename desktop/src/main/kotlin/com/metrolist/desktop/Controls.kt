package com.metrolist.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Symbol { Play, Pause, Previous, Next, Shuffle, Repeat, RepeatOne, Heart, Plus, Menu, Close, Up, Down, Minus, Volume, Muted, Queue, Refresh }

@Composable fun ControlIcon(symbol: Symbol, description: String?, modifier: Modifier = Modifier.size(22.dp), color: Color = LocalContentColor.current) {
    Canvas(modifier.then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        withTransform({ scale(size.width / 24, size.height / 24, Offset.Zero) }) {
            fun path(vararg points: Pair<Float, Float>, fill: Boolean = false) {
                val shape = Path().apply { moveTo(points[0].first, points[0].second); points.drop(1).forEach { lineTo(it.first, it.second) }; if (fill) close() }
                drawPath(shape, color, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.8f, cap = StrokeCap.Round))
            }
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2), 1.8f, StrokeCap.Round)
            when (symbol) {
                Symbol.Play -> path(7f to 4f, 20f to 12f, 7f to 20f, fill = true)
                Symbol.Pause -> { drawRect(color, Offset(6f, 5f), androidx.compose.ui.geometry.Size(4f, 14f)); drawRect(color, Offset(14f, 5f), androidx.compose.ui.geometry.Size(4f, 14f)) }
                Symbol.Next -> { path(5f to 5f, 16f to 12f, 5f to 19f, fill = true); line(19f, 5f, 19f, 19f) }
                Symbol.Previous -> { path(19f to 5f, 8f to 12f, 19f to 19f, fill = true); line(5f, 5f, 5f, 19f) }
                Symbol.Shuffle -> { path(3f to 5f, 7f to 5f, 17f to 19f, 21f to 19f); path(3f to 19f, 7f to 19f, 17f to 5f, 21f to 5f); path(18f to 2f, 21f to 5f, 18f to 8f); path(18f to 16f, 21f to 19f, 18f to 22f) }
                Symbol.Repeat, Symbol.RepeatOne -> {
                    path(4f to 10f, 4f to 6f, 20f to 6f, 17f to 3f); path(20f to 14f, 20f to 18f, 4f to 18f, 7f to 21f)
                    if (symbol == Symbol.RepeatOne) { path(10f to 10f, 12f to 8f, 12f to 15f); line(10f, 15f, 14f, 15f) }
                }
                Symbol.Heart -> {
                    val heart = Path().apply { moveTo(12f, 20f); cubicTo(-5f, 10f, 5f, -2f, 12f, 6f); cubicTo(19f, -2f, 29f, 10f, 12f, 20f); close() }
                    drawPath(heart, color, style = Stroke(1.8f))
                }
                Symbol.Plus -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
                Symbol.Minus -> line(5f, 12f, 19f, 12f)
                Symbol.Menu -> listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.7f, Offset(12f, it)) }
                Symbol.Close -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
                Symbol.Up -> path(5f to 15f, 12f to 8f, 19f to 15f)
                Symbol.Down -> path(5f to 9f, 12f to 16f, 19f to 9f)
                Symbol.Volume, Symbol.Muted -> { path(3f to 9f, 7f to 9f, 12f to 5f, 12f to 19f, 7f to 15f, 3f to 15f, fill = true); if (symbol == Symbol.Volume) { path(16f to 8f, 19f to 12f, 16f to 16f) } else { line(16f, 9f, 22f, 15f); line(16f, 15f, 22f, 9f) } }
                Symbol.Queue -> { line(3f, 5f, 21f, 5f); line(3f, 11f, 15f, 11f); line(3f, 17f, 12f, 17f); path(18f to 13f, 23f to 17f, 18f to 21f, fill = true) }
                Symbol.Refresh -> { path(20f to 10f, 18f to 5f, 12f to 3f, 6f to 5f, 3f to 11f, 5f to 17f, 11f to 21f, 17f to 19f); path(15f to 10f, 21f to 10f, 21f to 4f) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun ControlButton(symbol: Symbol, description: String, enabled: Boolean = true, active: Boolean = false, filled: Boolean = false, size: Dp = 40.dp, action: () -> Unit) {
    TooltipArea(tooltip = { Surface(shadowElevation = 6.dp, shape = MaterialTheme.shapes.small) { Text(description, Modifier.padding(10.dp, 6.dp), style = MaterialTheme.typography.labelSmall) } }) {
        val modifier = Modifier.size(size).semantics { selected = active; contentDescription = description }
        if (filled) FilledIconButton(onClick = action, enabled = enabled, modifier = modifier) { ControlIcon(symbol, null, Modifier.size(26.dp)) }
        else IconButton(onClick = action, enabled = enabled, modifier = modifier) { ControlIcon(symbol, null, color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f) else if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
    }
}
