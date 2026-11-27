package com.skillions.deskpilot.ir

import android.content.Context
import android.hardware.ConsumerIrManager

/**
 * IR actions through the phone's IR blaster (ConsumerIrManager).
 * Accepted code formats:
 *   pronto:0000 006D 0022 0002 0157 00AC ...      (Pronto hex, as found in most IR databases)
 *   nec:<address>:<command>                       e.g. nec:0x04:0x08
 *   samsung32:<address>:<command>                 e.g. samsung32:0x07:0x02
 */
class IrController(context: Context) {
    private val ir = context.getSystemService(ConsumerIrManager::class.java)

    val available: Boolean get() = ir?.hasIrEmitter() == true

    fun transmit(code: String) {
        val mgr = ir ?: error("No IR blaster on this phone")
        require(mgr.hasIrEmitter()) { "No IR blaster on this phone" }
        val (freq, pattern) = parse(code.trim())
        mgr.transmit(freq, pattern)
    }

    companion object {
        fun parse(code: String): Pair<Int, IntArray> {
            val lower = code.lowercase()
            return when {
                lower.startsWith("pronto:") -> pronto(code.substringAfter(':'))
                lower.startsWith("nec:") -> code.split(':').let { 38000 to nec(it[1].num(), it[2].num()) }
                lower.startsWith("samsung32:") -> code.split(':').let { 38000 to samsung32(it[1].num(), it[2].num()) }
                else -> pronto(code)
            }
        }

        private fun String.num() = trim().removePrefix("0x").toInt(16)

        fun pronto(hex: String): Pair<Int, IntArray> {
            val w = hex.trim().split(Regex("\\s+")).map { it.toInt(16) }
            require(w.size > 4 && w[0] == 0) { "Only raw Pronto (0000) codes are supported" }
            val freq = (1_000_000 / (w[1] * 0.241246)).toInt()
            val periodUs = 1_000_000.0 / freq
            val pairs = w.drop(4).map { (it * periodUs).toInt() }
            return freq to pairs.toIntArray()
        }

        private fun bitsLsb(byte: Int, out: MutableList<Int>, one: Int, zero: Int, mark: Int) {
            for (i in 0 until 8) { out += mark; out += if ((byte shr i) and 1 == 1) one else zero }
        }

        fun nec(address: Int, command: Int): IntArray {
            val p = mutableListOf(9000, 4500)
            bitsLsb(address and 0xFF, p, 1690, 560, 560)
            bitsLsb(address.inv() and 0xFF, p, 1690, 560, 560)
            bitsLsb(command and 0xFF, p, 1690, 560, 560)
            bitsLsb(command.inv() and 0xFF, p, 1690, 560, 560)
            p += 560
            return p.toIntArray()
        }

        fun samsung32(address: Int, command: Int): IntArray {
            val p = mutableListOf(4500, 4500)
            bitsLsb(address and 0xFF, p, 1690, 560, 560)
            bitsLsb(address and 0xFF, p, 1690, 560, 560)
            bitsLsb(command and 0xFF, p, 1690, 560, 560)
            bitsLsb(command.inv() and 0xFF, p, 1690, 560, 560)
            p += 560
            return p.toIntArray()
        }
    }
}
