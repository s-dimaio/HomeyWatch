package com.dimapp.android.homeywatch.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.api.models.FlowDto
import com.dimapp.android.homeywatch.repository.FlowRepository
import com.dimapp.android.homeywatch.repository.models.HomeyResult
import com.dimapp.android.homeywatch.ui.components.FlowExecutionState
import com.dimapp.android.homeywatch.ui.components.FlowItemChip
import com.dimapp.android.homeywatch.ui.theme.HomeyBlue
import com.dimapp.android.homeywatch.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Screen displaying and executing triggerable Homey Flows (Standard & Advanced).
 *
 * Implements:
 * - Rotary crown scrolling support
 * - Grouping by flow folder with clean section headers
 * - Distinct badges and official Homey vector icons for Standard and Advanced flows
 * - Transient visual and haptic state feedback (in-flight spinner, success check, error alert)
 * - Manual refresh button
 *
 * @public
 * @param flowRepo Flow repository handling network calls, caching, and execution proxy.
 * @param modifier Screen layout modifier.
 */
@Composable
fun FlowsScreen(
    flowRepo: FlowRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }

    var flows by remember { mutableStateOf<List<FlowDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val executionStateMap = remember { mutableStateMapOf<String, FlowExecutionState>() }

    val generalFolder = stringResource(id = R.string.flows_folder_general)
    val groupedFlows = remember(flows, generalFolder) {
        flows.groupBy { it.folderName ?: generalFolder }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        isLoading = true
        when (val result = flowRepo.getFlows()) {
            is HomeyResult.Success -> {
                flows = result.data.filter { it.triggerable == true }
                errorMessage = null
            }
            is HomeyResult.Error -> {
                errorMessage = result.message
            }
        }
        isLoading = false
    }

    fun _onTriggerFlow(flow: FlowDto) {
        if (executionStateMap[flow.id] != null && executionStateMap[flow.id] != FlowExecutionState.Idle) {
            return
        }

        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        executionStateMap[flow.id] = FlowExecutionState.Triggering

        coroutineScope.launch {
            when (val result = flowRepo.triggerFlow(flow.id, flow.isAdvanced)) {
                is HomeyResult.Success -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    executionStateMap[flow.id] = FlowExecutionState.Success
                    delay(1500)
                    executionStateMap.remove(flow.id)
                }
                is HomeyResult.Error -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    executionStateMap[flow.id] = FlowExecutionState.Error
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    delay(2500)
                    executionStateMap.remove(flow.id)
                }
            }
        }
    }

    Scaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
        modifier = modifier
    ) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(top = 46.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onRotaryScrollEvent { event ->
                    coroutineScope.launch {
                        listState.scrollBy(event.verticalScrollPixels)
                    }
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            item {
                Text(
                    text = stringResource(id = R.string.flows_title),
                    style = MaterialTheme.typography.title1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 3.dp,
                            indicatorColor = HomeyBlue
                        )
                    }
                }
            } else if (errorMessage != null) {
                item {
                    Text(
                        text = errorMessage!!,
                        color = Color.Red,
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                item {
                    CompactChip(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                when (val result = flowRepo.getFlows(forceRefresh = true)) {
                                    is HomeyResult.Success -> {
                                        flows = result.data.filter { it.triggerable == true }
                                    }
                                    is HomeyResult.Error -> {
                                        errorMessage = result.message
                                    }
                                }
                                isLoading = false
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_refresh),
                                contentDescription = stringResource(id = R.string.btn_retry),
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(text = stringResource(id = R.string.btn_retry))
                        },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            } else if (flows.isEmpty()) {
                item {
                    Text(
                        text = stringResource(id = R.string.flows_empty),
                        style = MaterialTheme.typography.body2,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                item {
                    CompactChip(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                when (val result = flowRepo.getFlows(forceRefresh = true)) {
                                    is HomeyResult.Success -> {
                                        flows = result.data.filter { it.triggerable == true }
                                    }
                                    is HomeyResult.Error -> {
                                        errorMessage = result.message
                                    }
                                }
                                isLoading = false
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_refresh),
                                contentDescription = stringResource(id = R.string.flows_btn_refresh),
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(text = stringResource(id = R.string.flows_btn_refresh))
                        },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                }
            } else {
                groupedFlows.forEach { (folderName, folderFlows) ->
                    item {
                        Text(
                            text = folderName.uppercase(),
                            style = MaterialTheme.typography.caption2,
                            color = TextSecondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 4.dp, start = 8.dp)
                        )
                    }

                    items(folderFlows.size) { index ->
                        val flow = folderFlows[index]
                        val executionState = executionStateMap[flow.id] ?: FlowExecutionState.Idle

                        FlowItemChip(
                            flow = flow,
                            folderName = null,
                            executionState = executionState,
                            onTrigger = { _onTriggerFlow(flow) },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    CompactChip(
                        onClick = {
                            if (!isRefreshing) {
                                coroutineScope.launch {
                                    isRefreshing = true
                                    when (val res = flowRepo.getFlows(forceRefresh = true)) {
                                        is HomeyResult.Success -> {
                                            flows = res.data.filter { it.triggerable == true }
                                            errorMessage = null
                                        }
                                        is HomeyResult.Error -> {
                                            Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    isRefreshing = false
                                }
                            }
                        },
                        icon = {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    indicatorColor = HomeyBlue
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_refresh),
                                    contentDescription = stringResource(id = R.string.flows_btn_refresh),
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(id = R.string.flows_btn_refresh),
                                style = MaterialTheme.typography.caption2
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            }
        }
    }
}
