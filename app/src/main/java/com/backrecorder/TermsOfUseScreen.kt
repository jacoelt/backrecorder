package com.backrecorder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun Separator(
    color: Color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
    thickness: Dp = 1.dp,
    verticalPadding: Dp = 8.dp
) {
    Spacer(modifier = Modifier.height(verticalPadding))
    HorizontalDivider(thickness = thickness, color = color)
    Spacer(modifier = Modifier.height(verticalPadding))
}

fun parseMarkdown(markdown: String): List<@Composable () -> Unit> {
    val composables = mutableListOf<@Composable () -> Unit>()
    markdown.lines().forEach { line ->
        when {
            line.startsWith("### ") -> {
                val text = line.removePrefix("### ")
                composables.add {
                    Text(
                        text = text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
            line.startsWith("**") && line.endsWith("**") -> {
                val text = line.removePrefix("**").removeSuffix("**")
                composables.add {
                    Text(
                        text = text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            line == "---" -> {
                composables.add { Separator() }
            }
            line.isBlank() -> {
                composables.add { Spacer(modifier = Modifier.height(4.dp)) }
            }
            else -> {
                composables.add {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
    return composables
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsOfUseScreen(
    onAccepted: (() -> Unit)? = null,
    onDeclined: (() -> Unit)? = null,
    onClosed: (() -> Unit)? = null,
    readOnly: Boolean = false
) {
    val context = LocalContext.current
    val dataStore = SettingsDataStore.getInstance(context)
    val coroutineScope = rememberCoroutineScope()

    // Load TOS content once
    val tosMarkdown = remember {
        context.resources.openRawResource(R.raw.terms_of_use)
            .bufferedReader().use { it.readText() }
    }
    val tosLines = remember { parseMarkdown(tosMarkdown) }

    var hasReachedBottom by remember { mutableStateOf(false) }

    // LazyColumn state
    val listState = rememberLazyListState()

    LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo.totalItemsCount) {
        hasReachedBottom =
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == tosLines.lastIndex
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // Header
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.terms_warning),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = stringResource(R.string.terms_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Content
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(tosLines.size) { index ->
                    tosLines[index]()
                }
            }

            // Actions
            if (!readOnly) {
                Text(
                    text = stringResource(R.string.terms_scroll_to_accept),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { onDeclined?.invoke() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.terms_decline))
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                dataStore.saveTermsAccepted(true)
                                onAccepted?.invoke()
                            }
                        },
                        enabled = hasReachedBottom
                    ) {
                        Text(stringResource(R.string.terms_accept))
                    }
                }
            } else {
                Button(
                    onClick = { onClosed?.invoke() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(stringResource(R.string.terms_close))
                }
            }
        }
    }
}
