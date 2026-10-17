package com.dimapp.android.homeywatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.dimapp.android.homeywatch.ui.theme.HomeyBlueDark
import com.dimapp.android.homeywatch.ui.theme.TextPrimary
import com.dimapp.android.homeywatch.ui.theme.TextSecondary
import java.util.Locale

/**
 * Section header displaying zone name and optional room temperature badge.
 *
 * Implements a 2-line layout where long zone names can wrap gracefully across up to 2 lines,
 * and the room temperature badge is placed directly beneath without clipping or horizontal squishing.
 *
 * @param title Zone / Room display title.
 * @param temperature Measured zone temperature in degrees Celsius, if available.
 * @param modifier Compose layout modifier.
 */
@Composable
fun ZoneSectionHeader(
    title: String,
    temperature: Double? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.title3,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (temperature != null && !temperature.isNaN()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(HomeyBlueDark.copy(alpha = 0.35f))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = String.format(Locale.getDefault(), "%.1f°C", temperature),
                    style = MaterialTheme.typography.caption2,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
