package tech.mrzeapple.ciphercodex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.mrzeapple.ciphercodex.ui.theme.CipherCyan
import tech.mrzeapple.ciphercodex.ui.theme.CipherMagenta
import tech.mrzeapple.ciphercodex.ui.theme.CipherMuted
import tech.mrzeapple.ciphercodex.ui.theme.CipherStatic
import tech.mrzeapple.ciphercodex.ui.theme.CipherVoid

/* The Cipher design kit for chrome screens. One signature shape (the cut
 * corner, from the icon's angular frame), one signature gradient (cyan ->
 * magenta), used sparingly — restraint rules from the brief apply. The
 * reading surface must NOT use anything in this file. */

val CipherGradient = Brush.linearGradient(listOf(CipherCyan, CipherMagenta))

val CipherShape = CutCornerShape(10.dp)
val CipherShapeSmall = CutCornerShape(6.dp)

/** Panel: the standard card/container. Static-noise surface, angular cut,
 *  hairline gradient border. */
@Composable
fun CipherPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(CipherShape)
            .background(CipherStatic)
            .border(1.dp, CipherGradient, CipherShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        content()
    }
}

/** Screen header: Orbitron wordmark line over a gradient rule. */
@Composable
fun CipherHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) trailing()
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(CipherGradient),
        )
    }
}

/** Primary action: cut-corner outline, mono label, cyan by default. */
@Composable
fun CipherButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = CipherCyan,
    enabled: Boolean = true,
) {
    val color = if (enabled) accent else CipherMuted
    Box(
        modifier = modifier
            .clip(CipherShapeSmall)
            .border(1.dp, color, CipherShapeSmall)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** Text input in Cipher chrome: mono font, cyan focus, angular cut. */
@Composable
fun CipherTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isPassword: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = CipherShapeSmall,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CipherCyan,
            unfocusedBorderColor = CipherMuted,
            focusedLabelColor = CipherCyan,
            unfocusedLabelColor = CipherMuted,
            cursorColor = CipherCyan,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = CipherVoid,
            unfocusedContainerColor = CipherVoid,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Thin reading-progress bar: muted track, gradient fill. */
@Composable
fun CipherProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(CipherStatic),
    ) {
        Box(
            Modifier
                .fillMaxWidth(clamped)
                .height(3.dp)
                .background(CipherGradient),
        )
    }
}

/** Bottom tab bar: icon + mono label per tab, cyan when active and muted
 *  otherwise, over the static surface with a gradient top hairline. */
@Composable
fun CipherBottomNav(
    tabs: List<Pair<ImageVector, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CipherStatic),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CipherGradient),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, (icon, label) ->
                val active = i == selected
                val tint = if (active) CipherCyan else CipherMuted
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CipherShapeSmall)
                        .clickable { onSelect(i) }
                        .padding(vertical = 6.dp),
                ) {
                    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(5.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
        // Extend the bar's surface under the system navigation bar.
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** Small mono caption in the muted ink — metadata lines, statuses. */
@Composable
fun CipherCaption(text: String, modifier: Modifier = Modifier, color: Color = CipherMuted) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
