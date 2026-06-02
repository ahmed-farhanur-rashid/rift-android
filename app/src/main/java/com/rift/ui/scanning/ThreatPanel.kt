package com.rift.ui.scanning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rift.core.ml.*
import com.rift.ui.theme.*

/**
 * Modal bottom sheet surface surfacing the MoE threat analysis results.
 *
 * Language policy (per IMPROVEMENT.md §5.2): descriptive, factual, no alarm language.
 * The content informs; it does not frighten.
 *
 * Shown when:
 *  - overallRiskLevel exceeds SAFE, OR
 *  - the user taps the shield icon in the stats bar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreatPanel(
    report: ThreatReport,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "RF Analysis",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                RiskLevelBadge(report.overallRiskLevel)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            // ── Expert results ────────────────────────────────────────────────
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(max = 420.dp)
            ) {
                // Evil Twin section
                report.evilTwinResult?.let { result ->
                    item {
                        ExpertCard(title = "Impersonation Check") {
                            Text(
                                "${result.affectedBssids.size} access points share the SSID " +
                                    "'${result.suspiciousSsid}' with different physical identifiers.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Similarity confidence: ${(result.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Risk scores section (only show APs with non-trivial risk)
                val riskyAps = report.riskScores.filter { it.score > 0.3f }
                    .sortedByDescending { it.score }
                if (riskyAps.isNotEmpty()) {
                    item {
                        ExpertCard(title = "Access Point Assessment") {
                            riskyAps.take(6).forEach { rs ->
                                RiskScoreRow(rs)
                            }
                        }
                    }
                }

                // Interference section
                report.interferenceReport?.let { ir ->
                    item {
                        ExpertCard(title = "Channel Interference") {
                            Text(
                                "Detected ${ir.severity.name.lowercase()} interference on the " +
                                    "current channel.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            ir.recommendedChannel?.let { ch ->
                                Text(
                                    "Channel $ch has lower occupancy on this band.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Anomaly section
                val anomalies = report.anomalyReport?.anomalies
                if (!anomalies.isNullOrEmpty()) {
                    item {
                        ExpertCard(title = "Environment Deviations") {
                            anomalies.take(5).forEach { anomaly ->
                                AnomalyRow(anomaly)
                            }
                        }
                    }
                }

                // Empty state
                if (report.evilTwinResult == null && riskyAps.isEmpty() &&
                    report.interferenceReport == null && anomalies.isNullOrEmpty()
                ) {
                    item {
                        Text(
                            "No significant observations from the current scan.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun RiskLevelBadge(level: RiskLevel) {
    val (color, label) = when (level) {
        RiskLevel.SAFE     -> SignalExcellent to "SAFE"
        RiskLevel.CAUTION  -> SignalFair to "CAUTION"
        RiskLevel.WARNING  -> SignalPoor to "WARNING"
        RiskLevel.CRITICAL -> SignalVeryPoor to "CRITICAL"
    }
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun ExpertCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = NeonCyan
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun RiskScoreRow(rs: RiskScore) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                rs.ssid.ifBlank { rs.bssid.takeLast(8) },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                rs.primaryReason.name.lowercase().replace('_', ' '),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        val scoreColor = when {
            rs.score > 0.7f -> SignalVeryPoor
            rs.score > 0.5f -> SignalPoor
            rs.score > 0.3f -> SignalFair
            else -> SignalGood
        }
        Text(
            "${(rs.score * 100).toInt()}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = scoreColor
        )
    }
}

@Composable
private fun AnomalyRow(anomaly: Anomaly) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            anomaly.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    )
}
