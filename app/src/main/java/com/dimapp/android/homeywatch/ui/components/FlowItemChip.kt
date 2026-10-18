package com.dimapp.android.homeywatch.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
 * Execution states for flow triggering.
 */
enum class FlowExecutionState {
    Idle,
    Triggering,
    Success,
    Error
}

/**
 * Reusable Wear OS chip for triggering a Homey flow.
 *
 * Supports both standard and advanced flows, displays the official Homey vector icon
 * (differentiating standard vs advanced flows visually), and provides transient execution states
 * (spinner, success check, error warning).
 *
 * @public
 * @param flow The [FlowDto] model.
 * @param folderName Optional folder name to display as secondary label.
 * @param executionState Current execution state of the flow.
 * @param onTrigger Callback invoked when user taps the chip.
 * @param modifier Optional modifier for the chip.
 */
@Composable
fun FlowItemChip(
    flow: FlowDto,
    folderName: String? = null,
    executionState: FlowExecutionState = FlowExecutionState.Idle,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isIdle = executionState == FlowExecutionState.Idle

    Chip(
        onClick = { if (isIdle) onTrigger() },
        enabled = flow.enabled != false && isIdle,
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
                when (executionState) {
                    FlowExecutionState.Triggering -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            indicatorColor = HomeyBlue
                        )
                    }
                    FlowExecutionState.Success -> {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = stringResource(id = R.string.flow_success),
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    FlowExecutionState.Error -> {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_error),
                            contentDescription = stringResource(id = R.string.flow_error),
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    FlowExecutionState.Idle -> {
                        val iconRes = if (flow.isAdvanced) {
                            R.drawable.ic_flow_advanced
                        } else {
                            R.drawable.ic_flow_standard
                        }
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = stringResource(id = R.string.flows_icon_desc),
                            tint = HomeyBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = modifier.fillMaxWidth()
    )
}
