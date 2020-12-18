package org.surrel.aromalarm

import android.bluetooth.*
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LifecycleService

import org.surrel.aromalarm.Constants.MAC_ADDRESS
import java.util.*

class DeviceService : LifecycleService() {
    val TAG = "DeviceService"

    private var device: BluetoothDevice? = null
    private var gattConnection: BluetoothGatt? = null
    private var aromaService: BluetoothGattService? = null
    private var aromaCommandCharacteristic: BluetoothGattCharacteristic? = null

    private var pendingCommands: MutableList<ByteArray> = MutableList(0) { byteArrayOf() }
    private val COMMAND_START = byteArrayOf(0x73, 0x01, 0x01, 0x00, 0x00, 0x75)
    private val COMMAND_STOP = byteArrayOf(0x73, 0x01, 0x00, 0x00, 0x00, 0x74)
    private var COMMAND_INTENSITY_HIGH = byteArrayOf(0x73, 0x05, 0x01, 0x00, 0x00, 0x79)
    private var COMMAND_INTENSITY_LOW = byteArrayOf(0x73, 0x05, 0x00, 0x00, 0x00, 0x78)
    private var COMMAND_PULSE_START = byteArrayOf(0x73, 0x04, 0x00, 0x00, 0x00, 0x77)
    private var COMMAND_PULSE_STOP = byteArrayOf(0x73, 0x04, 0x01, 0x00, 0x00, 0x78)
    private var COMMAND_LIGHT_ON = byteArrayOf(0x73, 0x08, 0x02, 0x00, 0x00, 0x7d)
    private var COMMAND_LIGHT_OFF = byteArrayOf(0x73, 0x08, 0x00, 0x00, 0x00, 0x7b)
    private var COMMAND_TIMER_OFF = byteArrayOf(0x73, 0x02, 0x30, 0x00, 0x00, 0xa5.toByte())

    // Various callback methods defined by the BLE API.
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            val intentAction: String
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server.")
                    Log.i(TAG, "Attempting to start service discovery: " + gatt.discoverServices())
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server.")
                }
            }
        }

        // New services discovered
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "Services discovered")
                    for (service in gatt.services) {
                        Log.i(TAG, "Service: ${service.uuid}")
                        if (service.uuid == UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")) {
                            Log.i(TAG, "Found Aroma service")
                            aromaService = service
                            for (characteristic in service.characteristics) {
                                Log.i(TAG, "Characteristic: ${characteristic.uuid}")
                                if (characteristic.uuid == UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")) {
                                    Log.i(TAG, "Found Aroma command characteristic")
                                    aromaCommandCharacteristic = characteristic
                                    processQueue()
                                }
                            }
                        }
                    }
                }
                else -> Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        // Result of a characteristic read operation
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "Data available: $characteristic")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.i(TAG, "Written to ${characteristic?.uuid} with status $status")
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mac = intent?.getStringExtra(MAC_ADDRESS)
        if (mac == null) {
            Log.w(TAG, "No $mac received")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "Using device $mac")

        intent.action?.let { processAction(it) }

        // Get the BT adapter
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        // TODO: check if bluetooth is on and ask to turn it on if not

        val connectionState =
            if (device == null) BluetoothGatt.STATE_DISCONNECTED else bluetoothManager.getConnectionState(
                device,
                BluetoothGatt.GATT);
        Log.i(TAG, "Connection state: $connectionState")
        if (device == null || (connectionState == BluetoothGatt.STATE_DISCONNECTED)) {
            // Get the Bluetooth device from the list of bonded devices, otherwise return
            val devices =
                bluetoothAdapter.bondedDevices.filter { bluetoothDevice -> bluetoothDevice.address == mac };
            device = devices.firstOrNull()

            gattConnection = device?.connectGatt(this, true, gattCallback, TRANSPORT_LE)
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun processAction(action: String) {
        when (action) {
            Constants.SET_ACTIVE_ON -> {
                pendingCommands.add(COMMAND_START)
            }
            Constants.SET_ACTIVE_OFF -> {
                pendingCommands.add(COMMAND_STOP)
            }
            Constants.SET_INTENSITY_HIGH -> {
                pendingCommands.add(COMMAND_INTENSITY_HIGH)
            }
            Constants.SET_INTENSITY_LOW -> {
                pendingCommands.add(COMMAND_INTENSITY_LOW)
            }
            Constants.SET_PLUSE_ON -> {
                pendingCommands.add(COMMAND_PULSE_START)
            }
            Constants.SET_PULSE_OFF -> {
                pendingCommands.add(COMMAND_PULSE_STOP)
            }
            Constants.SET_LIGHT_ON -> {
                pendingCommands.add(COMMAND_LIGHT_ON)
            }
            Constants.SET_LIGHT_OFF -> {
                pendingCommands.add(COMMAND_LIGHT_OFF)
            }
            Constants.SET_TIMER_ON -> {
            }
            Constants.SET_TIMER_OFF -> {
                pendingCommands.add(COMMAND_TIMER_OFF)
            }
        }

        processQueue()
    }

    private fun processQueue() {
        if (aromaCommandCharacteristic != null && gattConnection != null) {
            for (cmd in pendingCommands) {
                val str = cmd.joinToString(":", "", "", -1, "...") { value -> "%02x".format(value) }
                Log.i(TAG, "Sending command: 0x$str to ${aromaCommandCharacteristic?.uuid}")
                aromaCommandCharacteristic?.value = cmd
                gattConnection?.writeCharacteristic(aromaCommandCharacteristic)
                pendingCommands.remove(cmd)
            }
        }
    }

}