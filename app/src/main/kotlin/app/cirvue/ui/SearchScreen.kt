package app.cirvue.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.cirvue.Panel
import app.cirvue.R
import app.cirvue.SearchState
import app.cirvue.Stage
import app.cirvue.Tool
import app.cirvue.camera.CameraScreen
import app.cirvue.capture.Source
import app.cirvue.select.SelectionOverlay

@Composable
fun SearchScreen(
    state: SearchState,
    onCaptured: (Bitmap) -> Unit,
    onCamera: () -> Unit,
    onExternal: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    when (val stage = state.stage) {
        Stage.Camera -> CameraScreen(onCaptured = onCaptured, onExternal = onExternal, onClose = onClose)
        Stage.Blocked -> Blocked(onCamera = onCamera, onClose = onClose)
        is Stage.Select -> {
            // Back first clears the selection, then returns to the camera for a photo, then closes.
            BackHandler(enabled = state.selection != null || stage.source == Source.Camera) {
                if (state.selection != null) state.show(stage) else onCamera()
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                SelectionOverlay(
                    image = stage.image,
                    tool = state.tool,
                    selection = state.selection,
                    textBoxes = state.textBoxes,
                    description = stringResource(R.string.selection_description),
                    onSelect = state::select,
                )
                AnimatedVisibility(
                    visible = state.selection == null,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    HintPill(stringResource(R.string.hint_select))
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .widthIn(max = 640.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedVisibility(
                        visible = state.panel != Panel.Hidden,
                        enter = slideInVertically { it / 2 } + fadeIn(),
                        exit = slideOutVertically { it / 2 } + fadeOut(),
                    ) {
                        ResultPanel(state, onOpenApp, Modifier.padding(bottom = 12.dp))
                    }
                    SelectToolbar(
                        tool = state.tool,
                        onTool = { state.tool = it },
                        onCamera = onCamera,
                        onClose = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun HintPill(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            text,
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SelectToolbar(tool: Tool, onTool: (Tool) -> Unit, onCamera: () -> Unit, onClose: () -> Unit) {
    HorizontalFloatingToolbar(expanded = true, colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()) {
        // Read inside the toolbar, so unchecked icons use the toolbar's content color.
        val toggleColors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
        )
        IconToggleButton(checked = tool == Tool.Draw, onCheckedChange = { onTool(Tool.Draw) }, colors = toggleColors) {
            Icon(painterResource(R.drawable.ic_gesture), stringResource(R.string.tool_draw))
        }
        IconToggleButton(checked = tool == Tool.Box, onCheckedChange = { onTool(Tool.Box) }, colors = toggleColors) {
            Icon(painterResource(R.drawable.ic_crop_free), stringResource(R.string.tool_box))
        }
        IconButton(onClick = onCamera) {
            Icon(painterResource(R.drawable.ic_photo_camera), stringResource(R.string.action_camera))
        }
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultPanel(state: SearchState, onOpenApp: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AnimatedContent(
            targetState = state.panel,
            contentKey = { it::class },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "panel",
        ) { panel ->
            Column(Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                when (panel) {
                    Panel.Hidden -> Unit
                    Panel.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(40.dp))
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.recognizing), style = MaterialTheme.typography.bodyLarge)
                    }
                    Panel.NoModels -> {
                        Text(stringResource(R.string.no_models_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.no_models_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onOpenApp) { Text(stringResource(R.string.action_open_cirvue)) }
                        QueryField(state)
                    }
                    Panel.NothingFound, Panel.Failed -> {
                        Text(
                            stringResource(if (panel == Panel.Failed) R.string.recognition_failed else R.string.nothing_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        QueryField(state)
                    }
                    is Panel.Ready -> {
                        QueryField(state)
                        if (panel.query.suggestions.isNotEmpty()) {
                            Row(
                                Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                panel.query.suggestions.forEach { s ->
                                    SuggestionChip(onClick = { state.pickSuggestion(s) }, label = { Text(s, maxLines = 1) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueryField(state: SearchState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { state.query = it },
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.query_label)) },
            singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { state.query = "" }) {
                        Icon(painterResource(R.drawable.ic_cancel), stringResource(R.string.action_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { state.submit() }),
            shape = CircleShape,
        )
        Spacer(Modifier.size(8.dp))
        Button(onClick = state::submit, enabled = state.query.isNotBlank()) {
            Text(stringResource(R.string.action_search))
        }
    }
}

@Composable
private fun Blocked(onCamera: () -> Unit, onClose: () -> Unit) {
    // A Surface, not a background, so text gets the matching content color.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 480.dp)) {
                Icon(
                    painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.blocked_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.blocked_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                    Button(onClick = onCamera) { Text(stringResource(R.string.action_use_camera)) }
                }
            }
        }
    }
}
