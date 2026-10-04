package de.energy6.caravanleveler

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import dagger.hilt.android.AndroidEntryPoint
import de.energy6.caravanleveler.databinding.MainBinding
import kotlinx.coroutines.flow.onEach

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val mViewModel: MainViewModel by viewModels()
    private val mBinding by lazy { MainBinding.inflate(layoutInflater) }
    private val mNavController: NavController by lazy {
        (supportFragmentManager.findFragmentById(mBinding.fragment.id) as NavHostFragment).navController
    }
    private val mAppBarConfiguration by lazy {
        AppBarConfiguration(setOf(R.id.levelerFragment))
    }
    private var mConnectSensor : SwitchCompat? = null
    private val mPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        mViewModel.permissionsRequested()
    }

    private var mUiState = MainUiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(mBinding.root)
        setSupportActionBar(mBinding.toolbar)
        setupActionBarWithNavController(mNavController, mAppBarConfiguration)

        val toolbarHeight = mBinding.toolbar.layoutParams.height
        val toolbarTopPadding = mBinding.toolbar.paddingTop
        val isNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNightMode
            isAppearanceLightNavigationBars = !isNightMode
        }
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = insets.left,
                top = 0,
                right = insets.right,
                bottom = insets.bottom
            )
            mBinding.toolbar.updatePadding(top = toolbarTopPadding + insets.top)
            mBinding.toolbar.updateLayoutParams {
                height = toolbarHeight + insets.top
            }
            windowInsets
        }
        mNavController.addOnDestinationChangedListener { _, _, _ -> invalidateOptionsMenu() }

        launchRepeatOnLifecycle(Lifecycle.State.STARTED) {
            mViewModel.uiState
                .onEach { mUiState = it }
                .collect {
                    mConnectSensor?.isEnabled = it.isConnectEnabled
                    mConnectSensor?.isChecked = it.isConnected
                    if (it.requestPermissions) {
                        requestPermissions()
                    }
                }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.main_menu, menu)
        mConnectSensor = menu?.findItem(R.id.connect_sensor)?.actionView
            ?.findViewById<SwitchCompat>(R.id.switch_item)?.apply {
                isEnabled = mUiState.isConnectEnabled
                isChecked = mUiState.isConnected
                setOnCheckedChangeListener { _, state -> mViewModel.connect(state) }
            }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val showLevelerActions = mNavController.currentDestination?.id == R.id.levelerFragment
        menu.findItem(R.id.connect_sensor)?.isVisible = showLevelerActions
        menu.findItem(R.id.open_preferences)?.isVisible = showLevelerActions
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.open_preferences -> {
                NavGraphDirections.actionGlobalSettingsFragment().also {
                    mNavController.navigate(it)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean =
        mNavController.navigateUp(mAppBarConfiguration) || super.onSupportNavigateUp()

    private fun requestPermissions() {
        runOnUiThread {
            AlertDialog.Builder(this).apply {
                setTitle(resources.getString(R.string.permission_required))
                setMessage(resources.getString(R.string.permission_description))
                setCancelable(false)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    mPermissionRequest.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                }
            }.show()
        }
    }
}
