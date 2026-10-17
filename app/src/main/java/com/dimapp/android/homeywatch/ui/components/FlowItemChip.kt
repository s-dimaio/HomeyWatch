package com.dimapp.android.homeywatch.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.api.models.FlowDto
import com.dimapp.android.homeywatch.ui.theme.HomeyBlue

/**
 * Reusable Wear OS chip for triggering a Homey flow.
 */
@Composable
fun FlowItemChip(
    flow: FlowDto,
    folderName: String? = null,
    isTriggering: Boolean = false,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier
) {
    Chip(
        onClick = { if (!isTriggering) onTrigger() },
        enabled = flow.enabled != false && !isTriggering,
        label = {
            Text(
                text = flow.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        secondaryLabel = if (!folderName.isNullOrBlank()) {
            {
                Text(
                    text = folderName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else null,
        icon = {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isTriggering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_play_arrow),
                        contentDescription = "Trigger Flow",
                        tint = HomeyBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = modifier.fillMaxWidth()
    )
}
