package app.coilforphoniebox

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * An empty activity for the screenshot tests to compose into.
 *
 * It exists because `CoilApp` resolves its view models through `hiltViewModel()`, which needs
 * a host activity that is itself a Hilt entry point — the stock activity a Compose test rule
 * would otherwise use is not one, and the app's own [MainActivity] brings a media session,
 * a connection and a permission prompt with it, none of which belong in a screenshot.
 *
 * It lives in the debug source set because that is the only place a test can see an activity
 * from: Robolectric will not launch one that is absent from the merged manifest. It is
 * therefore present in debug builds and absent from release ones, and it is not exported.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
