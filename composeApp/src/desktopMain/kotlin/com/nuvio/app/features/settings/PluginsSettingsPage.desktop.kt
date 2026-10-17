package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Desktop placeholder: plugins are disabled on desktop
 * (see [com.nuvio.app.core.build.AppFeaturePolicy]).
 */
internal actual fun LazyListScope.pluginsSettingsContent() {
    item {
        Text(
            text = "Plugins are not supported on desktop",
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}
