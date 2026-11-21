package com.belugachat.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.belugachat.app.data.AnalyticsTotals
import com.belugachat.app.data.DailyAnalytics
import com.belugachat.app.data.HourlyAnalytics
import com.belugachat.app.data.ModelAnalytics
import com.belugachat.app.ui.components.Card
import com.belugachat.app.ui.components.SectionLabel
import com.belugachat.app.ui.components.SubScreen
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class AnalyticsPeriod(val label: String, val days: Int?) {
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    QUARTER("90 days", 90),
    ALL("All time", null),
}

data class AnalyticsSnapshot(
    val period: AnalyticsPeriod,
    val totals: AnalyticsTotals,
    val previous: AnalyticsTotals?,
    val models: List<ModelAnalytics>,
    val daily: List<DailyAnalytics>,
    val hourly: List<HourlyAnalytics>,
)

@Composable
fun AnalyticsScreen(vm: ManageViewModel, onBack: () -> Unit) {
    val snapshot by vm.analytics.collectAsState()
    val loading by vm.analyticsLoading.collectAsState()
    var period by rememberSaveable { mutableStateOf(AnalyticsPeriod.MONTH) }

    LaunchedEffect(Unit) { vm.loadAnalytics(period) }

    SubScreen("Analytics", onBack, actions = {
        IconButton(onClick = { vm.loadAnalytics(period) }, enabled = !loading) {
            Icon(Icons.Outlined.Refresh, "Refresh analytics")
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            PeriodPicker(period) {
                period = it
                vm.loadAnalytics(it)
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val data = snapshot
            when {
                data == null && loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                data == null -> Text(
                    "Couldn't load analytics.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(20.dp),
                )
                else -> AnalyticsContent(data)
            }
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun PeriodPicker(selected: AnalyticsPeriod, onSelect: (AnalyticsPeriod) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnalyticsPeriod.entries.forEach { period ->
            FilterChip(selected == period, { onSelect(period) }, label = { Text(period.label) })
        }
    }
}

@Composable
private fun AnalyticsContent(data: AnalyticsSnapshot) {
    SummaryGrid(data)
    Spacer(Modifier.height(8.dp))

    SectionLabel("Activity")
    Card {
        Column(Modifier.padding(16.dp)) {
            val points = chartPoints(data.daily)
            if (points.isEmpty()) {
                Text("No activity in this period.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TokenChart(points)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LegendDot("Input", MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    LegendDot("Output", MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${data.totals.activeDays} active day${if (data.totals.activeDays == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    SectionLabel("By model")
    Card {
        if (data.models.isEmpty()) {
            Text(
                "No model activity yet. Answers will appear here once you chat.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            val max = data.models.maxOf { it.totalTokens }.coerceAtLeast(1)
            data.models.forEachIndexed { index, model ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ModelRow(model, max)
            }
        }
    }

    SectionLabel("Insights")
    Card {
        val insights = insights(data)
        insights.forEachIndexed { index, insight ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InsightRow(insight.first, insight.second, insight.third)
        }
    }

    Text(
        "Counts come from tokens reported by providers. Check your provider dashboard for billing.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun SummaryGrid(data: AnalyticsSnapshot) {
    val totals = data.totals
    val success = successRate(totals)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricCard(
            "Tokens",
            fmt(totals.totalTokens),
            "${fmt(totals.inputTokens)} in · ${fmt(totals.outputTokens)} out",
            trend(totals.totalTokens, data.previous?.totalTokens),
            Modifier.weight(1f),
        )
        MetricCard(
            "Answers",
            totals.answers.toString(),
            "${totals.conversations} conversation${if (totals.conversations == 1) "" else "s"}",
            trend(totals.answers.toLong(), data.previous?.answers?.toLong()),
            Modifier.weight(1f),
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricCard(
            "Avg / answer",
            if (totals.answers == 0) "—" else fmt(totals.totalTokens / totals.answers),
            "tokens per answer",
            null,
            Modifier.weight(1f),
        )
        MetricCard(
            "Success",
            if (totals.answers == 0) "—" else "$success%",
            when {
                totals.answers == 0 -> "No answers yet"
                totals.errors == 0 -> "No errors"
                else -> "${totals.errors} error${if (totals.errors == 1) "" else "s"}"
            },
            null,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    detail: String,
    trend: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (trend != null) {
                Spacer(Modifier.height(4.dp))
                Text(trend, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun TokenChart(points: List<ChartPoint>) {
    val inputColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val outputColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val max = points.maxOf { it.input + it.output }.coerceAtLeast(1)

    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, size.height - 2f),
            size = Size(size.width, 2f),
            cornerRadius = CornerRadius(1f, 1f),
        )
        val gap = (size.width * 0.02f).coerceAtLeast(2f)
        val barWidth = ((size.width - gap * (points.size - 1)) / points.size).coerceAtLeast(1f)
        points.forEachIndexed { index, point ->
            val total = point.input + point.output
            if (total <= 0) return@forEachIndexed
            val height = (total.toFloat() / max) * size.height
            val inputHeight = height * point.input / total
            val outputHeight = height - inputHeight
            val x = index * (barWidth + gap)
            val radius = CornerRadius((barWidth / 2).coerceAtMost(6f), (barWidth / 2).coerceAtMost(6f))
            if (inputHeight > 0f) {
                drawRoundRect(
                    color = inputColor,
                    topLeft = Offset(x, size.height - inputHeight),
                    size = Size(barWidth, inputHeight),
                    cornerRadius = radius,
                )
            }
            if (outputHeight > 0f) {
                drawRoundRect(
                    color = outputColor,
                    topLeft = Offset(x, size.height - height),
                    size = Size(barWidth, outputHeight),
                    cornerRadius = radius,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.first().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Peak ${fmt(max)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(points.last().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendDot(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(4.dp), color = color, modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelRow(model: ModelAnalytics, maxTokens: Long) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.modelId,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = buildList {
                    model.providerName?.takeIf { it.isNotBlank() }?.let { add(it) }
                    add("${model.answers} answer${if (model.answers == 1) "" else "s"}")
                    if (model.errors > 0) add("${model.errors} error${if (model.errors == 1) "" else "s"}")
                }.joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(fmt(model.totalTokens), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${fmt(model.inputTokens)} in · ${fmt(model.outputTokens)} out",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier.fillMaxWidth((model.totalTokens.toFloat() / maxTokens).coerceIn(0f, 1f))
                    .fillMaxHeight().clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun InsightRow(title: String, value: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.titleSmall)
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class ChartPoint(val label: String, val input: Long, val output: Long)

private fun chartPoints(daily: List<DailyAnalytics>, maxPoints: Int = 30): List<ChartPoint> {
    if (daily.isEmpty()) return emptyList()
    val bucket = ceil(daily.size / maxPoints.toDouble()).toInt().coerceAtLeast(1)
    return daily.chunked(bucket).map { chunk ->
        ChartPoint(
            label = shortDate(chunk.first().day),
            input = chunk.sumOf { it.inputTokens },
            output = chunk.sumOf { it.outputTokens },
        )
    }
}

private fun insights(data: AnalyticsSnapshot): List<Triple<String, String, String>> {
    val totals = data.totals
    val busiest = data.daily.maxByOrNull { it.answers }
    val peak = data.hourly.maxByOrNull { it.answers }
    val success = if (totals.answers == 0) "—" else "${successRate(totals)}%"
    val successDetail = when {
        totals.answers == 0 -> "No answers yet"
        totals.errors == 0 -> "No errors"
        else -> "${totals.errors} errors"
    }
    return listOf(
        Triple("Success rate", success, successDetail),
        Triple(
            "Active days",
            totals.activeDays.toString(),
            data.period.days?.let { "of $it days" } ?: "since you started",
        ),
        Triple("Busiest day", busiest?.let { shortDate(it.day) } ?: "—", busiest?.let { "${it.answers} answers" } ?: ""),
        Triple("Peak time", peak?.let { hourRange(it.hour) } ?: "—", peak?.let { "${it.answers} answers" } ?: ""),
        Triple("Total tokens", fmt(totals.totalTokens), "${fmt(totals.inputTokens)} in · ${fmt(totals.outputTokens)} out"),
    )
}

private fun successRate(totals: AnalyticsTotals): Int =
    if (totals.answers == 0) 0 else ((totals.answers - totals.errors) * 100.0 / totals.answers).roundToInt()

private fun trend(current: Long, previous: Long?): String? {
    if (previous == null || previous <= 0) return null
    val change = ((current - previous) * 100.0 / previous).roundToInt()
    return when {
        change > 0 -> "+$change% vs previous period"
        change < 0 -> "$change% vs previous period"
        else -> "Same as previous period"
    }
}

private fun shortDate(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("MMM d"))
}.getOrDefault(day)

private fun hourRange(hour: Int): String {
    val start = hourLabel(hour)
    val end = hourLabel((hour + 1) % 24)
    val startSuffix = if (hour < 12) "AM" else "PM"
    val endSuffix = if ((hour + 1) % 24 < 12) "AM" else "PM"
    return if (startSuffix == endSuffix) {
        "${start.substringBefore(' ')}–$end"
    } else {
        "$start–$end"
    }
}

private fun hourLabel(hour: Int): String {
    val h = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$h ${if (hour < 12) "AM" else "PM"}"
}

private fun fmt(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
