package dev.brahmkshatriya.kmplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import dev.brahmkshatriya.kmplayer.internal.AndroidKMPlayerBackend

/** Android-specific access and initialization hooks. */
public object KMPlayerAndroid {
    @Volatile
    internal var applicationContext: Context? = null

    /** Optional explicit initialization. The manifest provider normally does this automatically. */
    public fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }
}

/** Exposes Media3 for attaching PlayerView, PlayerSurface, media sessions, etc. */
public val KMPlayer.exoPlayer: ExoPlayer?
    get() = (platformBackend as? AndroidKMPlayerBackend)?.player

/** Internal zero-config Android initialization provider. */
public class KMPlayerInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(KMPlayerAndroid::initialize)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
