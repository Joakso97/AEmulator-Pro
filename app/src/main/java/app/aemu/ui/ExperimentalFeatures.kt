
package app.aemu.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import app.aemu.R

@Composable
fun SunsetTitle(style: TextStyle = LocalTextStyle.current, color: Color = Color.Unspecified) {
    Text(stringResource(R.string.app_name), style = style, color = color)
}
