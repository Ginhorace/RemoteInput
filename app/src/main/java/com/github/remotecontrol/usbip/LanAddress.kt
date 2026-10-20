package com.github.remotecontrol.usbip

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 取本机的局域网 IPv4 地址，用于在卡片上告诉用户「电脑该连哪个地址」。
 *
 * 之所以要挑而不是直接取第一个：
 * - 回环地址（127.0.0.1）与链路本地地址（169.254.x.x，DHCP 失败时的兜底）对电脑都不可达；
 * - 同时开着 Wi-Fi 与热点 / VPN 时会有多个接口，优先级按「非回环、非链路本地」过滤之后
 *   取第一个可用的 —— 多网卡下不保证挑到用户心里那个，所以卡片上同时给出完整地址让用户核对。
 *
 * 拿不到地址（比如飞行模式）时返回 null，界面显示占位文案而不是空白。
 */
internal object LanAddress {

    fun ipv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
