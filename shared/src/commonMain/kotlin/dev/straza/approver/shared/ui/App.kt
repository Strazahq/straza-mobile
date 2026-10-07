package dev.straza.approver.shared.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.straza.approver.shared.flow.ActivityOutcome
import dev.straza.approver.shared.flow.RenewalNotice
import dev.straza.approver.shared.flow.RenewalNotices
import dev.straza.approver.shared.flow.ArgsPreviewText
import dev.straza.approver.shared.flow.DecidedAttribution
import dev.straza.approver.shared.flow.ActivityScreen
import dev.straza.approver.shared.flow.ApprovalScreen
import dev.straza.approver.shared.flow.Expiry
import dev.straza.approver.shared.flow.PendingQueue
import dev.straza.approver.shared.flow.AuditStamp
import dev.straza.approver.shared.flow.ExecutionContext
import dev.straza.approver.shared.flow.RelativeTime
import dev.straza.approver.shared.net.ArgsPreview
import dev.straza.approver.shared.net.PendingRequest
import dev.straza.approver.shared.net.ResolvedRequest
import dev.straza.approver.shared.net.ResolvedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.straza.approver.shared.protocol.ReasonValidation
import dev.straza.approver.shared.protocol.Verdict
import dev.straza.approver.shared.net.PushKind
import dev.straza.approver.shared.push.FcmStatus
import dev.straza.approver.shared.push.PushConsent
import dev.straza.approver.shared.push.PushRouteStatus
import dev.straza.approver.shared.push.PushSettingsUi
import dev.straza.approver.shared.push.PushTransport
import dev.straza.approver.shared.push.PushTransportPref

private const val WORDMARK = "STRAZA"
private const val TAGLINE = "MOBILE APPROVER"

// The compass-shield mark on its 32-unit grid.
private const val SHIELD_PATH =
    "M7.4 5 H24.6 A1.4 1.4 0 0 1 26 6.4 V15.2 C26 22.1 21.7 26.9 16 29.1 C10.3 26.9 6 22.1 6 15.2 V6.4 A1.4 1.4 0 0 1 7.4 5 Z"
private const val ROSE_PATH =
    "M16 7 L17.11 12.52 L19.68 11.52 L18.68 14.09 L24.2 15.2 L18.68 16.31 L19.68 18.88 L17.11 17.88 L16 23.4 L14.89 17.88 L12.32 18.88 L13.32 16.31 L7.8 15.2 L13.32 14.09 L12.32 11.52 L14.89 12.52 Z"

private val Mono = FontFamily.Monospace

/**
 * The whole UI. One rule governs every screen: an Approve control exists only
 * when the app can produce a signed decision the server will accept. An
 * unreachable server, a revoked enrollment, an in-flight decision or a closed
 * window removes the control.
 *
 * @param scanner the platform's QR viewfinder, rendered in place while scanning.
 * @param versionLabel the build's identity line ("Straza 0.1.3 (4) · play"),
 *   passed in because shared code cannot read BuildConfig or a plist. Blank
 *   hides the line.
 */
@Composable
fun App(
    state: UiState,
    actions: AppActions,
    scanner: @Composable () -> Unit = {},
    versionLabel: String = "",
) {
    StrazaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                when (state) {
                    is UiState.NeedsEnrollment -> when {
                        state.confirmReplace != null ->
                            ReplacePairingScreen(state.confirmReplace, actions)
                        state.scanning -> ScannerScreen(state, actions, scanner)
                        else -> EnrollmentScreen(state, actions, versionLabel)
                    }

                    is UiState.Approvals -> when {
                        // The provider-choice ask outranks every other screen: its
                        // answer decides what the phone registers, and with whom.
                        state.askPushOptIn != null -> PushOptInScreen(state.askPushOptIn, actions)
                        state.confirmRemove != null ->
                            RemovePairingScreen(state.confirmRemove, actions)
                        state.selected != null -> DecisionScreen(state.selected, state, actions)
                        state.pushSettings != null ->
                            NotificationSettingsScreen(state.pushSettings, state, actions, versionLabel)
                        else -> ApprovalsScreen(state, actions)
                    }
                }
                // Cold-start brand intro, layered above the content until it fades.
                var introShown by rememberSaveable { mutableStateOf(false) }
                if (!introShown) {
                    StartupSplash(onFinished = { introShown = true })
                }
            }
        }
    }
}

// Shared marks and icons, drawn so there is no icon dependency.

/**
 * The Straza mark: a compass rose knocked out of a shield. The amber is a brand
 * constant, not a themed token, so it is the same in light and dark.
 */
@Composable
private fun ShieldMark(width: Dp) {
    val amber = Color(0xFFF6B63C)
    val ink = Color(0xFF0B0D12)
    // Parsed once per composition, not on every draw.
    val shield = remember { PathParser().parsePathString(SHIELD_PATH).toPath() }
    val rose = remember { PathParser().parsePathString(ROSE_PATH).toPath() }
    Canvas(Modifier.size(width)) {
        val u = size.width / 32f
        scale(u, u, pivot = Offset.Zero) {
            drawPath(shield, amber)
            drawPath(rose, ink)
        }
    }
}

/**
 * Cold-start brand intro, about 1.5 s. Plays once per process: the
 * [rememberSaveable] flag in [App] survives configuration changes. A tap skips it.
 */
@Composable
private fun StartupSplash(onFinished: () -> Unit) {
    val amber = Color(0xFFF6B63C)
    val ink = Color(0xFF0B0D12)
    val bg = MaterialTheme.colorScheme.background
    val onSurface = MaterialTheme.colorScheme.onSurface
    val faint = MaterialTheme.colorScheme.onSurfaceVariant

    val shieldScale = remember { Animatable(0.72f) }
    val shieldAlpha = remember { Animatable(0f) }
    val roseSweep = remember { Animatable(142f) } // degrees; settles to 0 = north
    val pulse = remember { Animatable(0f) }
    val wordAlpha = remember { Animatable(0f) }
    val cover = remember { Animatable(1f) }
    // Parsed once: the splash redraws every animation frame.
    val shieldPath = remember { PathParser().parsePathString(SHIELD_PATH).toPath() }
    val rosePath = remember { PathParser().parsePathString(ROSE_PATH).toPath() }

    LaunchedEffect(Unit) {
        launch { shieldAlpha.animateTo(1f, tween(440)) }
        launch { shieldScale.animateTo(1f, spring(dampingRatio = 0.52f, stiffness = 200f)) }
        roseSweep.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 150f))
        launch { pulse.animateTo(1f, tween(900)) }
        wordAlpha.animateTo(1f, tween(380))
        delay(480)
        cover.animateTo(0f, tween(480))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(cover.value)
            .background(bg)
            .pointerInput(Unit) { detectTapGestures { onFinished() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(124.dp)) {
                val u = size.width / 32f
                val pivot = Offset(16f, 15.2f) // rose/shield centre on the 32-grid
                val p = pulse.value
                if (p > 0f) {
                    // A soft amber halo expands from the mark and fades.
                    drawCircle(
                        amber.copy(alpha = (1f - p) * 0.34f),
                        radius = (9f + p * 6f) * u,
                        center = Offset(pivot.x * u, pivot.y * u),
                    )
                }
                scale(u, u, pivot = Offset.Zero) {
                    scale(shieldScale.value, shieldScale.value, pivot = pivot) {
                        drawPath(shieldPath, amber.copy(alpha = shieldAlpha.value))
                        rotate(roseSweep.value, pivot = pivot) {
                            drawPath(rosePath, ink.copy(alpha = shieldAlpha.value))
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                WORDMARK,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 5.sp,
                color = onSurface,
                modifier = Modifier.alpha(wordAlpha.value),
            )
            Spacer(Modifier.height(7.dp))
            Text(
                TAGLINE,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.5.sp,
                color = onSurface.copy(alpha = 0.7f),
                modifier = Modifier.alpha(wordAlpha.value),
            )
        }
    }
}

/** The outcome glyph: check, struck circle or clock. */
private fun DrawScope.statusGlyph(state: ResolvedState, tint: Color) {
    val w = size.width
    val sw = w * 0.13f
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (state) {
        // The path depends on the draw size, so it cannot be remembered.
        ResolvedState.APPROVED -> drawPath(
            Path().apply {
                moveTo(w * 0.20f, w * 0.54f)
                lineTo(w * 0.42f, w * 0.74f)
                lineTo(w * 0.80f, w * 0.30f)
            },
            tint, style = stroke,
        )
        ResolvedState.DENIED -> {
            drawCircle(tint, radius = w * 0.34f, center = center, style = Stroke(sw))
            drawLine(tint, Offset(w * 0.31f, w * 0.31f), Offset(w * 0.69f, w * 0.69f), strokeWidth = sw, cap = StrokeCap.Round)
        }
        else -> {
            drawCircle(tint, radius = w * 0.34f, center = center, style = Stroke(sw))
            drawLine(tint, center, Offset(center.x, w * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(tint, center, Offset(w * 0.66f, w * 0.55f), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun StatusIcon(state: ResolvedState, tint: Color, size: Dp = 13.dp) {
    Canvas(Modifier.size(size)) { statusGlyph(state, tint) }
}

/** A small clock, for the countdown chip. */
@Composable
private fun ClockIcon(tint: Color, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.12f
        drawCircle(tint, radius = w * 0.40f, center = center, style = Stroke(sw))
        drawLine(tint, center, Offset(center.x, w * 0.22f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, center, Offset(w * 0.68f, w * 0.55f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** A right chevron, marking a pending card as one that opens a screen. */
@Composable
private fun Chevron(tint: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.12f
        drawLine(tint, Offset(w * 0.40f, w * 0.28f), Offset(w * 0.64f, w * 0.5f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.64f, w * 0.5f), Offset(w * 0.40f, w * 0.72f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** A fingerprint hint: concentric arcs. */
@Composable
private fun FingerprintIcon(tint: Color, size: Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.08f
        for (i in 0..2) {
            val r = w * (0.16f + i * 0.13f)
            drawArc(
                tint, startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
    }
}

// Shared components

/** The app header: shield mark, wordmark and a refresh control. */
@Composable
private fun Header(
    onRefresh: (() -> Unit)?,
    refreshEnabled: Boolean = true,
    deploymentName: String? = null,
    deployments: List<DeploymentSummary> = emptyList(),
    activeProjectId: String? = null,
    onSwitch: (String) -> Unit = {},
    onAdd: () -> Unit = {},
    onExport: (() -> Unit)? = null,
    onNotifications: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 12.dp),
    ) {
        // Brand row: small and muted, so the deployment name below leads.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShieldMark(width = 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                WORDMARK,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (onRefresh != null) {
                // offset(7dp) keeps the 34dp visual box right-aligned with the
                // 18dp content edge while its 48dp touch target runs to the
                // screen edge.
                IconAffordance(
                    label = "Refresh",
                    enabled = refreshEnabled,
                    onClick = onRefresh,
                    modifier = Modifier.offset(x = if (onExport == null && onNotifications == null && onRemove == null) 7.dp else 0.dp),
                ) {
                    RefreshIcon(MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Overflow menu for the utility actions.
            if (onExport != null || onNotifications != null || onRemove != null) {
                var moreOpen by remember { mutableStateOf(false) }
                Box(Modifier.offset(x = 7.dp)) {
                    IconAffordance(label = "More options", onClick = { moreOpen = true }) {
                        OverflowIcon(MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        if (onNotifications != null) {
                            DropdownMenuItem(
                                text = { Text("Push service") },
                                onClick = { moreOpen = false; onNotifications() },
                            )
                        }
                        if (onExport != null) {
                            DropdownMenuItem(
                                text = { Text("Export activity (CSV)") },
                                leadingIcon = { ExportIcon(MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp) },
                                onClick = { moreOpen = false; onExport() },
                            )
                        }
                        // The destructive entry sits last, in the danger colour,
                        // and only opens a confirmation.
                        if (onRemove != null) {
                            DropdownMenuItem(
                                text = { Text("Remove this deployment", color = StrazaTheme.status.deniedFg) },
                                onClick = { moreOpen = false; onRemove() },
                            )
                        }
                    }
                }
            }
        }

        // A null name (not enrolled yet) falls back to the tagline, so "Add"
        // cannot appear before there is a deployment to add to.
        if (deploymentName != null) {
            Spacer(Modifier.height(11.dp))
            DeploymentBar(deploymentName, deployments, activeProjectId, onSwitch, onAdd)
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                TAGLINE,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The deployment strip: a beacon, the active deployment as a switcher chip, and
 * an "Add" pill. The chip opens the menu even with a single deployment.
 */
@Composable
private fun DeploymentBar(
    deploymentName: String,
    deployments: List<DeploymentSummary>,
    activeProjectId: String?,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(percent = 50)),
        )
        Spacer(Modifier.width(10.dp))
        Box {
            // A real control: Button role, a click label naming the action and a
            // 48dp touch minimum. The visible chip inside stays compact.
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = "Show deployments") { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        deploymentName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 168.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    // Decorative: the chip's click label announces the action,
                    // and the glyph alone would be read as a shape name.
                    Text(
                        "▾",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                deployments.forEach { d ->
                    val isActive = d.projectId == activeProjectId
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isActive) "${d.name}  ✓" else d.name,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = { menuOpen = false; onSwitch(d.projectId) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Add deployment", color = MaterialTheme.colorScheme.primary) },
                    onClick = { menuOpen = false; onAdd() },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        AddDeploymentPill(onClick = onAdd)
    }
}

/**
 * The "enroll another deployment" pill. The tappable node around the visual is
 * 48dp tall and carries a full label, because "+ Add" alone tells TalkBack
 * nothing about what is added.
 */
@Composable
private fun AddDeploymentPill(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Add deployment" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(percent = 50))
                .padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("+", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("Add", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * A header icon control. The 34dp bordered box is the visual; the tappable node
 * around it is 48dp and carries [label], because a Canvas glyph announces
 * nothing and 34dp is below the platform touch minimum.
 */
@Composable
private fun IconAffordance(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun RefreshIcon(tint: Color, size: Dp = 17.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.11f
        val r = w * 0.32f
        drawArc(
            tint, startAngle = 60f, sweepAngle = 260f, useCenter = false,
            topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // arrowhead
        val a = Offset(center.x + r * 0.5f, center.y - r * 0.86f)
        drawLine(tint, a, a + Offset(w * 0.14f, w * 0.02f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, a, a + Offset(-w * 0.02f, w * 0.14f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** An upload/share glyph: an arrow rising out of an open tray. */
@Composable
private fun ExportIcon(tint: Color, size: Dp = 17.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val sw = w * 0.11f
        // up arrow
        drawLine(tint, Offset(cx, h * 0.60f), Offset(cx, h * 0.20f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(cx, h * 0.20f), Offset(cx - w * 0.13f, h * 0.36f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(cx, h * 0.20f), Offset(cx + w * 0.13f, h * 0.36f), strokeWidth = sw, cap = StrokeCap.Round)
        // open tray
        val left = w * 0.24f
        val right = w * 0.76f
        val top = h * 0.56f
        val bottom = h * 0.82f
        drawLine(tint, Offset(left, top), Offset(left, bottom), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(right, top), Offset(right, bottom), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(left, bottom), Offset(right, bottom), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** The overflow glyph: three vertical dots. */
@Composable
private fun OverflowIcon(tint: Color, size: Dp = 17.dp) {
    Canvas(Modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val r = this.size.width * 0.085f
        val gap = this.size.height * 0.26f
        drawCircle(tint, radius = r, center = Offset(cx, cy - gap))
        drawCircle(tint, radius = r, center = Offset(cx, cy))
        drawCircle(tint, radius = r, center = Offset(cx, cy + gap))
    }
}

/**
 * The blocking count on the To-decide tab, in amber: an AI agent is waiting.
 * Day-scale tickets are not counted here.
 */
@Composable
private fun CountPill(n: Int) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(7.dp)) {
        Text(
            "$n", fontSize = 11.sp, fontFamily = Mono, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
            // The two count pills differ only visually, which a screen reader
            // cannot see: without a description TalkBack reads two bare numbers.
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .semantics { contentDescription = "$n blocking" },
        )
    }
}

/** The open-ticket count. Not amber: tickets are work, not an interruption. */
@Composable
private fun QuietCountPill(n: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(7.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            "$n", fontSize = 11.sp, fontFamily = Mono, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // See CountPill.
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .semantics { contentDescription = if (n == 1) "1 ticket" else "$n tickets" },
        )
    }
}

/** The remaining decision window, as a chip that turns urgent near zero. */
@Composable
private fun CountdownChip(expiresAt: Long?, nowEpochSeconds: Long, isTicket: Boolean = false) {
    // A ticket counts down a day-scale window: coarse label, no seconds, amber
    // only inside the final 24h. A hold keeps the per-second label and goes red
    // near expiry, because a hold timing out is a denial.
    val label = (if (isTicket) Expiry.coarseLabel(expiresAt, nowEpochSeconds)
        else Expiry.label(expiresAt, nowEpochSeconds)) ?: return
    val expired = Expiry.isExpired(expiresAt, nowEpochSeconds)
    val urgent = if (isTicket) Expiry.isTicketUrgent(expiresAt, nowEpochSeconds)
        else expired || Expiry.isUrgent(expiresAt, nowEpochSeconds)
    val status = StrazaTheme.status
    // Calm is the brand amber on its 13% tint. Urgency uses the semantic hues.
    val (content, bg) = when {
        isTicket && (urgent || expired) -> status.expiredFg to status.expiredBg // amber warn
        !isTicket && urgent -> status.deniedFg to status.deniedBg               // hold: red
        else -> MaterialTheme.colorScheme.primary to
            MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClockIcon(content)
        Text(
            if (expired) "expired" else label,
            fontSize = 13.sp, fontFamily = Mono, fontWeight = FontWeight.SemiBold, color = content,
        )
    }
}

/**
 * Names the approval class: `⧗ TICKET` (day-scale) or `● BLOCKING` (an AI agent
 * is frozen on the call). Slate in both cases: a class is a category, and
 * colour is reserved for trust states.
 */
@Composable
private fun ClassBadge(isTicket: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 2.5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (isTicket) "⧗" else "●", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (isTicket) "TICKET" else "BLOCKING",
            fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The filled amber button. */
@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/** The outlined secondary button. */
@Composable
private fun GhostButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/** Deny is an outline with danger-coloured content. Red is not used as a fill. */
@Composable
private fun DenyButton(enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = StrazaTheme.status.deniedFg),
    ) { Text("Deny", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/** A centered state: loading, empty, fail-closed or re-pair, with an optional action. */
@Composable
private fun StateBlock(
    title: String,
    body: String,
    loading: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
        Text(
            body, fontSize = 13.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 300.dp),
        )
        if (loading) {
            Spacer(Modifier.height(4.dp))
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
        action?.let { Spacer(Modifier.height(4.dp)); it() }
    }
}

/**
 * The re-pair screen for a trust failure. It is the one fail-closed state that
 * offers an action, because a stale pin does not heal by waiting.
 */
@Composable
private fun UntrustedBlock(reason: String, enabled: Boolean, onReEnroll: () -> Unit) {
    StateBlock(
        title = "This isn't the server you enrolled with",
        body = "Straza answered, but with a different certificate than the one this device " +
            "was paired to. That could be a rotated certificate, or an intercepted connection. " +
            "Nothing is approved either way. Pair again with a fresh code.\n\n($reason)",
        action = { PrimaryButton("Pair this device again", enabled = enabled, onClick = onReEnroll) },
    )
}

/**
 * The pre-expiry nudge, shown before the server starts answering 401. Its one
 * action is the same renewal the RenewNeeded screen offers.
 */
@Composable
private fun RenewSoonBanner(notice: RenewalNotice, enabled: Boolean, onRenew: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Pairing renews soon", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(notice.body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRenew, enabled = enabled) { Text("Renew now") }
        }
    }
}

/** A transient result banner ("Recorded · approved"). */
@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

// Enrollment

@Composable
private fun EnrollmentScreen(
    state: UiState.NeedsEnrollment,
    actions: AppActions,
    versionLabel: String = "",
) {
    var payload by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 26.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        ShieldMark(width = 68.dp)
        Text("Pair this device", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Scan the enrollment code your Straza console shows. It carries the server " +
                "address and the certificate this app will trust.",
            fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
            modifier = Modifier.widthIn(max = 320.dp),
        )

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            if (state.busy) "Enrolling…" else "Scan enrollment code",
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = actions::startScan,
        )
        TextButton(onClick = { manual = !manual }, enabled = !state.busy) {
            Text(if (manual) "Hide manual entry" else "Enter the code manually")
        }

        if (manual) {
            OutlinedTextField(
                value = payload,
                onValueChange = { payload = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Enrollment code") },
                enabled = !state.busy,
                minLines = 3,
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
            )
            GhostButton(
                "Enroll",
                enabled = !state.busy && payload.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = { actions.enroll(payload) },
            )
        }

        // A failed "add" lands here while other pairings still work, so the
        // way back to them stays on screen.
        if (state.hasDeployments) {
            TextButton(onClick = actions::stopScan, enabled = !state.busy) {
                Text("Back to deployments")
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "The signing key is created in this phone's secure hardware and never leaves it. " +
                "If this phone is lost, an admin can revoke the pairing from the Straza " +
                "console at any time.",
            fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
            modifier = Modifier.widthIn(max = 320.dp),
        )
        VersionFooter(versionLabel)
    }
}

/** The build identity line. A blank label renders nothing. */
@Composable
private fun VersionFooter(label: String) {
    if (label.isBlank()) return
    Text(
        label,
        fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Mono,
        color = StrazaTheme.faint, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

/**
 * The confirmation behind [UiState.NeedsEnrollment.confirmReplace]. Keeping the
 * existing pairing is the prominent action.
 */
@Composable
private fun ReplacePairingScreen(prompt: ReplacePrompt, actions: AppActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 26.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShieldMark(width = 68.dp)
        Text(
            "Replace an existing pairing?",
            fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "The scanned code identifies itself as \"${prompt.claimedName}\". This phone " +
                "is already paired with that deployment as \"${prompt.existingName}\".",
            fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Text(
            "Replacing destroys the existing pairing's signing key on this phone. If this code " +
                "did not come from that deployment's own console, keep the existing pairing.",
            fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            "Keep existing pairing",
            modifier = Modifier.fillMaxWidth(),
            onClick = actions::cancelReplace,
        )
        OutlinedButton(
            onClick = actions::confirmReplace,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = StrazaTheme.status.deniedFg),
        ) { Text("Replace pairing", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/**
 * The confirmation behind [UiState.Approvals.confirmRemove]. Removal cannot be
 * undone without a fresh enrollment code, so keeping the deployment is the
 * prominent action.
 */
@Composable
private fun RemovePairingScreen(deploymentName: String, actions: AppActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 26.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShieldMark(width = 68.dp)
        Text(
            "Remove this deployment?",
            fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "This phone is paired with \"$deploymentName\". Removing it destroys the " +
                "pairing's signing key on this phone and stops approvals and " +
                "notifications for this deployment.",
            fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Text(
            "This phone also tells the deployment to forget it; if that call fails " +
                "(offline, or an older server) the console keeps its record until an " +
                "admin revokes it there. Pairing this phone again needs a freshly " +
                "generated enrollment code.",
            fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            "Keep this deployment",
            modifier = Modifier.fillMaxWidth(),
            onClick = actions::cancelRemoveDeployment,
        )
        OutlinedButton(
            onClick = actions::confirmRemoveDeployment,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = StrazaTheme.status.deniedFg),
        ) { Text("Remove deployment", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ScannerScreen(state: UiState.NeedsEnrollment, actions: AppActions, scanner: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF05070C))) {
        scanner()

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ReticleCorners()
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Point at the enrollment code",
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFEDEFF4), textAlign = TextAlign.Center,
            )
            state.error?.let { Text(it, color = StrazaTheme.status.deniedFg, fontSize = 13.sp, textAlign = TextAlign.Center) }
            OutlinedButton(
                onClick = actions::stopScan, enabled = !state.busy, shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEDEFF4)),
            ) { Text("Cancel") }
            // Manual entry stays reachable from the viewfinder, so a camera that
            // will not focus or an emulator cannot dead-end the pairing.
            TextButton(onClick = actions::enterCodeManually, enabled = !state.busy) {
                Text("Enter the code manually", color = Color(0xFFEDEFF4), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        if (state.busy) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xB805070C)),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                Text("Enrolling this device…", color = Color(0xFFEDEFF4), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ReticleCorners() {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "scan")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanY",
    )
    Canvas(Modifier.size(196.dp)) {
        val len = size.width * 0.17f
        val sw = 3.dp.toPx()
        val corners = listOf(
            Offset(0f, 0f) to listOf(Offset(len, 0f), Offset(0f, len)),
            Offset(size.width, 0f) to listOf(Offset(size.width - len, 0f), Offset(size.width, len)),
            Offset(0f, size.height) to listOf(Offset(len, size.height), Offset(0f, size.height - len)),
            Offset(size.width, size.height) to listOf(Offset(size.width - len, size.height), Offset(size.width, size.height - len)),
        )
        corners.forEach { (o, ends) -> ends.forEach { drawLine(accent, o, it, strokeWidth = sw, cap = StrokeCap.Round) } }

        // The sweeping line, with a soft leading glow.
        val y = size.height * (0.07f + 0.86f * t)
        val x0 = size.width * 0.10f
        val x1 = size.width * 0.90f
        drawLine(accent.copy(alpha = 0.18f), Offset(x0, y), Offset(x1, y), strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
        drawLine(accent, Offset(x0, y), Offset(x1, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

// Approvals: header, tabs, Decide and Activity

@Composable
private fun ApprovalsScreen(state: UiState.Approvals, actions: AppActions) {
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Export is offered only on Activity, and only when the feed has rows.
        val canExport = state.tab == ApprovalTab.Activity &&
            (state.activity as? ActivityScreen.Resolved)?.items?.isNotEmpty() == true
        Header(
            onRefresh = actions::refresh,
            refreshEnabled = !state.busy,
            deploymentName = state.deploymentName,
            deployments = state.deployments,
            activeProjectId = state.activeProjectId,
            onSwitch = actions::switchDeployment,
            onAdd = actions::addDeployment,
            onExport = if (canExport) actions::exportActivity else null,
            onNotifications = actions::openNotificationSettings,
            onRemove = if (state.deploymentName != null) actions::removeDeployment else null,
        )

        // The amber badge counts blocking work only; tickets go in the quiet pill.
        val queue = (state.screen as? ApprovalScreen.Pending)?.let { PendingQueue.of(it.requests) }
        TabBar(
            tab = state.tab,
            blockingCount = queue?.blocking?.size ?: 0,
            ticketCount = queue?.tickets?.size ?: 0,
            onSelect = actions::selectTab,
        )

        when (state.tab) {
            ApprovalTab.Decide -> DecideTab(state, actions)
            ApprovalTab.Activity -> ActivityTab(state, actions)
        }
    }
}

/** The segmented tab strip: an outlined plate with the selected tab as a raised card. */
@Composable
private fun TabBar(tab: ApprovalTab, blockingCount: Int, ticketCount: Int, onSelect: (ApprovalTab) -> Unit) {
    Row(
        // selectableGroup here plus Role.Tab on each item: TalkBack announces
        // "tab, selected, 1 of 2" instead of an anonymous clickable.
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        TabItem(
            "To decide", tab == ApprovalTab.Decide,
            count = blockingCount.takeIf { it > 0 },
            quietCount = ticketCount.takeIf { it > 0 },
            modifier = Modifier.weight(1f),
        ) { onSelect(ApprovalTab.Decide) }
        TabItem(
            "Activity", tab == ApprovalTab.Activity, count = null, quietCount = null,
            modifier = Modifier.weight(1f),
        ) { onSelect(ApprovalTab.Activity) }
    }
}

@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    count: Int?,
    quietCount: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .then(
                if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp))
                else Modifier
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        Text(
            label,
            fontSize = 14.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        count?.let { CountPill(it) }
        quietCount?.let { QuietCountPill(it) }
    }
}

// Decide

@Composable
private fun DecideTab(state: UiState.Approvals, actions: AppActions) {
    Column(Modifier.fillMaxSize()) {
        state.notice?.let {
            Box(Modifier.padding(16.dp)) { Notice(it, actions::dismissNotice) }
        }
        // The pre-expiry nudge shows only over a live list. The renewal,
        // suspension and outage states each carry their own message.
        if (state.screen is ApprovalScreen.Pending) {
            RenewalNotices.of(state.tokenExpiresAtEpochSeconds, state.nowEpochSeconds)?.let { nudge ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RenewSoonBanner(nudge, enabled = !state.busy, onRenew = actions::renewPairing)
                }
            }
        }
        when (val screen = state.screen) {
            ApprovalScreen.NotEnrolled, ApprovalScreen.Loading ->
                StateBlock("Loading", "Fetching what needs your decision…", loading = true)

            ApprovalScreen.Revoked ->
                StateBlock("Enrollment revoked", "This device can no longer approve requests. Ask an administrator to enroll it again.")

            // The token aged out but the hardware key did not, so renewal needs
            // no re-scan and destroys nothing.
            ApprovalScreen.RenewNeeded ->
                StateBlock(
                    "Pairing needs renewal",
                    "This deployment's pairing token has expired. Renewing re-authenticates with this " +
                        "phone's secure hardware key. Confirm with your fingerprint or screen lock. " +
                        "No new code is needed.",
                    action = {
                        PrimaryButton("Renew pairing", enabled = !state.busy, onClick = actions::renewPairing)
                    },
                )

            // Fail-closed but self-healing: the poll keeps running underneath,
            // so reactivation on the server restores the list.
            is ApprovalScreen.Suspended ->
                StateBlock("Pairing suspended", screen.reason)

            is ApprovalScreen.CannotReach ->
                StateBlock(
                    "Can't reach Straza",
                    "Approvals can't be decided while the server is unreachable. A request that times out is denied by the server, not approved.\n\n(${screen.reason})",
                    loading = true,
                )

            is ApprovalScreen.Untrusted ->
                UntrustedBlock(screen.reason, enabled = !state.busy, onReEnroll = actions::reEnroll)

            is ApprovalScreen.Pending -> {
                // Two lanes: blocking work has an AI agent frozen on the call and
                // a window measured in seconds, a ticket has neither. Server
                // order is preserved inside each lane.
                val queue = PendingQueue.of(screen.requests)
                if (queue.isEmpty) {
                    StateBlock("Nothing waiting", "You're all caught up. New requests appear here and buzz your phone.")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // The blocking header shows even at zero, to say that nothing
                        // is blocking. The ticket header is omitted when empty.
                        item(key = "h-blocking") {
                            QueueHeader(
                                "Blocking now",
                                count = queue.blocking.size,
                                note = if (queue.blocking.isEmpty()) "No agent is waiting on you right now." else null,
                            )
                        }
                        items(queue.blocking, key = { it.id }) { PendingCard(it, state.nowEpochSeconds) { actions.select(it) } }

                        if (queue.tickets.isNotEmpty()) {
                            item(key = "h-tickets") {
                                QueueHeader(
                                    "Tickets",
                                    count = queue.tickets.size,
                                    note = "The agent isn't waiting on these. Decide within the window.",
                                )
                            }
                            items(queue.tickets, key = { it.id }) { PendingCard(it, state.nowEpochSeconds) { actions.select(it) } }
                        }
                    }
                }
            }
        }
    }
}

/** A lane header on the Decide queue: the class name, its count and a one-line note. */
@Composable
private fun QueueHeader(label: String, count: Int, note: String? = null) {
    Column(
        modifier = Modifier.padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                label.uppercase(),
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "$count", fontSize = 11.sp, fontFamily = Mono, fontWeight = FontWeight.SemiBold,
                color = StrazaTheme.faint,
            )
        }
        note?.let {
            Text(it, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = StrazaTheme.faint)
        }
    }
}

/** A pending request, as a tappable card that opens the decision screen. */
@Composable
private fun PendingCard(request: PendingRequest, nowEpochSeconds: Long, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // No class badge here: the lane header names the class, and a
                // badge would take width from the title. The title is the
                // humanized summary; the wire identity is on the decision screen.
                Text(
                    request.toolTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                CountdownChip(request.expiresAtEpochSeconds, nowEpochSeconds, isTicket = request.isTicket)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    request.requester, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // The box takes the slack, so a long rule id ellipsizes inside
                // its plate while the chevron stays at the row's end.
                Box(Modifier.weight(1f)) { MetaChip(request.ruleId) }
                Chevron(MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Small mono metadata on a `surfaceVariant` plate. For rule ids and similar, not for prose. */
@Composable
private fun MetaChip(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Text(
        text,
        fontSize = 13.sp, fontFamily = Mono, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// Activity: timeline, filters and date groups

@Composable
private fun ActivityTab(state: UiState.Approvals, actions: AppActions) {
    Column(Modifier.fillMaxSize()) {
        // Header actions (export, push settings) fire from either tab, so the
        // notice renders on both.
        state.notice?.let {
            Box(Modifier.padding(16.dp)) { Notice(it, actions::dismissNotice) }
        }
        ActivityTabBody(state, actions)
    }
}

@Composable
private fun ActivityTabBody(state: UiState.Approvals, actions: AppActions) {
    when (val screen = state.activity) {
        ActivityScreen.Loading -> StateBlock("Loading", "Fetching recent activity…", loading = true)

        ActivityScreen.Revoked ->
            StateBlock("Enrollment revoked", "This device can no longer reach Straza. Ask an administrator to enroll it again.")

        is ActivityScreen.CannotReach ->
            StateBlock("Can't reach Straza", "Recent activity can't be shown while the server is unreachable.\n\n(${screen.reason})", loading = true)

        is ActivityScreen.Untrusted -> UntrustedBlock(screen.reason, enabled = !state.busy, onReEnroll = actions::reEnroll)

        is ActivityScreen.Resolved -> {
            val all = screen.items
            val shown = state.activityFilter?.let { f -> all.filter { it.state == f } } ?: all
            Column(Modifier.fillMaxSize()) {
                // One row open at a time. Hoisted above the rows so it survives
                // LazyColumn recycling, and saveable so it survives rotation.
                var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
                FilterChips(all, state.activityFilter, actions::filterActivity)
                if (shown.isEmpty()) {
                    StateBlock("Nothing here", "No ${state.activityFilter?.let { label(it).lowercase() } ?: "resolved"} requests to show.")
                } else {
                    val groups = groupByBucket(shown, state.nowEpochSeconds)
                    val listState = rememberLazyListState()
                    // Opening a row scrolls it to the top of the list. Otherwise
                    // collapsing an open row above shifts the tapped row up and
                    // its detail can land below the fold. The index follows the
                    // LazyColumn's emission order: header, then rows, per bucket.
                    val flatKeys = remember(groups) { groups.flatMap { (b, rows) -> listOf("h-$b") + rows.map { it.id } } }
                    LaunchedEffect(expandedId, flatKeys) {
                        val idx = expandedId?.let { flatKeys.indexOf(it) } ?: -1
                        if (idx >= 0) listState.animateScrollToItem(idx)
                    }
                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                        groups.forEach { (bucket, rows) ->
                            item(key = "h-$bucket") { GroupHeader(bucket) }
                            items(rows, key = { it.id }) { item ->
                                TimelineRow(
                                    item, state.nowEpochSeconds, state.thisDeviceId,
                                    first = item == rows.first(), last = item == rows.last(),
                                    expanded = expandedId == item.id,
                                    onToggle = { expandedId = if (expandedId == item.id) null else item.id },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChips(all: List<ResolvedRequest>, selected: ResolvedState?, onFilter: (ResolvedState?) -> Unit) {
    val counts = all.groupingBy { it.state }.eachCount()
    Row(
        // selectableGroup plus per-chip selected semantics: TalkBack announces
        // "Approved, 3, selected" instead of four anonymous clickables.
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Chip("All", all.size, selected == null) { onFilter(null) }
        listOf(ResolvedState.APPROVED, ResolvedState.DENIED, ResolvedState.EXPIRED).forEach { st ->
            Chip(label(st), counts[st] ?: 0, selected == st) { onFilter(if (selected == st) null else st) }
        }
    }
}

@Composable
private fun Chip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        // The 48dp touch minimum is on this outer node; the bordered pill
        // below is the visual. Role.Checkbox fits a filter that toggles, as
        // in Material's FilterChip.
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(11.dp))
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
                .border(1.dp, if (selected) accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(11.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            Text("$count", fontSize = 11.5.sp, fontFamily = Mono, fontWeight = FontWeight.SemiBold, color = if (selected) accent else StrazaTheme.faint)
        }
    }
}

@Composable
private fun GroupHeader(bucket: String) {
    Text(
        bucket.uppercase(),
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
    )
}

/**
 * A resolved row on the timeline. Tapping expands it to show the rule and the
 * agent's stated reason. Read-only: nothing here leads to a decision.
 */
@Composable
private fun TimelineRow(
    item: ResolvedRequest,
    nowEpochSeconds: Long,
    thisDeviceId: String?,
    first: Boolean,
    last: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    // A ticket's `state` stays approved after consumption, so the outcome shown
    // is derived (used, grant active, unused), not read off `state`.
    val outcome = ActivityOutcome.of(item, nowEpochSeconds)
    val color = outcomeColor(outcome)
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(16.dp).fillMaxHeight()) {
            RailAndNode(outcomeFilled(outcome), color, first, last)
        }
        Column(
            // No ripple: this column expands on tap, so the indication's bounds
            // would jump to the full detail height mid-animation, which draws
            // as a large flash on iOS. The expansion and the caret flip are the
            // click feedback.
            Modifier.weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                )
                .padding(vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    item.toolTitle, fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                RelativeTime.label(item.decidedAtEpochSeconds, nowEpochSeconds)?.let {
                    Text(it, fontSize = 13.sp, fontFamily = Mono, fontWeight = FontWeight.Medium, color = StrazaTheme.faint)
                }
                Text(if (expanded) "▴" else "▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusIcon(outcomeGlyphState(outcome), color, size = 13.dp)
                Text(outcomeLabel(outcome), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
                Text(
                    activitySubline(item, outcome, nowEpochSeconds, thisDeviceId), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (expanded) ActivityDetail(item, outcome, nowEpochSeconds, thisDeviceId)
        }
    }
}

/**
 * The expanded detail of a resolved row. The agent's stated reason keeps the
 * unverified-claim framing of the decision screen.
 */
@Composable
private fun ActivityDetail(item: ResolvedRequest, outcome: ActivityOutcome, nowEpochSeconds: Long, thisDeviceId: String?) {
    Column(Modifier.padding(top = 7.dp, bottom = 2.dp, end = 2.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // The wire identity, which the collapsed row's humanized title omits.
        // The console, Slack and the audit record use this string.
        if (item.toolWire.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("TOOL", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.toolWire, fontSize = 13.sp, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("RULE", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.ruleId.ifBlank { "–" }, fontSize = 13.sp, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurface)
        }
        // Local wall time plus UTC offset, so the row reconciles against the
        // server's audit record without timezone arithmetic.
        AuditStamp.local(item.decidedAtEpochSeconds)?.let {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("DECIDED", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it, fontSize = 13.sp, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        // The agent session that raised it, when the server sent one.
        ExecutionContext.line(item.sessionId, item.harness)?.let {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SESSION", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it, fontSize = 13.sp, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        item.argsPreview?.let { ParamsBlock(it) }
        // The use window: a ticket's grant, or a hold's short retry window on
        // servers that send one.
        if (item.grantExpiresAtEpochSeconds != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (item.isTicket) "GRANT" else "RUN", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when (outcome) {
                        ActivityOutcome.USED -> "Used" + (item.consumedBy?.let { " by $it" } ?: "") +
                            (RelativeTime.label(item.consumedAtEpochSeconds, nowEpochSeconds)?.let { " · $it" } ?: "")
                        ActivityOutcome.GRANT_ACTIVE ->
                            "Active · agent can act " + (Expiry.coarseLabel(item.grantExpiresAtEpochSeconds, nowEpochSeconds) ?: "")
                        ActivityOutcome.RAN_ONCE -> "Ran once" + (item.consumedBy?.let { " · session $it" } ?: "") +
                            (RelativeTime.label(item.consumedAtEpochSeconds, nowEpochSeconds)?.let { " · $it" } ?: "")
                        ActivityOutcome.RUN_PENDING ->
                            "Not run yet · retry window " + (Expiry.label(item.grantExpiresAtEpochSeconds, nowEpochSeconds) ?: "open")
                        ActivityOutcome.NOT_RUN -> "Not run · the retry window closed"
                        else -> "Grant window expired unused"
                    },
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (item.justification.isNotBlank()) {
            UnverifiedClaim(item.justification)
        } else {
            Text("No reason was recorded for this request.", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = StrazaTheme.faint)
        }
        // The decider's own words, kept apart from the agent's claim above.
        if (item.decidedReason.isNotBlank()) {
            DeciderReason(
                reason = item.decidedReason,
                decidedBy = item.decidedBy,
                surfaceTag = DecidedAttribution.surfaceTag(item.decidedViaSurface, item.decidedViaDeviceId, thisDeviceId),
            )
        }
        item.argsPreview?.let { ArgsMeta(it) }
    }
}

/**
 * The decider's recorded words, drawn as the visual opposite of
 * [UnverifiedClaim]: solid edge, amber attribution, upright text. A reason
 * longer than [REASON_FOLD_CHARS] or spanning several lines folds to a one-line
 * snippet with an explicit "Show all".
 */
@Composable
private fun DeciderReason(reason: String, decidedBy: String?, surfaceTag: String?) {
    var expandedReason by remember(reason) { mutableStateOf(false) }
    val folds = reason.length > REASON_FOLD_CHARS || '\n' in reason
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Reason recorded" + (decidedBy?.let { " by $it" } ?: ""),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    surfaceTag?.let {
                        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(6.dp)) {
                            Text(
                                it, fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (!folds || expandedReason) {
                    Text("\"$reason\"", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
                } else {
                    Text(
                        "\"" + reason.lineSequence().first(),
                        fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (folds) {
                    Text(
                        if (expandedReason) "Show less" else "Show all",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { expandedReason = !expandedReason }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** The fold threshold for a decider reason, the same one the approvals page uses. */
private const val REASON_FOLD_CHARS = 160

/**
 * The connector rail and node. The top half is hidden on the first item and
 * the bottom half on the last, so the line reads as a single timeline.
 */
@Composable
private fun RailAndNode(filled: Boolean, color: Color, first: Boolean, last: Boolean) {
    val line = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val nodeY = 24.dp.toPx()
        val sw = 1.5.dp.toPx()
        if (!first) drawLine(line, Offset(cx, 0f), Offset(cx, nodeY), strokeWidth = sw)
        if (!last) drawLine(line, Offset(cx, nodeY), Offset(cx, size.height), strokeWidth = sw)
        val r = 5.5.dp.toPx()
        // See outcomeFilled for what a filled node means.
        if (!filled) {
            drawCircle(surface, radius = r, center = Offset(cx, nodeY))
            drawCircle(color, radius = r, center = Offset(cx, nodeY), style = Stroke(width = r * 0.55f))
        } else {
            drawCircle(color.copy(alpha = 0.22f), radius = r * 1.7f, center = Offset(cx, nodeY))
            drawCircle(color, radius = r, center = Offset(cx, nodeY))
        }
    }
}

/**
 * The second line of an activity row: "nova · by kim · this phone", "nova ·
 * used by agent-7", "scout · ran once". The surface clause appears only on rows
 * that carry `decided_via` (openapi 0.63.0); older servers omit it.
 */
private fun activitySubline(
    item: ResolvedRequest,
    outcome: ActivityOutcome,
    nowEpochSeconds: Long,
    thisDeviceId: String?,
): String {
    val detail = when (outcome) {
        ActivityOutcome.USED -> "used" + (item.consumedBy?.let { " by $it" } ?: "")
        ActivityOutcome.GRANT_ACTIVE ->
            Expiry.coarseLabel(item.grantExpiresAtEpochSeconds, nowEpochSeconds)?.let { "agent can act · $it" }
                ?: "grant active"
        ActivityOutcome.UNUSED -> "grant expired · unused"
        ActivityOutcome.RAN_ONCE -> "ran once"
        ActivityOutcome.RUN_PENDING -> "not run yet"
        ActivityOutcome.NOT_RUN -> "not run"
        ActivityOutcome.EXPIRED -> "no human decision"
        ActivityOutcome.APPROVED, ActivityOutcome.DENIED, ActivityOutcome.UNKNOWN ->
            item.decidedBy?.let { name ->
                val clause = DecidedAttribution.surfaceClause(item.decidedViaSurface, item.decidedViaDeviceId, thisDeviceId)
                if (clause != null) "by $name · $clause" else "decided by $name"
            } ?: "resolved"
    }
    return listOfNotNull(item.requester.takeIf { it.isNotBlank() }, detail).joinToString(" · ")
}

// Decision detail

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DecisionScreen(request: PendingRequest, state: UiState.Approvals, actions: AppActions) {
    val expired = Expiry.isExpired(request.expiresAtEpochSeconds, state.nowEpochSeconds)
    // The optional decider reason. Keyed to the request id, so one request's
    // words cannot carry over to another. The draft leaves this screen only
    // through actions.decide, so what is signed is what was on screen.
    var reasonOpen by remember(request.id) { mutableStateOf(false) }
    var reasonDraft by remember(request.id) { mutableStateOf("") }
    // While the reason field is open, every draft change brings the button row
    // back into view, so the keyboard and a growing reason cannot push
    // Approve/Deny out of reach. It is a no-op when the row is already visible.
    val verbsInView = remember { BringIntoViewRequester() }
    LaunchedEffect(reasonOpen, reasonDraft) {
        if (reasonOpen) verbsInView.bringIntoView()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.select(null) }, enabled = !state.busy) { Text("←  Back") }
            Spacer(Modifier.weight(1f))
            ClassBadge(isTicket = request.isTicket)
        }

        Text(
            if (expired) "This request has expired" else "Approve this action?",
            fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountdownChip(request.expiresAtEpochSeconds, state.nowEpochSeconds, isTicket = request.isTicket)
            if (!expired) Text(
                if (request.isTicket) "to decide · times out if left undecided" else "left to decide · denied automatically at zero",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        KvCard {
            // The deployment this decision signs for, repeated from the header.
            state.deploymentName?.let {
                KvRow("DEPLOYMENT", it)
                KvDivider()
            }
            // Human words above, the wire identity beneath, as on the browser
            // approval card.
            KvRow("TOOL", request.toolTitle, wire = request.toolWire)
            KvDivider()
            RequesterKvRow(request.requester, request.requesterKind)
            // The agent session that is asking. Servers before openapi 0.69.0
            // omit it, and the row is then absent.
            ExecutionContext.line(request.sessionId, request.harness)?.let {
                KvDivider()
                KvRow("SESSION", it, mono = true)
            }
            KvDivider()
            KvRow("RULE", request.ruleId, mono = true)
        }

        // Facts first: the server's redacted call render, then the agent's
        // unverified claim, then the hash-binding and truncation notes.
        request.argsPreview?.let { ParamsBlock(it) }

        if (request.justification.isNotBlank()) UnverifiedClaim(request.justification)

        request.argsPreview?.let { ArgsMeta(it) }

        state.notice?.let { Notice(it, actions::dismissNotice) }

        when {
            state.busy -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }

            expired -> Surface(
                color = StrazaTheme.status.expiredBg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(ResolvedState.EXPIRED, StrazaTheme.status.expiredFg)
                    Text(
                        "Expired · denied on timeout. If the action is still needed it must be requested again.",
                        fontSize = 13.sp, color = StrazaTheme.status.expiredFg,
                    )
                }
            }

            else -> {
                AddReasonSection(
                    open = reasonOpen,
                    draft = reasonDraft,
                    onOpen = { reasonOpen = true },
                    onDraft = { reasonDraft = it },
                )
                Row(
                    Modifier.fillMaxWidth().bringIntoViewRequester(verbsInView),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    DenyButton(enabled = true, modifier = Modifier.weight(1f)) { actions.decide(request, Verdict.DENY, reasonDraft) }
                    PrimaryButton("Approve", modifier = Modifier.weight(1f)) { actions.decide(request, Verdict.APPROVE, reasonDraft) }
                }
                // A ticket approval opens a one-time grant window. Its length is
                // policy and unknown until approval, so the text names no duration.
                if (request.isTicket) {
                    Text(
                        "Approving opens a one-time window for the agent to act. Denying is final for this ticket's window.",
                        fontSize = 13.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FingerprintIcon(MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("You'll confirm with your fingerprint.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KvCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
private fun KvRow(label: String, value: String, mono: Boolean = false, wire: String = "") {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.0.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 7.dp, vertical = 2.5.dp),
        )
        Text(
            value, fontSize = if (mono) 14.sp else 14.5.sp, fontWeight = FontWeight.Medium,
            fontFamily = if (mono) Mono else FontFamily.Default, color = MaterialTheme.colorScheme.onSurface,
        )
        // The wire spelling under the human one, so the row can be matched
        // against the console, Slack and the audit record.
        if (wire.isNotBlank()) {
            Text(wire, fontSize = 12.5.sp, fontFamily = Mono, fontWeight = FontWeight.Medium, color = StrazaTheme.faint)
        }
    }
}

@Composable
private fun KvDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 14.dp))

/**
 * The REQUESTED BY row. A human subject reads "bob's agent, acting for bob". A
 * non-human subject keeps its plain name and gains a badge and a caption, worded
 * as on the server's approvals page. A server older than openapi 0.63.0 sends
 * no kind, and the row is then the plain name.
 */
@Composable
private fun RequesterKvRow(requester: String, kind: String) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            "REQUESTED BY", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.0.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 7.dp, vertical = 2.5.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                DecidedAttribution.requesterValue(requester, kind),
                fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
            )
            if (DecidedAttribution.isNhi(kind)) {
                Surface(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Text(
                        "NON-HUMAN IDENTITY", fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp, color = StrazaTheme.faint,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (DecidedAttribution.isNhi(kind)) {
            Text(
                DecidedAttribution.nhiCaption(requester),
                fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The optional decider reason: a labelled action that opens a text field. The
 * counter counts bytes, because the cap in [ReasonValidation] is in bytes and a
 * character count would be wrong for non-ASCII text.
 */
@Composable
private fun AddReasonSection(open: Boolean, draft: String, onOpen: () -> Unit, onDraft: (String) -> Unit) {
    if (!open) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            Text("+", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(
                "Add a reason",
                fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "(optional)",
                fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val bytes = ReasonValidation.utf8ByteLength(draft)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedTextField(
            value = draft,
            // Input past the byte cap is refused, not truncated: a cut would
            // sign a reason that differs from what the user saw.
            onValueChange = { new ->
                if (ReasonValidation.utf8ByteLength(new) <= ReasonValidation.MAX_BYTES) onDraft(new)
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text("Add a reason (optional)", fontSize = 13.5.sp) },
            minLines = 2,
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Recorded on the request, in your name.",
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (bytes >= REASON_COUNTER_AT) {
                Text(
                    "$bytes/${ReasonValidation.MAX_BYTES} bytes",
                    fontSize = 13.sp, fontFamily = Mono, fontWeight = FontWeight.Medium,
                    color = StrazaTheme.status.expiredFg,
                )
            }
        }
    }
}

/** The draft size, in bytes, at which the counter appears. */
private const val REASON_COUNTER_AT = 400

/**
 * The agent's stated reason, framed as an unverified claim: a dashed border and
 * no semantic colour, which is reserved for outcomes.
 */
@Composable
private fun UnverifiedClaim(text: String) {
    val edge = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .drawBehind {
                drawRoundRect(
                    edge, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                    cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                )
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("The requester's stated reason", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Surface(color = Color.Transparent, shape = RoundedCornerShape(6.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Text("UNVERIFIED", fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = StrazaTheme.faint, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
            }
        }
        Text("\"$text\"", fontSize = 13.5.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The server's redacted render of the call, shown verbatim: display safety is
 * the server's job and the app does not redact again. Wide lines scroll and do
 * not wrap. Previews longer than 12 lines collapse.
 */
@Composable
private fun ParamsBlock(args: ArgsPreview) {
    val preview = args.preview
    if (preview.isBlank()) return
    val total = preview.count { it == '\n' } + 1
    val collapsible = total > 12
    var expanded by rememberSaveable(args.preview) { mutableStateOf(false) }
    Surface(
        // The screen background, darker than the card: an inset well for code.
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            val shown = if (collapsible && !expanded) preview.lineSequence().take(12).joinToString("\n") else preview
            Text(
                shown,
                fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp, softWrap = false,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(13.dp),
            )
            if (collapsible) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text(
                    if (expanded) "▴  Show less" else "▾  Show all ($total lines)",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 13.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/**
 * The captions beneath the call render and the claim: what the hash binds
 * (keyed on `binding_scope`) and the truncation footnote.
 */
@Composable
private fun ArgsMeta(args: ArgsPreview) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            Text("ⓘ", fontSize = 13.sp, color = StrazaTheme.faint)
            Text(
                ArgsPreviewText.honestyLine(args.bindingScope, args.hashPrefix),
                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = StrazaTheme.faint, lineHeight = 17.sp,
            )
        }
        if (args.truncated) {
            Text(
                ArgsPreviewText.truncationFootnote(args.bytes),
                fontSize = 13.sp, fontStyle = FontStyle.Italic, color = StrazaTheme.faint, lineHeight = 17.sp,
            )
        }
    }
}

// Small helpers

private fun label(state: ResolvedState): String = when (state) {
    ResolvedState.APPROVED -> "Approved"
    ResolvedState.DENIED -> "Denied"
    ResolvedState.EXPIRED -> "Expired"
    ResolvedState.UNKNOWN -> "Resolved"
}

/**
 * The colour of a derived activity outcome: green for the approved family, red
 * for a denial, amber for a timeout. An unused approval is neutral slate, since
 * it is neither a failure nor a warning.
 */
@Composable
private fun outcomeColor(o: ActivityOutcome): Color = when (o) {
    ActivityOutcome.APPROVED, ActivityOutcome.USED, ActivityOutcome.GRANT_ACTIVE,
    ActivityOutcome.RAN_ONCE, ActivityOutcome.RUN_PENDING -> StrazaTheme.status.approvedFg
    ActivityOutcome.DENIED -> StrazaTheme.status.deniedFg
    ActivityOutcome.EXPIRED -> StrazaTheme.status.expiredFg
    ActivityOutcome.UNUSED, ActivityOutcome.NOT_RUN, ActivityOutcome.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun outcomeLabel(o: ActivityOutcome): String = when (o) {
    ActivityOutcome.APPROVED -> "Approved"
    ActivityOutcome.USED -> "Approved · used"
    ActivityOutcome.GRANT_ACTIVE -> "Approved · grant active"
    ActivityOutcome.UNUSED -> "Approved · unused"
    ActivityOutcome.RAN_ONCE -> "Approved · ran once"
    ActivityOutcome.RUN_PENDING -> "Approved · not run yet"
    ActivityOutcome.NOT_RUN -> "Approved · not run"
    ActivityOutcome.DENIED -> "Denied"
    ActivityOutcome.EXPIRED -> "Expired"
    ActivityOutcome.UNKNOWN -> "Resolved"
}

/**
 * Whether the timeline node is filled. Filled is a settled human decision; a
 * hollow ring is in progress or nothing happened.
 */
private fun outcomeFilled(o: ActivityOutcome): Boolean = when (o) {
    ActivityOutcome.USED, ActivityOutcome.RAN_ONCE, ActivityOutcome.APPROVED, ActivityOutcome.DENIED -> true
    else -> false
}

/** The status glyph that fits a derived outcome. */
private fun outcomeGlyphState(o: ActivityOutcome): ResolvedState = when (o) {
    ActivityOutcome.APPROVED, ActivityOutcome.USED, ActivityOutcome.GRANT_ACTIVE,
    ActivityOutcome.RAN_ONCE, ActivityOutcome.RUN_PENDING -> ResolvedState.APPROVED
    ActivityOutcome.DENIED -> ResolvedState.DENIED
    ActivityOutcome.EXPIRED -> ResolvedState.EXPIRED
    ActivityOutcome.UNUSED, ActivityOutcome.NOT_RUN, ActivityOutcome.UNKNOWN -> ResolvedState.UNKNOWN
}

/**
 * Date buckets from elapsed seconds against the device clock. Approximate, like
 * [RelativeTime]: it only labels a read-only record, so a skewed clock does not
 * affect anything actionable.
 */
private fun groupByBucket(items: List<ResolvedRequest>, nowEpochSeconds: Long): List<Pair<String, List<ResolvedRequest>>> {
    fun bucket(at: Long?): String {
        if (at == null) return "Earlier"
        val d = nowEpochSeconds - at
        return when {
            d < 86_400 -> "Today"
            d < 172_800 -> "Yesterday"
            else -> "Earlier"
        }
    }
    return items.groupBy { bucket(it.decidedAtEpochSeconds) }
        .toList()
        .sortedBy { listOf("Today", "Yesterday", "Earlier").indexOf(it.first) }
}

// Push service: the provider-choice ask and the settings screen. Push only
// speeds up the 15 s poll. Nothing on these screens can block deciding, and
// the server still denies on timeout in every state shown here.

/**
 * The first-start provider-choice ask. Nothing is preselected, and the one exit
 * is [AppActions.answerPushOptIn] with a chosen row: picking a provider grants
 * consent for that lane, "Polling only" declines.
 */
@Composable
private fun PushOptInScreen(facts: PushSettingsUi, actions: AppActions) {
    var choice by remember { mutableStateOf<PushTransportPref?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Choose a push provider",
            fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Push tells this phone the moment a request needs your decision. " +
                "It never carries the request itself, only that one is waiting.\n\n" +
                "Pick who delivers it. You can change this any time under " +
                "Push service; if a request times out undecided, the server " +
                "denies it either way.",
            fontSize = 13.5.sp, lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (facts.transportPickerAvailable) {
                ProviderRows(facts, selectedPref = choice, onSelect = { choice = it })
            } else {
                // iOS: Apple push is the one provider and polling the fallback.
                // AUTO stands for the platform's push, because iOS stores no
                // transport preference, only the consent the row expresses.
                TransportRow(
                    title = "Apple push",
                    subtitle = "Delivered through Apple (APNs), the only push service on iPhone.",
                    selected = choice == PushTransportPref.AUTO,
                    onClick = { choice = PushTransportPref.AUTO },
                )
                TransportRow(
                    title = "Polling only",
                    subtitle = POLL_ONLY_SUBTITLE,
                    selected = choice == PushTransportPref.POLL_ONLY,
                    onClick = { choice = PushTransportPref.POLL_ONLY },
                )
            }
        }
        if (choice == PushTransportPref.UNIFIEDPUSH && facts.distributors.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            DistributorSuggestion(actions)
        }
        Spacer(Modifier.height(26.dp))
        PrimaryButton(
            "Continue",
            enabled = choice != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = { choice?.let(actions::answerPushOptIn) },
        )
    }
}

@Composable
private fun NotificationSettingsScreen(
    settings: PushSettingsUi,
    state: UiState.Approvals,
    actions: AppActions,
    versionLabel: String = "",
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            TextButton(onClick = actions::closeNotificationSettings) { Text("←  Back") }
        }

        Column(
            modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Push service",
                fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Who delivers push to this phone, and whether anyone does. The app always " +
                    "checks while it is open; push makes it instant, including in the " +
                    "background. If a request times out undecided, the server denies it. " +
                    "Nothing is ever approved by silence.",
                fontSize = 13.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val pushOn = settings.consent == PushConsent.GRANTED
            PushMasterSwitch(pushOn, onToggle = actions::setPushEnabled)

            if (settings.postureNote != null) {
                Text(
                    settings.postureNote,
                    fontSize = 13.sp, lineHeight = 17.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!pushOn) {
                // Push is off by the user's choice: one card, no picker, no status.
                StatusCard(
                    title = "Push is off",
                    body = "New requests appear only while the app is open. Any push " +
                        "route this phone had registered is removed. If a request " +
                        "times out undecided, the server denies it.",
                )
                VersionFooter(versionLabel)
                return@Column
            }

            if (settings.transportPickerAvailable) TransportPicker(settings, actions)

            PushStatusBlock(settings, state.nowEpochSeconds)

            VersionFooter(versionLabel)
        }
    }
}

/**
 * The push master switch. It is the persisted opt-in, not a mute: off tears the
 * server route down, on registers it again and may raise the OS permission ask
 * on Android 13+.
 */
@Composable
private fun PushMasterSwitch(checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "Push notifications",
                fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (checked) "This phone is alerted when a request needs you."
                else "Off. The app finds new requests only while it is open.",
                fontSize = 13.sp, lineHeight = 17.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/** The polling row's subtitle, shared by the first-start ask and the settings screen. */
private const val POLL_ONLY_SUBTITLE =
    "No push provider at all. New requests appear only while the app is open, " +
        "and nothing about this phone is registered anywhere."

/**
 * The provider rows, shared by the first-start ask (local selection, committed
 * by Continue) and the Push service picker (applied immediately).
 */
@Composable
private fun ProviderRows(
    settings: PushSettingsUi,
    selectedPref: PushTransportPref?,
    onSelect: (PushTransportPref) -> Unit,
) {
    TransportRow(
        title = "Automatic",
        subtitle = autoSubtitle(settings),
        selected = selectedPref == PushTransportPref.AUTO,
        onClick = { onSelect(PushTransportPref.AUTO) },
    )
    if (settings.fcmInBuild) {
        TransportRow(
            title = "Google push",
            // An unavailable lane stays visible, greyed and unselectable,
            // and its subtitle carries the reason.
            subtitle = when (settings.fcm) {
                FcmStatus.READY ->
                    "Delivered through Google Play services (FCM)."
                FcmStatus.NO_PLAY_SERVICES ->
                    "Google Play services is not available on this phone."
                FcmStatus.NO_FIREBASE_CONFIG ->
                    "This build carries no Firebase configuration, so Google push " +
                        "cannot register. Use UnifiedPush or polling below."
                // Not reachable: the row is hidden on a build without FCM.
                FcmStatus.NOT_IN_BUILD -> null
            },
            selected = selectedPref == PushTransportPref.FCM,
            enabled = settings.fcmAvailable,
            onClick = { onSelect(PushTransportPref.FCM) },
        )
    }
    TransportRow(
        title = "UnifiedPush",
        subtitle = if (settings.distributors.isEmpty()) {
            "Self-hosted push through a distributor app such as ntfy. None is installed yet."
        } else {
            "Self-hosted push through a distributor app on this phone."
        },
        selected = selectedPref == PushTransportPref.UNIFIEDPUSH,
        onClick = { onSelect(PushTransportPref.UNIFIEDPUSH) },
    )
    TransportRow(
        title = "Polling only",
        subtitle = POLL_ONLY_SUBTITLE,
        selected = selectedPref == PushTransportPref.POLL_ONLY,
        onClick = { onSelect(PushTransportPref.POLL_ONLY) },
    )
}

/**
 * The Android provider picker plus the distributor chooser. iOS passes
 * `transportPickerAvailable = false` and shows the switch, posture and status
 * only.
 */
@Composable
private fun TransportPicker(settings: PushSettingsUi, actions: AppActions) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderRows(
                settings,
                selectedPref = settings.pref,
                onSelect = { actions.setPushTransport(it) },
            )
        }

        val wantsUnifiedPush = settings.pref == PushTransportPref.UNIFIEDPUSH ||
            (settings.pref == PushTransportPref.AUTO && settings.effective == PushTransport.UNIFIEDPUSH)

        // `!fcmAvailable`, not `!fcmInBuild`: a play build whose FCM lane cannot
        // run (no Firebase configuration, no Play services) is as unreachable
        // as a foss build with no distributor, and gets the same suggestion.
        if (settings.distributors.isEmpty() &&
            (settings.pref == PushTransportPref.UNIFIEDPUSH || !settings.fcmAvailable)
        ) {
            DistributorSuggestion(actions)
        }

        if (wantsUnifiedPush && settings.distributors.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "DISTRIBUTOR",
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                settings.distributors.forEach { d ->
                    TransportRow(
                        title = d.label,
                        subtitle = null,
                        selected = d.id == settings.chosenDistributorId,
                        onClick = { actions.setPushDistributor(d.id) },
                    )
                }
            }
        }
    }
}

private fun autoSubtitle(settings: PushSettingsUi): String = when {
    // fcmAvailable implies "in the build" (FcmStatus.usable), so a play build
    // with no Firebase configuration falls through and does not promise
    // Google push.
    settings.fcmAvailable ->
        "Best available on this phone: Google push now, UnifiedPush if you remove Play services."
    settings.distributors.isNotEmpty() ->
        "Best available on this phone: UnifiedPush through your installed distributor."
    else ->
        "Best available on this phone. With no push app installed, that is polling."
}

/** A radio-style option row. An amber border marks the selection. */
@Composable
private fun TransportRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (selected) accent else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            title,
            fontSize = 14.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                fontSize = 13.sp, lineHeight = 17.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shown when UnifiedPush is wanted and no distributor is installed: a link to ntfy. */
@Composable
private fun DistributorSuggestion(actions: AppActions) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "UnifiedPush needs a distributor app to deliver the push. ntfy is the usual " +
                    "choice: install it, and this screen will pick it up.",
                fontSize = 13.sp, lineHeight = 17.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            GhostButton("Get ntfy", modifier = Modifier.fillMaxWidth(), onClick = actions::getDistributorApp)
        }
    }
}

/** Registration state and last delivery: whether push can reach this phone. */
@Composable
private fun PushStatusBlock(settings: PushSettingsUi, nowEpochSeconds: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "STATUS",
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val s = settings.status) {
            is PushRouteStatus.Registered -> StatusCard(
                title = "Push is registered",
                // Keyed on the route's kind, not the resolved transport: iOS
                // has an APNs route and no transport picker. The exception is
                // the Android transient where a route still stands after the
                // resolution fell to polling and its teardown is in flight.
                body = if (settings.transportPickerAvailable && settings.effective == PushTransport.POLL_ONLY) {
                    "A route is still registered; it will be removed."
                } else {
                    when (s.route.kind) {
                        PushKind.UNIFIEDPUSH -> "Push reaches this phone through ${hostOf(s.route.tokenOrEndpoint)}."
                        PushKind.FCM -> "Push reaches this phone through Google push."
                        PushKind.APNS -> "Push reaches this phone through Apple push."
                        PushKind.WEBPUSH -> "Push reaches this phone through web push."
                    }
                },
            )
            is PushRouteStatus.Rejected -> StatusCard(
                title = "The server refused this push endpoint",
                body = "\"${s.serverMessage}\"\n\nAsk your admin to allowlist this push host on " +
                    "the server (approval.push.allowedPushHosts). Until then the app still " +
                    "works: it checks for approvals whenever it is open.",
                emphasized = true,
            )
            is PushRouteStatus.Unreached -> StatusCard(
                title = "Push is not registered yet",
                body = "${s.why}. Registration retries the next time the app opens.",
            )
            is PushRouteStatus.Unavailable -> StatusCard(
                title = "Push cannot reach this phone",
                body = "${s.why}.\n\nDeciding still works normally: the app checks for waiting " +
                    "approvals every few seconds while it is open, and the server denies " +
                    "anything that times out undecided. Nothing is ever approved by silence. " +
                    "To be alerted in the background, pick UnifiedPush above.",
                emphasized = true,
            )
            PushRouteStatus.None -> StatusCard(
                title = if (settings.effective == PushTransport.POLL_ONLY) "Polling" else "No push route yet",
                body = if (settings.effective == PushTransport.POLL_ONLY) {
                    "The app checks for waiting approvals every few seconds while it is open."
                } else {
                    "A route registers automatically once the transport is ready."
                },
            )
        }
        Text(
            RelativeTime.label(settings.lastDeliveryEpochSeconds, nowEpochSeconds)
                ?.let { "Last push received: $it" }
                ?: "No push has been received on this phone yet.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(title: String, body: String, emphasized: Boolean = false) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (emphasized) StrazaTheme.status.deniedFg else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                title,
                fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                color = if (emphasized) StrazaTheme.status.deniedFg else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                body,
                fontSize = 13.sp, lineHeight = 17.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
            )
        }
    }
}

/** The endpoint's host, for display. The full URL is long and mostly secret-shaped. */
private fun hostOf(endpoint: String): String =
    endpoint.substringAfter("://", endpoint).substringBefore("/").ifEmpty { "your distributor" }
