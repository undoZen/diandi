package com.mianbizhe.diandiji.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.mianbizhe.diandiji.db.NotificationEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE ANCS（Apple Notification Center Service）采集端。
 *
 * ## 关键：正确的配件配对流程（Pebble 等智能手表用的就是这套）
 *
 * iOS 的 ANCS 不需要 MFi，但要求**配对绑定（bonding）**。而 iPhone 只接受
 * 「自己在蓝牙设置里发现一个外设并主动配对」，**不会**接受一个外部 Central
 * 反向 createBond 过来——这就是之前「一直连不上」的根因。
 *
 * 所以流程分两段：
 *
 * **Phase 1 — 配对（Android 当外设/Peripheral）：**
 *   1. Android 用 [BluetoothLeAdvertiser] 广播一个自定义服务，并开 [BluetoothGattServer]。
 *   2. 服务里放一个**需要加密读取**的特征值——iPhone 一旦读它就会被强制触发配对。
 *   3. 用户在 iPhone「设置 → 蓝牙」里看到 diandi，点一下配对 → bond 建立。
 *   4. Android 侧 [BroadcastReceiver] 收到 BOND_BONDED，记下 iPhone 地址（持久化）。
 *
 * **Phase 2 — 采集（Android 当 Central）：**
 *   5. 用记下的 iPhone 地址 [BluetoothDevice.connectGatt]，链路因 bond 而自动加密。
 *   6. 发现 ANCS 服务 → 订阅 Notification Source + Data Source。
 *   7. iPhone 来通知时 NS 推事件 → 写 Control Point 取属性 → DS 回标题/正文 → 落库。
 *
 * 配对只需做一次；之后 bond 持久化在系统蓝牙栈里，重启 App 直接走 Phase 2。
 *
 * ## ANCS 协议
 * - Service  7905F431-B5CE-4E99-A40F-4B1E122D00D0
 *   - [Notify] Notification Source — 通知事件（增/删/改）+ UID
 *   - [Write]  Control Point       — 请求通知/App 属性
 *   - [Notify] Data Source         — 属性响应（标题/正文/时间等）
 *
 * ## 参考
 * - Apple ANCS Specification（公开 GATT 服务，非 MFi）
 */
class AncsClient(
    private val context: Context,
    private val onNotification: (NotificationEntity) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "diandi-ancs"
        private const val PREFS = "diandi_ancs"
        private const val KEY_PAIRED_ADDR = "paired_addr"

        // ANCS（iPhone 侧暴露的 GATT 服务，Phase 2 以 Central 角色去读）
        val SERVICE_ANCS       = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        val CHAR_NOTIFICATION  = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        val CHAR_CONTROL_POINT = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        val CHAR_DATA_SOURCE   = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
        val CCCD               = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // 自定义配对服务（Phase 1 Android 当外设时广播）。
        // 首次启动为每台设备随机生成唯一 UUID，持久化到 SharedPreferences，
        // 后续同一设备始终用同一个（不同设备之间不冲突）。
        private const val KEY_PAIR_SVC_UUID = "pair_service_uuid"
        private const val KEY_PAIR_CHR_UUID = "pair_char_uuid"

        private fun initPairUuids(prefs: android.content.SharedPreferences): Pair<UUID, UUID> {
            val svc = prefs.getString(KEY_PAIR_SVC_UUID, null)
            val chr = prefs.getString(KEY_PAIR_CHR_UUID, null)
            if (svc != null && chr != null) {
                return UUID.fromString(svc) to UUID.fromString(chr)
            }
            val newSvc = UUID.randomUUID()
            val newChr = UUID.randomUUID()
            prefs.edit()
                .putString(KEY_PAIR_SVC_UUID, newSvc.toString())
                .putString(KEY_PAIR_CHR_UUID, newChr.toString())
                .apply()
            return newSvc to newChr
        }

        // ANCS CategoryID 枚举
        val CATEGORIES = mapOf(
            0  to "Other",
            1  to "IncomingCall",
            2  to "MissedCall",
            3  to "Voicemail",
            4  to "Social",
            5  to "Schedule",
            6  to "Email",
            7  to "News",
            8  to "HealthAndFitness",
            9  to "BusinessAndFinance",
            10 to "Location",
            11 to "Entertainment",
        )

        private const val ATTR_APP_IDENTIFIER = 0
        private const val ATTR_TITLE          = 1
        private const val ATTR_SUBTITLE       = 2
        private const val ATTR_MESSAGE        = 3
        private const val ATTR_MESSAGE_SIZE   = 4
        private const val ATTR_DATE           = 5

        private const val EVENT_ADDED    = 0
        private const val EVENT_MODIFIED = 1
        private const val EVENT_REMOVED  = 2

        fun bondStateName(state: Int): String = when (state) {
            BluetoothDevice.BOND_NONE -> "NONE"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            BluetoothDevice.BOND_BONDED -> "BONDED"
            else -> "UNKNOWN($state)"
        }
    }

    enum class State { IDLE, ADVERTISING, CONNECTING, CONNECTED, SUBSCRIBED }

    // ---- 只读状态（供 Web API / UI 读取） ----
    @Volatile var state: State = State.IDLE; private set
    @Volatile var deviceName: String? = null; private set
    @Volatile var lastError: String? = null; private set

    // 用户主动 disconnect 时不自动重连；订阅时间用于过滤订阅瞬间刷盘的历史通知
    @Volatile private var userInitiatedDisconnect = false
    @Volatile private var subscribedAt = 0L
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    val isAdvertising: Boolean get() = state == State.ADVERTISING
    val isConnected: Boolean get() = state == State.CONNECTED || state == State.SUBSCRIBED
    val isSubscribed: Boolean get() = state == State.SUBSCRIBED

    /** 已配对的 iPhone 地址（持久化，配对一次后后续直接连）。 */
    val pairedAddress: String?
        get() = prefs.getString(KEY_PAIRED_ADDR, null)

    /** 系统蓝牙栈里所有已配对设备（用于 UI 兜底手动选）。 */
    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<Map<String, String>> = try {
        bt.bondedDevices.map { d ->
            mapOf(
                "address" to d.address,
                "name" to (d.name ?: "(unknown)"),
                "bondState" to bondStateName(d.bondState),
            )
        }
    } catch (e: SecurityException) {
        emptyList()
    }

    // 监听配对状态变化
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
            log("bond: ${device.address} $prevState -> ${bondStateName(bondState)}")
            if (bondState == BluetoothDevice.BOND_BONDED && prevState != BluetoothDevice.BOND_BONDED) {
                onBonded(device)
            }
        }
    }
    private var receiverRegistered = false

    private val manager: BluetoothManager =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
    private val bt: BluetoothAdapter = manager.adapter
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // 配对服务 UUID：每台设备首次启动时随机生成，持久化后固定使用
    private val pairServiceUuid: UUID
    private val pairCharUuid: UUID
    val serviceUuid: String get() = pairServiceUuid.toString()
    val charUuid: String get() = pairCharUuid.toString()

    init {
        val (svc, chr) = initPairUuids(prefs)
        pairServiceUuid = svc
        pairCharUuid = chr
        try {
            context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            receiverRegistered = true
        } catch (e: Throwable) {
            Log.e(TAG, "bond receiver registration failed: $e")
        }
    }

    // 环形日志缓冲区（最近 80 条），浏览器可查看
    private val logBuffer = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private fun log(msg: String) {
        Log.i(TAG, msg)
        logBuffer += "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg"
        while (logBuffer.size > 80) logBuffer.poll()
    }
    fun getLogs(): List<String> = logBuffer.toList()

    // ---- Phase 1 资源 ----
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    @Volatile private var advertising = false

    // ---- Phase 2 资源 ----
    private var gatt: BluetoothGatt? = null
    private var nsChar: BluetoothGattCharacteristic? = null
    private var dsChar: BluetoothGattCharacteristic? = null
    private var cpChar: BluetoothGattCharacteristic? = null
    // DS 分片累积：ANCS 可能把一次属性响应拆成多个 Data Source 通知
    private val dsBuffer   = java.util.concurrent.ConcurrentHashMap<Int, MutableMap<Int, String>>()
    private val dsTimer    = java.util.concurrent.ConcurrentHashMap<Int, Runnable>()
    private val dsMergeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingWriteCallbacks =
        java.util.concurrent.ConcurrentHashMap<BluetoothGattDescriptor, (Boolean) -> Unit>()

    private fun setState(s: State, status: String? = null) {
        state = s
        status?.let { onStatus(it); log("state=$s | $it") } ?: log("state=$s")
    }

    // ====================================================================
    // 对外 API
    // ====================================================================

    /** Phase 1：开始广播，等用户在 iPhone 蓝牙设置里配对。 */
    @SuppressLint("MissingPermission")
    fun startPairing() {
        if (advertising) return
        val advertiser = bt.bluetoothLeAdvertiser ?: run {
            lastError = "BluetoothLeAdvertiser 不可用（设备不支持外设模式）"
            setState(State.IDLE, lastError!!)
            return
        }
        this.advertiser = advertiser

        // 1) 开 GATT server，放一个「加密读取」特征值，逼 iPhone 配对
        if (gattServer == null) {
            val server = manager.openGattServer(context, serverCallback) ?: run {
                lastError = "openGattServer 失败"
                setState(State.IDLE, lastError!!)
                return
            }
            val pairChar = BluetoothGattCharacteristic(
                pairCharUuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                // 加密权限：iPhone 读取时强制走配对
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            )
            val service = BluetoothGattService(pairServiceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(pairChar)
            }
            server.addService(service)
            gattServer = server
            log("GATT server ready, pair service exposed (encrypted read → forces pairing)")
        }

        // 2) 广播：名称放主包、服务 UUID 放扫描响应，避免 31 字节主包溢出
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        val scanResp = AdvertiseData.Builder()
            .addServiceUuid(android.os.ParcelUuid(pairServiceUuid))
            .build()
        try {
            advertiser.startAdvertising(settings, advData, scanResp, advertiseCallback)
        } catch (e: SecurityException) {
            lastError = "startAdvertising 权限被拒: ${e.message}"
            setState(State.IDLE, lastError!!)
            return
        }
        advertising = true
        setState(State.ADVERTISING, "正在广播——去 iPhone「设置→蓝牙」找到 diandi 并配对")
    }

    /** Phase 1：停止广播（配对完成或用户取消）。 */
    @SuppressLint("MissingPermission")
    fun stopPairing() {
        if (!advertising) return
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: SecurityException) {}
        advertising = false
        if (state == State.ADVERTISING) setState(State.IDLE, "已停止广播")
    }

    /**
     * Phase 2：以 Central 连接已配对的 iPhone 读 ANCS。
     * address 为空时用 [pairedAddress]；都没有则失败。
     */
    @SuppressLint("MissingPermission")
    fun connectAncs(address: String? = null, autoConnect: Boolean = false) {
        val addr = address ?: pairedAddress
        if (addr.isNullOrEmpty()) {
            lastError = "还没有配对的 iPhone，先点「开始配对」"
            setState(State.IDLE, lastError!!)
            return
        }
        stopPairing()
        val device = try { bt.getRemoteDevice(addr) } catch (e: Exception) {
            lastError = "无效地址 $addr"
            setState(State.IDLE, lastError!!)
            return
        }
        deviceName = device.name ?: addr
        // iPhone 用随机私有地址，保存的地址可能已轮转，bondState 查不到不代表 bond 真没了
        // （系统会按 IRK 解析重连）。故只告警不阻断；配对真不存在时 onServicesDiscovered 会失败。
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            log("WARN: $addr bondState=${bondStateName(device.bondState)}（地址可能轮转，仍尝试连接）")
        }
        userInitiatedDisconnect = false
        setState(State.CONNECTING, "连接 $deviceName …")
        gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userInitiatedDisconnect = true
        reconnectHandler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null; nsChar = null; dsChar = null; cpChar = null
        if (state != State.IDLE) setState(State.IDLE, "已断开")
    }

    @SuppressLint("MissingPermission")
    fun close() {
        disconnect()
        stopPairing()
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        if (receiverRegistered) {
            try { context.unregisterReceiver(bondReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    // ====================================================================
    // Phase 1 回调：GATT server + advertiser
    // ====================================================================

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            log("advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            lastError = "广播失败 code=$errorCode（可能缺少 BLUETOOTH_ADVERTISE 权限或设备不支持外设）"
            setState(State.IDLE, lastError!!)
        }
    }

    @SuppressLint("MissingPermission")
    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // iPhone（Central）连过来准备配对——记下地址
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("iPhone 连入配对: ${device.address} (${device.name})")
                if (pairedAddress == null) {
                    prefs.edit().putString(KEY_PAIRED_ADDR, device.address).apply()
                    log("saved paired address: ${device.address}")
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // iPhone 读这个加密特征值触发配对；给个占位应答即可
            try {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0))
            } catch (_: SecurityException) {}
        }
    }

    /** bond 建立后：停广播，自动切 Phase 2 连 ANCS。 */
    @SuppressLint("MissingPermission")
    private fun onBonded(device: BluetoothDevice) {
        prefs.edit().putString(KEY_PAIRED_ADDR, device.address).apply()
        deviceName = device.name ?: device.address
        stopPairing()
        onStatus("配对成功：${deviceName}，正在连接 ANCS…")
        connectAncs(device.address)
    }

    // ====================================================================
    // Phase 2：GATT 连接生命周期 + ANCS 订阅
    // ====================================================================

    @SuppressLint("MissingPermission")
    private fun onConnected(g: BluetoothGatt) {
        setState(State.CONNECTED, "已连接 ${g.device.address}，发现服务中…")
        g.discoverServices()
    }

    @SuppressLint("MissingPermission")
    private fun onDisconnected() {
        nsChar = null; dsChar = null; cpChar = null
        subscribedAt = 0L
        dsBuffer.clear(); dsTimer.values.forEach { dsMergeHandler.removeCallbacks(it) }; dsTimer.clear()
        // 关闭旧 gatt 实例避免泄漏；重连会新建
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        setState(State.IDLE, "连接断开")
        // 非用户主动断开 + 已配对 → 5s 后自动重连（autoConnect=true 让系统持续等 iPhone）
        if (!userInitiatedDisconnect && pairedAddress != null) {
            log("5s 后自动重连…")
            reconnectHandler.postDelayed({
                if (!userInitiatedDisconnect && state == State.IDLE && gatt == null) {
                    connectAncs(autoConnect = true)
                }
            }, 5000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun onServicesDiscovered(g: BluetoothGatt) {
        val ancs = g.getService(SERVICE_ANCS)
        if (ancs == null) {
            val names = g.services.map { it.uuid.toString() }
            lastError = "未找到 ANCS 服务（服务列表: $names）。可能配对未真正建立加密链路"
            setState(State.IDLE, lastError!!)
            return
        }
        log("ANCS service found")
        nsChar = ancs.getCharacteristic(CHAR_NOTIFICATION)
        cpChar = ancs.getCharacteristic(CHAR_CONTROL_POINT)
        dsChar = ancs.getCharacteristic(CHAR_DATA_SOURCE)
        if (nsChar == null || cpChar == null || dsChar == null) {
            lastError = "ANCS 特征值缺失: ns=${nsChar != null} cp=${cpChar != null} ds=${dsChar != null}"
            setState(State.IDLE, lastError!!)
            return
        }
        // 请求更大 MTU 避免 DS 响应被分片（默认 23B，ANCS 属性远超此值）
        log("requesting MTU 512…")
        g.requestMtu(512)
    }

    /** MTU 协商完成后订阅 NS + DS。 */
    @SuppressLint("MissingPermission")
    private fun onMtuReady(g: BluetoothGatt) {
        enableNotification(g, nsChar!!) { nsOk ->
            if (!nsOk) { lastError = "订阅 Notification Source 失败"; setState(State.IDLE, lastError!!); return@enableNotification }
            enableNotification(g, dsChar!!) { dsOk ->
                if (!dsOk) { lastError = "订阅 Data Source 失败"; setState(State.IDLE, lastError!!); return@enableNotification }
                subscribedAt = System.currentTimeMillis()
                setState(State.SUBSCRIBED, "ANCS 就绪——iPhone 通知会出现在「通知历史」")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(
        g: BluetoothGatt, char: BluetoothGattCharacteristic, callback: (Boolean) -> Unit,
    ) {
        g.setCharacteristicNotification(char, true)
        val desc = char.getDescriptor(CCCD) ?: run { callback(false); return }
        pendingWriteCallbacks[desc] = callback
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(desc)
    }

    // ====================================================================
    // ANCS 协议解析（NS / CP / DS）
    // ====================================================================

    private fun onNotificationSource(data: ByteArray) {
        if (data.size < 8) { log("WARN: NS 包过短 ${data.size}B"); return }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val eventId    = buf.get().toInt() and 0xFF
        val eventFlags = buf.get().toInt() and 0xFF
        val categoryId = buf.get().toInt() and 0xFF
        val catCount   = buf.get().toInt() and 0xFF
        val uid        = buf.getInt()
        val catName = CATEGORIES[categoryId] ?: "Other"
        val eventName = when (eventId) {
            EVENT_ADDED -> "ADDED"; EVENT_MODIFIED -> "MODIFIED"; EVENT_REMOVED -> "REMOVED"; else -> "UNKNOWN($eventId)"
        }
        log("NS: $eventName uid=$uid cat=$categoryId($catName) count=$catCount flags=$eventFlags")
        if (eventId == EVENT_REMOVED) return
        requestAttributes(uid)
    }

    @SuppressLint("MissingPermission")
    /** 通过 Control Point 请求通知属性。Title/Subtitle/Message 后面须跟 2B 最大长度。 */
    private fun requestAttributes(uid: Int) {
        val cp = cpChar ?: return
        val payload = ByteBuffer.allocate(1 + 4 + 1 + (1+2)*3 + 2).order(ByteOrder.LITTLE_ENDIAN)
            .put(0)              // CommandID: GetNotificationAttributes
            .putInt(uid)
            .put(ATTR_APP_IDENTIFIER.toByte())      // 0  不需要长度
            .put(ATTR_TITLE.toByte())               // 1
            .putShort(0xFFFF.toShort())              //   max len
            .put(ATTR_SUBTITLE.toByte())            // 2
            .putShort(0xFFFF.toShort())
            .put(ATTR_MESSAGE.toByte())             // 3
            .putShort(0xFFFF.toShort())
            .put(ATTR_DATE.toByte())                // 5  不需要长度
            .put(ATTR_MESSAGE_SIZE.toByte())        // 4  不需要长度
            .array()
        cp.value = payload
        gatt?.writeCharacteristic(cp)
    }

    private fun onDataSource(data: ByteArray) {
        if (data.size < 5) { log("WARN: DS 包过短 ${data.size}B"); return }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // CommandID
        val uid = buf.getInt()

        // 解析当前片段含有的属性（可能只是完整属性列表的子集）
        val frag = mutableMapOf<Int, String>()
        while (buf.remaining() >= 3) {
            val attrId = buf.get().toInt() and 0xFF
            val len = buf.short.toInt() and 0xFFFF
            if (len < 0 || len > buf.remaining()) { log("WARN: 畸形 attr $attrId len=$len rem=${buf.remaining()}"); break }
            val bytes = ByteArray(len); buf.get(bytes)
            frag[attrId] = String(bytes, Charsets.UTF_8)
        }

        // 累积：同一 uid 可能跨多个 Data Source 通知分段传输
        val accumulated = dsBuffer.getOrPut(uid) { mutableMapOf() }
        accumulated.putAll(frag)
        log("DS frag uid=$uid got=${frag.keys.sorted()} total=${accumulated.keys.sorted()}")

        // 有 AppID 的片段是首段；等 300ms 收集后续片段后 flush
        if (frag.containsKey(ATTR_APP_IDENTIFIER)) {
            dsTimer[uid]?.let { dsMergeHandler.removeCallbacks(it) }
            val runnable = Runnable { flushDs(uid) }
            dsTimer[uid] = runnable
            dsMergeHandler.postDelayed(runnable, 300)
        }
    }

    /** 累积后的完整属性 → 落库 */
    private fun flushDs(uid: Int) {
        dsTimer.remove(uid)
        val attrs = dsBuffer.remove(uid) ?: return
        val appId   = attrs[ATTR_APP_IDENTIFIER] ?: return
        val title   = attrs[ATTR_TITLE]
        val subtitle= attrs[ATTR_SUBTITLE]
        val message = attrs[ATTR_MESSAGE]
        val dateStr = attrs[ATTR_DATE]
        log("DS final uid=$uid app=$appId attrs=${attrs.keys.sorted()} title=${title.orEmpty().take(40)} msg=${message.orEmpty().take(60)}")

        val postTime = dateStr?.let(::parseAncsDate) ?: System.currentTimeMillis()
        // 过滤订阅瞬间刷盘的历史通知
        if (subscribedAt > 0 && postTime < subscribedAt - 60_000) {
            log("skip historical uid=$uid app=$appId title=${title.orEmpty().take(20)}")
            return
        }
        val entity = NotificationEntity(
            packageName = appId, postTime = postTime,
            sbnKey = "ancs|$uid", sbnId = uid,
            tag = null, uid = null,
            isClearable = true, isOngoing = false,
            groupKey = null, whenTime = postTime,
            category = null, channelId = null,
            priority = 0, flags = 0,
            template = null, actions = null,
            title = title ?: subtitle, titleBig = null,
            text = message, bigText = null,
            subText = subtitle, infoText = null,
            summaryText = null, textLines = null,
            messages = null, people = null,
            progress = null, progressMax = null,
            showChronometer = false, hasPicture = false,
            importance = null, receivedAt = System.currentTimeMillis(),
            contentHash = listOf(appId, title, subtitle, message).hashCode(),
        )
        onNotification(entity)
    }

    private fun parseAncsDate(iso: String): Long = try {
        val tz = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val clean = iso.replace(Regex("[+-]\\d{4}$"), "").replace("Z", "").replace("T", " ").take(19)
        tz.parse(clean)?.time ?: System.currentTimeMillis()
    } catch (_: Exception) { System.currentTimeMillis() }

    // ====================================================================
    // Phase 2 GATT 回调
    // ====================================================================

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("WARN: 连接状态变更 status=$status newState=$newState")
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> onConnected(g)
                BluetoothProfile.STATE_DISCONNECTED -> onDisconnected()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onServicesDiscovered(g)
            else { lastError = "服务发现失败 status=$status"; setState(State.IDLE, lastError!!) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU changed: $mtu (status=$status)")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onMtuReady(g)
            } else {
                // iOS 经常拒绝 requestMtu(512)；不致命，用默认 MTU 继续
                Log.w(TAG, "MTU negotiation failed (status=$status), proceeding with default")
                onMtuReady(g)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            when (char.uuid) {
                CHAR_NOTIFICATION -> onNotificationSource(char.value)
                CHAR_DATA_SOURCE -> onDataSource(char.value)
                else -> log("debug: 意外 notify ${char.uuid}")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
            val cb = pendingWriteCallbacks.remove(desc)
            cb?.invoke(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("WARN: write ${char.uuid} 失败 status=$status")
        }
    }
}
