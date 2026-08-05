package app.coilforphoniebox.di

import android.content.Context
import app.coilforphoniebox.R
import app.coilforphoniebox.media.MediaNotificationTexts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the media session with its text and icon.
 *
 * Deliberately implemented here rather than in `:feature-media`: it keeps every
 * translatable string in the app module's single `strings.xml`, which is what the
 * translation workflow in §12.5 assumes. Strings are resolved on each read, so a locale
 * change reaches an already-running service.
 */
@Singleton
class AppMediaNotificationTexts @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaNotificationTexts {

    override val playbackChannelName: String
        get() = context.getString(R.string.notification_channel_playback)

    override val playbackChannelDescription: String
        get() = context.getString(R.string.notification_channel_playback_description)

    override val statusChannelName: String
        get() = context.getString(R.string.notification_channel_status)

    override val statusChannelDescription: String
        get() = context.getString(R.string.notification_channel_status_description)

    override val readyTitle: String
        get() = context.getString(R.string.notification_watching_title)

    override fun connectedTo(boxName: String): String =
        context.getString(R.string.notification_watching_text, boxName)

    override val smallIcon: Int get() = R.drawable.ic_stat_coil
}

@Module
@InstallIn(SingletonComponent::class)
interface AppModule {

    @Binds
    fun bindMediaNotificationTexts(impl: AppMediaNotificationTexts): MediaNotificationTexts
}
