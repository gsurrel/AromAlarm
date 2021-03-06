package org.surrel.aromalarm

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.*
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.*
import java.util.regex.Pattern


class MainActivity : AppCompatActivity() {

    private val SELECT_DEVICE_REQUEST_CODE: Int = 0
    private var mac_address: String? = null

    private val deviceManager: CompanionDeviceManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(CompanionDeviceManager::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mac_address = getSharedPreferences(Constants.DEVICE_PREFS,
            MODE_PRIVATE).getString(Constants.MAC_ADDRESS, null)
        if (mac_address.isNullOrBlank()) {
            setupCompanionDevice()
        } else {
            startDeviceService(mac_address!!)
        }

        setupUi()

        val intensitySeekBar = findViewById<SeekBar>(R.id.seekBar)
        intensitySeekBar.setOnSeekBarChangeListener(object :SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                seekBar?.progress?.let { changeIntensity(it) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Nothing
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Nothing
            }
        })
    }

    private fun startDeviceService(mac: String) {
        val intent = Intent(this, DeviceService::class.java).putExtra(Constants.MAC_ADDRESS, mac)
        Log.i("MainActivity", intent.getStringExtra(Constants.MAC_ADDRESS) ?: "No address")
        startService(intent)
    }

    private fun setupCompanionDevice() {
        val deviceFilter: BluetoothDeviceFilter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("Aroma31"))
            // Filtering by Service UUID crash on some phones
            //.addServiceUuid(ParcelUuid(UUID.fromString("ae00-0000-1000-8000-00805f9b34fb")), null)
            .build()
        val pairingRequest: AssociationRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(true)
            .build()

        // When the app tries to pair with the Bluetooth device, show the
        // appropriate pairing request dialog to the user.
        deviceManager.associate(
            pairingRequest,
            object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    startIntentSenderForResult(
                        chooserLauncher,
                        SELECT_DEVICE_REQUEST_CODE, null, 0, 0, 0
                    )
                }

                override fun onFailure(error: CharSequence?) {
                    // Handle failure...
                }
            }, null
        )
    }

    private fun setupUi() {
        setContentView(R.layout.activity_main)
        val navView: BottomNavigationView = findViewById(R.id.nav_view)

        val navController = findNavController(R.id.nav_host_fragment)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        var deviceToPair: BluetoothDevice? = null

        when (requestCode) {
            SELECT_DEVICE_REQUEST_CODE -> when (resultCode) {
                Activity.RESULT_OK -> {
                    // User has chosen to pair with the Bluetooth device.
                    deviceToPair =
                        data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
                }
            }
        }

        if (deviceToPair != null) {
            deviceToPair.let {
                it.createBond()
                val mac = getSharedPreferences(Constants.DEVICE_PREFS, MODE_PRIVATE)
                mac.edit().putString(Constants.MAC_ADDRESS, it.address).apply()
                startDeviceService(it.address)
            }

        } else {
            AlertDialog.Builder(this)
                .setTitle("No device selected")
                .setMessage(getString(R.string.device_needed_to_use_app))
                //.setIcon(R.drawable.ic_launcher)
                .setPositiveButton(
                    "Pair again"
                ) { dialog, which -> this@MainActivity.setupCompanionDevice() }
                .setNegativeButton(
                    "Exit"
                ) { dialog, which -> finish() }
                .create().show()
        }
    }

    fun changeIntensity(intensity: Int) {
        sendCommand(when (intensity) {
            0 -> Constants.SET_ACTIVE_OFF
            1 -> Constants.SET_INTENSITY_LOW
            2 -> Constants.SET_INTENSITY_HIGH
            else -> Constants.SET_ACTIVE_ON
        })
    }
    fun togglePulsing(view: View) {
        val switch = view as SwitchCompat
        sendCommand(if (switch.isChecked) Constants.SET_PLUSE_ON else Constants.SET_PULSE_OFF)
    }
    fun toggleLight(view: View) {
        val switch = view as SwitchCompat
        sendCommand(if (switch.isChecked) Constants.SET_LIGHT_ON else Constants.SET_LIGHT_OFF)
    }

    private fun sendCommand(command: String) {
        val intent = Intent(this, DeviceService::class.java).apply {
            action = command
            putExtra(Constants.MAC_ADDRESS, mac_address)
        }
        startService(intent)
    }
}


