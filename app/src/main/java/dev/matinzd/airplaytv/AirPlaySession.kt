package dev.matinzd.airplaytv

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.CopyOnWriteArraySet

/** Shared state between the receiver service and the screens. */
object AirPlaySession {
    data class State(
        val serverRunning: Boolean = false,
        val serverName: String = "",
        val port: Int = 0,
        val error: String? = null,
        val clientName: String? = null,
        val clientModel: String? = null,
        val connections: Int = 0,
        val mirroring: Boolean = false,
        val hlsVideo: Boolean = false,
        val audioOnly: Boolean = false,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val coverArt: Bitmap? = null,
        /** PIN a pairing device must enter, while one is being shown. */
        val pin: String? = null,
    ) {
        val showsVideo get() = mirroring || hlsVideo
        // A PIN stays up without a connection: the iPhone drops the connection that asked
        // for it and reconnects once the code is typed.
        val active get() = pin != null || connections > 0 && (mirroring || hlsVideo || audioOnly || clientName != null)

        fun endSession() = copy(
            clientName = null, clientModel = null, mirroring = false, hlsVideo = false, audioOnly = false,
            videoWidth = 0, videoHeight = 0, title = null, artist = null, album = null, coverArt = null,
        )
    }

    @Volatile
    var state = State()
        private set

    /** Set by the service while it runs; the playback screen hands it its Surface. */
    @Volatile
    var surfaceSink: ((Surface?) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<(State) -> Unit>()

    fun update(change: (State) -> State) {
        val newState = synchronized(this) {
            state = change(state)
            state
        }
        main.post { observers.forEach { it(newState) } }
    }

    /** Observers run on the main thread. */
    fun observe(observer: (State) -> Unit) {
        observers += observer
        observer(state)
    }

    fun removeObserver(observer: (State) -> Unit) {
        observers -= observer
    }
}
