package dev.evestaticmapplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem as MaterialDropdownMenuItem
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField as MaterialOutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.Checkbox as MaterialCheckbox
import androidx.compose.material3.DropdownMenu as MaterialDropdownMenu
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties

/** Fixed, low-saturation shell colors. Map rendering owns its separate visual palette. */
object EveColors {
    val MapBackground = Color(0xFF000000)
    val PrimarySurface = Color(0xFF0B1115)
    val SecondarySurface = Color(0xFF10191E)
    val InputSurface = Color(0xFF05080A)
    val Border = Color(0xFF283840)
    val FloatingBorder = Color(0xFF344A54)
    val Divider = Color(0xFF1E2B31)
    val PrimaryText = Color(0xFFC3CDD2)
    val SecondaryText = Color(0xFF77878E)
    val DisabledText = Color(0xFF4F5C62)
    val PrimaryAccent = Color(0xFF4F9EC3)
    val Important = Color(0xFFCDA15B)
    val HoverSurface = Color(0xFF14242B)
    val SelectedSurface = Color(0xFF18313C)
    val PressedSurface = Color(0xFF081014)
    val Success = Color(0xFF75B68A)
    val Warning = Color(0xFFCDA15B)
    val Error = Color(0xFFD77B73)
    val CapitalAccent = Color(0xFF9E86B6)
    val ModalScrim = Color(0x99000000)
}

object EveDimensions {
    val CornerRadius = 1.dp
    val BorderWidth = 1.dp
    val MinimumInteractiveSize = 40.dp
    val ButtonHeight = 36.dp
    val InputMinimumHeight = 44.dp
    val InputHorizontalPadding = 10.dp
    val InputVerticalPadding = 8.dp
    val CompactInputHeight = 36.dp
    val MenuBarHeight = 30.dp
    val MenuRowHeight = 36.dp
    val PanelPadding = 12.dp
    val WindowPadding = 16.dp
    val SectionSpacing = 6.dp
    val ContentSpacing = 8.dp
    val ScrollbarThickness = 5.dp
}

val EveShapes = Shapes(
    extraSmall = RoundedCornerShape(EveDimensions.CornerRadius),
    small = RoundedCornerShape(EveDimensions.CornerRadius),
    medium = RoundedCornerShape(EveDimensions.CornerRadius),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)

val EveTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
)

private val EveColorScheme = darkColorScheme(
    primary = EveColors.PrimaryAccent,
    onPrimary = EveColors.InputSurface,
    primaryContainer = EveColors.SelectedSurface,
    onPrimaryContainer = EveColors.PrimaryText,
    inversePrimary = EveColors.PrimaryAccent,
    secondary = EveColors.SecondaryText,
    onSecondary = EveColors.InputSurface,
    secondaryContainer = EveColors.SecondarySurface,
    onSecondaryContainer = EveColors.PrimaryText,
    tertiary = EveColors.Important,
    onTertiary = EveColors.InputSurface,
    tertiaryContainer = EveColors.SecondarySurface,
    onTertiaryContainer = EveColors.Important,
    background = EveColors.PrimarySurface,
    onBackground = EveColors.PrimaryText,
    surface = EveColors.PrimarySurface,
    onSurface = EveColors.PrimaryText,
    surfaceVariant = EveColors.SecondarySurface,
    onSurfaceVariant = EveColors.SecondaryText,
    surfaceTint = Color.Transparent,
    inverseSurface = EveColors.PrimaryText,
    inverseOnSurface = EveColors.PrimarySurface,
    error = EveColors.Error,
    onError = EveColors.InputSurface,
    errorContainer = Color(0xFF361A18),
    onErrorContainer = Color(0xFFE9B7B2),
    outline = EveColors.Border,
    outlineVariant = EveColors.Divider,
    scrim = EveColors.ModalScrim,
    surfaceBright = EveColors.HoverSurface,
    surfaceDim = EveColors.InputSurface,
    surfaceContainer = EveColors.SecondarySurface,
    surfaceContainerHigh = EveColors.HoverSurface,
    surfaceContainerHighest = EveColors.SelectedSurface,
    surfaceContainerLow = EveColors.PrimarySurface,
    surfaceContainerLowest = EveColors.InputSurface,
)

object EveTheme {
    @Composable
    operator fun invoke(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides EveDimensions.MinimumInteractiveSize,
            LocalScrollbarStyle provides ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = EveDimensions.ScrollbarThickness,
                shape = RoundedCornerShape(EveDimensions.CornerRadius),
                hoverDurationMillis = 120,
                unhoverColor = EveColors.Border.copy(alpha = 0.72f),
                hoverColor = EveColors.SecondaryText,
            ),
        ) {
            MaterialTheme(
                colorScheme = EveColorScheme,
                typography = EveTypography,
                shapes = EveShapes,
                content = content,
            )
        }
    }
}

@Composable
fun EveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val container = when {
        pressed -> EveColors.PressedSurface
        hovered -> EveColors.HoverSurface
        else -> EveColors.SecondarySurface
    }
    MaterialButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = EveDimensions.ButtonHeight),
        enabled = enabled,
        shape = EveShapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = if (hovered) EveColors.PrimaryAccent else EveColors.PrimaryText,
            disabledContainerColor = EveColors.PrimarySurface,
            disabledContentColor = EveColors.DisabledText,
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        border = BorderStroke(
            EveDimensions.BorderWidth,
            if (hovered) EveColors.PrimaryAccent.copy(alpha = 0.72f) else EveColors.Border,
        ),
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun EveTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    MaterialTextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = EveDimensions.ButtonHeight),
        enabled = enabled,
        shape = EveShapes.small,
        colors = ButtonDefaults.textButtonColors(
            containerColor = when {
                pressed -> EveColors.PressedSurface
                selected -> EveColors.SelectedSurface
                hovered -> EveColors.HoverSurface
                else -> Color.Transparent
            },
            contentColor = if (hovered || selected) EveColors.PrimaryAccent else EveColors.PrimaryText,
            disabledContentColor = if (selected) EveColors.PrimaryAccent else EveColors.DisabledText,
        ),
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun EveOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    MaterialOutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = EveDimensions.ButtonHeight),
        enabled = enabled,
        shape = EveShapes.small,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = when {
                pressed -> EveColors.PressedSurface
                hovered -> EveColors.HoverSurface
                else -> Color.Transparent
            },
            contentColor = if (hovered) EveColors.PrimaryAccent else EveColors.PrimaryText,
            disabledContentColor = EveColors.DisabledText,
        ),
        border = BorderStroke(
            EveDimensions.BorderWidth,
            if (hovered) EveColors.PrimaryAccent.copy(alpha = 0.72f) else EveColors.Border,
        ),
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun EveCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    MaterialCheckbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = EveColors.PrimaryAccent,
            uncheckedColor = EveColors.Border,
            checkmarkColor = EveColors.InputSurface,
            disabledCheckedColor = EveColors.Border,
            disabledUncheckedColor = EveColors.Divider,
            disabledIndeterminateColor = EveColors.Border,
        ),
    )
}

@Composable
fun EveOutlinedTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = EveColors.PrimaryText,
    unfocusedTextColor = EveColors.PrimaryText,
    disabledTextColor = EveColors.DisabledText,
    errorTextColor = EveColors.PrimaryText,
    focusedContainerColor = EveColors.InputSurface,
    unfocusedContainerColor = EveColors.InputSurface,
    disabledContainerColor = EveColors.InputSurface.copy(alpha = 0.7f),
    errorContainerColor = EveColors.InputSurface,
    cursorColor = EveColors.PrimaryAccent,
    errorCursorColor = EveColors.Error,
    focusedBorderColor = EveColors.PrimaryAccent,
    unfocusedBorderColor = EveColors.Border,
    disabledBorderColor = EveColors.Divider,
    errorBorderColor = EveColors.Error,
    focusedLabelColor = EveColors.PrimaryAccent,
    unfocusedLabelColor = EveColors.SecondaryText,
    disabledLabelColor = EveColors.DisabledText,
    errorLabelColor = EveColors.Error,
    focusedPlaceholderColor = EveColors.SecondaryText,
    unfocusedPlaceholderColor = EveColors.SecondaryText,
    disabledPlaceholderColor = EveColors.DisabledText,
    errorPlaceholderColor = EveColors.SecondaryText,
    focusedSupportingTextColor = EveColors.SecondaryText,
    unfocusedSupportingTextColor = EveColors.SecondaryText,
    disabledSupportingTextColor = EveColors.DisabledText,
    errorSupportingTextColor = EveColors.Error,
)

@Composable
fun EveOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = androidx.compose.material3.LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = EveShapes.small,
) {
    MaterialOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.defaultMinSize(minHeight = EveDimensions.InputMinimumHeight),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = EveOutlinedTextFieldColors(),
    )
}

@Composable
fun EveOutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = androidx.compose.material3.LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = EveShapes.small,
) {
    MaterialOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.defaultMinSize(minHeight = EveDimensions.InputMinimumHeight),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = EveOutlinedTextFieldColors(),
    )
}

@Composable
fun EveDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = EveShapes.small,
        containerColor = EveColors.InputSurface,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(EveDimensions.BorderWidth, EveColors.FloatingBorder),
        content = content,
    )
}

@Composable
fun EveDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    MaterialDropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier.height(EveDimensions.MenuRowHeight),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = EveColors.PrimaryText,
            leadingIconColor = EveColors.SecondaryText,
            trailingIconColor = EveColors.SecondaryText,
            disabledTextColor = EveColors.DisabledText,
            disabledLeadingIconColor = EveColors.DisabledText,
            disabledTrailingIconColor = EveColors.DisabledText,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    )
}
