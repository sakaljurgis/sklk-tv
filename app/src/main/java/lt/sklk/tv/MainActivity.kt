package lt.sklk.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit

/** Hosts a back stack of [BrowseFragment]s, one per api folder; the remote's Back key goes up a level. */
class MainActivity : FragmentActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.container, BrowseFragment.newInstance(path = "", crumbs = emptyList()))
            }
        }
    }

    fun openFolder(fragment: BrowseFragment) {
        supportFragmentManager.commit {
            setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out, android.R.animator.fade_in, android.R.animator.fade_out)
            replace(R.id.container, fragment)
            addToBackStack(null)
        }
    }
}
