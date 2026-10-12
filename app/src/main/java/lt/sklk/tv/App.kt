package lt.sklk.tv

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Progress.init(this)
    }
}
