package app.cirvue.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.cirvue.R
import app.cirvue.models.FailReason
import app.cirvue.models.ModelPack
import app.cirvue.models.ModelStore
import app.cirvue.models.PackState
import app.cirvue.settings.Settings
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    isAssistant: Boolean,
    settings: Settings,
    models: ModelStore,
    onAssistantSettings: () -> Unit,
    onCamera: () -> Unit,
    onDownload: (ModelPack) -> Unit,
    onRemove: (ModelPack) -> Unit,
) {
    val states by models.states.collectAsStateWithLifecycle()
    val review by settings.reviewQuery.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                models.refresh()
                delay(if (models.anyActive) 500 else 5_000)
            }
        }
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text(stringResource(R.string.app_name)) }, scrollBehavior = scroll) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { AssistantCard(isAssistant, onAssistantSettings, Modifier.readable()) }
            item {
                Button(
                    onClick = onCamera,
                    modifier = Modifier.readable().heightIn(min = 56.dp),
                    contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_photo_camera), null)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.search_with_camera), style = MaterialTheme.typography.titleMedium)
                }
            }
            item { SectionTitle(stringResource(R.string.section_models)) }
            item {
                Card(Modifier.readable(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    ModelPack.entries.forEach { pack ->
                        ModelRow(pack, states[pack] ?: PackState.Missing, onDownload, onRemove)
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.section_settings)) }
            item {
                Card(Modifier.readable(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.setting_review)) },
                        supportingContent = { Text(stringResource(R.string.setting_review_detail)) },
                        trailingContent = { Switch(checked = review, onCheckedChange = settings::setReviewQuery) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
            item {
                Row(Modifier.readable().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        stringResource(R.string.privacy_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Keeps lines readable on tablets and in landscape. */
private fun Modifier.readable() = widthIn(max = 640.dp).fillMaxWidth()

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.readable().padding(top = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AssistantCard(isAssistant: Boolean, onSettings: () -> Unit, modifier: Modifier) {
    val colors = if (isAssistant) {
        CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh)
    } else {
        CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)
    }
    Card(modifier, colors = colors, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isAssistant) {
                    Icon(
                        painterResource(R.drawable.ic_check_circle),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(12.dp))
                }
                Text(
                    stringResource(if (isAssistant) R.string.assistant_ready_title else R.string.assistant_setup_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(if (isAssistant) R.string.assistant_ready_body else R.string.assistant_setup_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!isAssistant) {
                Spacer(Modifier.size(16.dp))
                Button(onClick = onSettings) { Text(stringResource(R.string.assistant_open_settings)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelRow(pack: ModelPack, state: PackState, onDownload: (ModelPack) -> Unit, onRemove: (ModelPack) -> Unit) {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, pack.size)
    val title = stringResource(if (pack == ModelPack.Text) R.string.model_text else R.string.model_vision)
    val detail = stringResource(if (pack == ModelPack.Text) R.string.model_text_detail else R.string.model_vision_detail)
    val status = when (state) {
        PackState.Installed -> stringResource(R.string.model_installed, size)
        PackState.Missing -> stringResource(R.string.model_missing, size)
        is PackState.Downloading -> state.progress?.let { stringResource(R.string.model_downloading, (it * 100).toInt()) }
            ?: stringResource(R.string.model_downloading_unknown)
        PackState.Verifying -> stringResource(R.string.model_verifying)
        is PackState.Failed -> stringResource(
            when (state.reason) {
                FailReason.Network -> R.string.model_failed_network
                FailReason.Storage -> R.string.model_failed_storage
                FailReason.Checksum -> R.string.model_failed_checksum
            },
        )
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(detail)
                Text(
                    status,
                    color = if (state is PackState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state is PackState.Downloading || state is PackState.Verifying) {
                    val progress = (state as? PackState.Downloading)?.progress
                    if (progress != null) {
                        LinearWavyProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 8.dp))
                    } else {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        },
        trailingContent = {
            when (state) {
                PackState.Installed -> IconButton(onClick = { onRemove(pack) }) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
                }
                is PackState.Downloading, PackState.Verifying -> IconButton(onClick = { onRemove(pack) }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_cancel))
                }
                PackState.Missing, is PackState.Failed -> FilledTonalButton(onClick = { onDownload(pack) }) {
                    Text(stringResource(R.string.action_download))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}
