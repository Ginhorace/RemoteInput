package com.github.remotecontrol.usbip

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB/IP 线协议的常量与编解码（协议版本 1.1.1）。
 *
 * ## 字节序有两种，别混
 * - **USB/IP 自己的报文一律大端**（网络字节序）：`op_common` 的 version / code / status、
 *   数据阶段那 48 字节头的每个字段、设备记录里的 busnum / devnum / speed / VID / PID / bcdDevice。
 * - **USB SETUP 包里的 wValue / wIndex / wLength 是小端**（USB 规范如此）。它是 8 个原始字节，
 *   随 48 字节头一起原样搬运，在这一层不要解释 —— 解释它的地方是 [VirtualUsbDevice]。
 *
 * 这两件事混过一次：版本号按小端读成 `0x1101`，于是 `usbip list -r` 一条设备都列不出来。
 */
object UsbIpProtocol {

    // 监听端口不在这里：它是**设置项**（可在设置里改，见 SettingConstants.USBIP_PORT_*），
    // 3240 只是出厂默认值。协议这一层不关心用户选了哪个端口，[UsbIpServer.start] 接收它。

    /** usbip-win2 的硬门槛：低于这个版本它直接把连接拒掉 */
    const val VERSION = 0x0111

    /** 对外的 busid，电脑端 `usbip attach -r <手机IP> -b 1-1` 用的就是它 */
    const val BUS_ID = "1-1"

    // ── 操作码 ─────────────────────────────────────────────

    const val OP_REQ_DEVLIST = 0x8005
    const val OP_REP_DEVLIST = 0x0005
    const val OP_REQ_IMPORT = 0x8003
    const val OP_REP_IMPORT = 0x0003
    const val OP_CMD_SUBMIT = 0x0001
    const val OP_CMD_UNLINK = 0x0002
    const val OP_RET_SUBMIT = 0x0003
    const val OP_RET_UNLINK = 0x0004

    /** 握手用的 `op_common`：version(2) + code(2) + status(4) */
    const val OP_COMMON_SIZE = 8

    /** 数据阶段 PDU 头：基础 20 字节 + 联合体（按 cmd_submit 对齐到 28）= 48 */
    const val HEADER_SIZE = 48

    /** `usbip_usb_device`：path[256] + busid[32] + 3×u32 + 3×u16 + 6×u8 */
    const val DEVICE_RECORD_SIZE = 312

    /** `usbip_usb_interface`：class / subclass / protocol / padding */
    const val INTERFACE_RECORD_SIZE = 4

    const val BUSID_SIZE = 32

    /** 传输方向：0 = 主机发往设备（OUT），1 = 设备发往主机（IN） */
    const val DIR_OUT = 0
    const val DIR_IN = 1

    /** `usbip_usb_device.speed`：全速设备 */
    const val SPEED_FULL = 2

    // ── ret_submit 的 status：负数 errno ────────────────────

    const val STATUS_OK = 0
    const val STATUS_STALL = -32       // -EPIPE：这条请求设备不支持
    const val STATUS_NOT_FOUND = -2    // -ENOENT
    const val STATUS_NO_DEVICE = -19   // -ENODEV
    const val STATUS_SHUTDOWN = -108   // -ESHUTDOWN

    /**
     * 把一个 ASCII 字符串写成 [size] 字节的定长字段（不足补 0）。
     *
     * 设备记录里的 `path` / `busid` 都是 `char[]`，**不参与字节序转换**，
     * 所以只能按字节填。
     */
    fun nulPadded(text: String, size: Int): ByteArray {
        val out = ByteArray(size)
        val raw = text.toByteArray(Charsets.US_ASCII)
        raw.copyInto(out, endIndex = minOf(raw.size, size))
        return out
    }

    /** 读定长 ASCII 字段（到第一个 0 为止） */
    fun readNulTerminated(bytes: ByteArray): String {
        val end = bytes.indexOfFirst { it == 0.toByte() }.let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, Charsets.US_ASCII)
    }
}

/**
 * 握手报文头 `op_common`。
 *
 * 三种报文共用这个头：请求（`OP_REQ_*`）、应答（`OP_REP_*`）。`status` 只在应答里有意义，
 * 0 = 成功。
 */
data class OpCommon(
    val version: Int,
    val code: Int,
    val status: Int,
) {
    fun toBytes(): ByteArray = ByteBuffer.allocate(UsbIpProtocol.OP_COMMON_SIZE)
        .order(ByteOrder.BIG_ENDIAN)
        .putShort(version.toShort())
        .putShort(code.toShort())
        .putInt(status)
        .array()

    companion object {
        fun parse(bytes: ByteArray): OpCommon {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            return OpCommon(
                version = buf.short.toInt() and 0xFFFF,
                code = buf.short.toInt() and 0xFFFF,
                status = buf.int,
            )
        }
    }
}

/**
 * 数据阶段的 48 字节 PDU 头。
 *
 * 字段按 `struct usbip_header` 原样摊开：基础头 20 字节 + `cmd_submit` 联合体 28 字节。
 * 应答（`ret_submit` / `ret_unlink`）用的是同一个头，只是联合体换了解释，长度仍是 48。
 *
 * @param transferBufferLength 主机为这次传输准备的缓冲区长度。控制传输里它就是 SETUP 包的
 *   `wLength`，**不是**长度校验：设备可以只回更少的字节（短包），主机照收
 * @param setup 控制传输的 8 字节 SETUP 包，非控制传输时内容无意义
 */
data class UsbIpPdu(
    val command: Int,
    val seqnum: Int,
    val devid: Int,
    val direction: Int,
    val ep: Int,
    val transferFlags: Int,
    val transferBufferLength: Int,
    val startFrame: Int,
    val numberOfPackets: Int,
    val interval: Int,
    val setup: ByteArray,
) {

    /** 端点 0 上的一切都是控制传输（枚举、GET_REPORT / SET_REPORT 都走它） */
    val isControl: Boolean get() = ep == 0

    /**
     * 是不是中断 IN 端点（地址 `0x81`）。
     *
     * usbip 的 `ep` 字段有的实现填原始端点地址（`0x81`），有的填去掉方向位的端点号（`1`），
     * 两种都认，免得因为一个约定差异把整条链路卡死在「主机一直等报告」上。
     */
    val isInterruptIn: Boolean
        get() = direction == UsbIpProtocol.DIR_IN && (ep == 0x81 || ep == 1)

    companion object {

        fun parse(header: ByteArray): UsbIpPdu {
            val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            val command = buf.int
            val seqnum = buf.int
            val devid = buf.int
            val direction = buf.int
            val ep = buf.int
            val transferFlags = buf.int
            val transferBufferLength = buf.int
            val startFrame = buf.int
            val numberOfPackets = buf.int
            val interval = buf.int
            val setup = ByteArray(8).also { buf.get(it) }
            return UsbIpPdu(
                command = command,
                seqnum = seqnum,
                devid = devid,
                direction = direction,
                ep = ep,
                transferFlags = transferFlags,
                transferBufferLength = maxOf(transferBufferLength, 0),
                startFrame = startFrame,
                numberOfPackets = numberOfPackets,
                interval = interval,
                setup = setup,
            )
        }

        /**
         * 构造 `ret_submit` 的 48 字节头（不带数据）。
         *
         * 基础头的五个字段必须**原样回抄**请求里的值（尤其 seqnum），主机靠它把应答配回 URB。
         */
        fun buildRetSubmit(
            request: UsbIpPdu,
            status: Int,
            actualLength: Int,
        ): ByteArray = ByteBuffer.allocate(UsbIpProtocol.HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(UsbIpProtocol.OP_RET_SUBMIT)
            .putInt(request.seqnum)
            .putInt(request.devid)
            .putInt(request.direction)
            .putInt(request.ep)
            .putInt(status)
            .putInt(actualLength)
            .putInt(0)   // start_frame
            .putInt(0)   // number_of_packets
            .putInt(0)   // error_count
            .putInt(0)   // padding
            .putInt(0)   // padding
            .array()

        /** 构造 `ret_unlink` 的 48 字节头（联合体里只有 status 有意义） */
        fun buildRetUnlink(request: UsbIpPdu, status: Int): ByteArray =
            ByteBuffer.allocate(UsbIpProtocol.HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(UsbIpProtocol.OP_RET_UNLINK)
                .putInt(request.seqnum)
                .putInt(request.devid)
                .putInt(request.direction)
                .putInt(request.ep)
                .putInt(status)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .array()
    }
}

/** 日志用的十六进制串，最多打 [max] 字节 */
fun ByteArray.toHex(max: Int = 32): String {
    if (isEmpty()) return "(空)"
    val shown = take(max)
    val body = shown.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
    return if (size > max) "$body …(共 $size 字节)" else body
}
