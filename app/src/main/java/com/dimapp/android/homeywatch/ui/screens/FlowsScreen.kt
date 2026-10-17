package com.dimapp.android.homeywatch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.dimapp.android.homeywatch.R
import com.dimapp.android.homeywatch.api.models.FlowDto
import com.dimapp.android.homeywatch.repository.FlowRepository
import com.dimapp.android.homeywatch.repository.models.HomeyResult
import com.dimapp.android.homeywatch.ui.components.FlowItemChip
import com.dimapp.android.homeywatch.ui.theme.HomeyBlue
import com.dimapp.android.homeywatch.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Screen displaying and executing triggerable Homey Flows.
 */
@Composable
fun FlowsScreen(
    flowRepo: FlowRepository
) {
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    var flows by remember { mutableStateOf<List<FlowDto>>(emptyList()) }
    var folderMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val triggeringMap = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        isLoading = true
        when (val result = flowRepo.getFlows()) {
            is HomeyResult.Success -> {
                flows = result.data.filter { it.triggerable == true }
            }
            is HomeyResult.Error -> {
                errorMessage = result.message
            }
        }
        isLoading = false
    }

    Scaffold(
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(top = 46.dp, bottom = 24.dp, start = 8.dp, end = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
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
            } else {
                items(flows.size) { index ->
                    val flow = flows[index]
                    val isTriggering = triggeringMap[flow.id] == true

                    FlowItemChip(
                        flow = flow,
                        folderName = flow.folder?.let { folderMap[it] },
                        isTriggering = isTriggering,
                        onTrigger = {
                            triggeringMap[flow.id] = true
                            coroutineScope.launch {
                                flowRepo.triggerFlow(flow.id)
                                triggeringMap.remove(flow.id)
                            }
                        },
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
