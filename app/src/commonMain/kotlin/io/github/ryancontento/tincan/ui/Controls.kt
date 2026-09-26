package io.github.ryancontento.tincan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mouse-sized controls: Material's assume a 40dp touch target and pill corners, so the app goes through these. */
object Metrics {
    val control: Dp = 26.dp
    val field: Dp = 28.dp
    val hairline: Dp = 1.dp
}

/** A one-line field: a border, a background, and nothing else. */
@Composable
fun TinField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        interactionSource = interaction,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier,
        decorationBox = { field ->
            FieldFrame(focused, leading, trailing) {
                if (value.isEmpty()) PlaceholderText(placeholder, textStyle)
                field()
            }
        },
    )
}

/** The [TextFieldValue] form, for the composer, which has to track the caret. */
@Composable
fun TinField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        interactionSource = interaction,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier,
        decorationBox = { field ->
            FieldFrame(focused, null, null) {
                if (value.text.isEmpty()) PlaceholderText(placeholder, textStyle)
                field()
            }
        },
    )
}

@Composable
private fun FieldFrame(
    focused: Boolean,
    leading: @Composable (() -> Unit)?,
    trailing: @Composable (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .heightIn(min = Metrics.field)
            .background(colors.surfaceContainer, MaterialTheme.shapes.small)
            .border(
                Metrics.hairline,
                if (focused) colors.primary else colors.outline,
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { content() }
        trailing?.invoke()
    }
}

@Composable
private fun PlaceholderText(text: String, style: TextStyle) {
    if (text.isEmpty()) return
    Text(text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The one action a screen is really offering. Everything else is quieter. */
@Composable
fun TinButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Metrics.control).pointerHoverIcon(PointerIcon.Hand),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TinOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String,
    leading: @Composable (() -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Metrics.control).pointerHoverIcon(PointerIcon.Hand),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(Metrics.hairline, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        leading?.let { it(); Box(Modifier.size(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Neutral until hovered: a row of accent-coloured words reads as a row of links. */
@Composable
fun TinToolbarButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    /** For addresses and other technical values. */
    mono: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme

    TextButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.heightIn(min = Metrics.control).pointerHoverIcon(PointerIcon.Hand),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = when {
                accent -> colors.primary
                hovered -> colors.onSurface
                else -> colors.onSurfaceVariant
            },
        ),
    ) {
        Text(
            label,
            style = if (mono) MaterialTheme.typography.labelMedium.merge(MonoStyle) else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A bare icon target, sized to the icon rather than to a thumb. */
@Composable
fun TinIconButton(
    onClick: () -> Unit,
    icon: TinIcon,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme

    TextButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .size(22.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            // The glyph is a drawing, so the button carries the name.
            .semantics { this.contentDescription = description },
        shape = MaterialTheme.shapes.extraSmall,
        contentPadding = PaddingValues(0.dp),
    ) {
        TinIconGlyph(
            icon = icon,
            tint = tint ?: if (hovered) colors.onSurface else colors.onSurfaceVariant,
            size = 13.dp,
        )
    }
}

@Composable
fun TinSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.9.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Label above, field, then the explanation — a settings row, not a floating label. */
@Composable
fun TinFormRow(
    label: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        content()
        hint?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One border, one answer: radio buttons would take triple the height and read as separate decisions. */
@Composable
fun <T> TinSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .heightIn(min = Metrics.field)
            .background(colors.surfaceContainer, MaterialTheme.shapes.small)
            .border(Metrics.hairline, colors.outline, MaterialTheme.shapes.small)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(if (isSelected) colors.surfaceContainerHigh else Color.Transparent)
                    .clickable { onSelect(option) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** Structure comes from rules like this, not from shadows. */
@Composable
fun TinDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.hairline)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
