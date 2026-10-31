package io.github.foadsf.openobd.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.foadsf.openobd.obd.Pid
import io.github.foadsf.openobd.obd.Reading
import io.github.foadsf.openobd.obd.Units
import io.github.foadsf.openobd.obd.fmt

val PanelShape = RoundedCornerShape(20.dp)

/** Units whose readings are clearer without a decimal. */
private val WHOLE_UNITS = setOf("rpm", "km/h", "mph", "°C", "°F", "kPa", "km", "mi", "s", "min")

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Int = 16,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(PanelShape).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        color = Palette.surface,
        shape = PanelShape,
    ) {
        Column(Modifier.padding(padding.dp), content = content)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
            color = Palette.muted,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium.merge(Numbers),
        maxLines = 1,
    )
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Palette.text, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(key, color = Palette.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(12.dp))
        Text(
            value,
            color = valueColor,
            style = if (mono) Mono else MaterialTheme.typography.bodyMedium.merge(Numbers),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.3f),
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Palette.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Palette.sky, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        if (action != null) {
            Spacer(Modifier.size(24.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/** A 240° arc gauge. The arc animates; the number is exact. */
@Composable
fun Gauge(pid: Pid, reading: Reading?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val value = reading?.value
    val target = if (value == null || !pid.gaugeable) 0f
    else ((value - pid.min) / (pid.max - pid.min)).toFloat().coerceIn(0f, 1f)
    val fraction by animateFloatAsState(target, tween(450), label = "gauge")
    val accent = when {
        pid.id == 0x05 && (value ?: 0.0) >= 110 -> Palette.crimson
        pid.id == 0x0C && (value ?: 0.0) >= 6000 -> Palette.amber
        else -> Palette.sky
    }
    val (shown, unit) = if (value == null) "—" to pid.unit else Units.convert(value, pid.unit).let { (v, u) ->
        (if (u in WHOLE_UNITS || kotlin.math.abs(v) >= 100) fmt(v, 0) else fmt(v, 1)) to u
    }
    Panel(modifier, onClick = onClick, padding = 12) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val stroke = size.minDimension * 0.075f
                val d = size.minDimension - stroke
                val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2 + d * 0.08f)
                val arc = Size(d, d)
                drawArc(Palette.outline, 150f, 240f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                if (fraction > 0f) {
                    drawArc(accent, 150f, 240f * fraction, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    shown,
                    style = MaterialTheme.typography.headlineMedium.merge(Numbers).copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                Text(unit, style = MaterialTheme.typography.labelMedium, color = Palette.muted)
            }
        }
        Text(
            pid.name,
            style = MaterialTheme.typography.labelLarge,
            color = Palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
