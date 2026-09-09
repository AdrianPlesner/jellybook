package dk.azp.jellybook

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

class JellybookApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startBackgroundSync()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
