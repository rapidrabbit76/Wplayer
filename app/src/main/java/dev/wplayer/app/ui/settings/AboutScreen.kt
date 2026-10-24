package dev.wplayer.app.ui.settings

import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.style.LibraryActionBadges
import dev.wplayer.app.BuildConfig
import dev.wplayer.app.R
import dev.wplayer.app.ui.common.BackTopAppBar
import dev.wplayer.app.ui.common.readableWidth

/**
 * Settings > About: the version, the app's license and the open-source
 * libraries it contains with their licenses (collected at build time by
 * AboutLibraries; see app/build.gradle.kts).
 */
@Composable
fun AboutScreen(onBack: (() -> Unit)?) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    Scaffold(
        topBar = { BackTopAppBar(stringResource(R.string.settings_about), onBack) },
    ) { padding ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.readableWidth(),
            contentPadding = padding,
            actionLabels = LibraryActionBadges(
                source = stringResource(R.string.about_source),
                website = stringResource(R.string.about_website),
                viewLicense = stringResource(R.string.about_view_license),
            ),
            licenseDialogConfirmText = stringResource(R.string.action_ok),
            header = {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.about_version)) },
                        supportingContent = { Text(BuildConfig.VERSION_NAME) },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.about_license)) },
                        supportingContent = { Text(stringResource(R.string.about_license_summary)) },
                    )
                }
                item { SectionHeader(R.string.about_open_source) }
            },
        )
    }
}
