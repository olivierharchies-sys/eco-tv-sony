package com.example.braviaeco

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Encodeur / décodeur protobuf minimal (entiers et champs de longueur variable). */
internal class Pb {
    private val out = ByteArrayOutputStream()

    private fun raw(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    fun int(field: Int, value: Int): Pb {
        raw((field shl 3).toLong())
        raw(value.toLong())
        return this
    }

    fun bytes(field: Int, value: ByteArray): Pb {
        raw(((field shl 3) or 2).toLong())
        raw(value.size.toLong())
        out.write(value, 0, value.size)
        return this
    }

    fun str(field: Int, value: String): Pb = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun msg(field: Int, value: Pb): Pb = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        /** Champ → dernière valeur lue : Long (entier) ou ByteArray (texte, sous-message). */
        fun parse(data: ByteArray): Map<Int, Any> {
            val map = HashMap<Int, Any>()
            val cursor = Cursor(data)
            while (cursor.pos < data.size) {
                val tag = cursor.varint(63)
                val field = (tag ushr 3).toInt()
                when ((tag and 7L).toInt()) {
                    0 -> map[field] = cursor.varint(63)
                    1 -> cursor.pos += 8
                    2 -> {
                        val len = cursor.varint(28).toInt()
                        if (len < 0 || cursor.pos + len > data.size) throw IOException("Message tronqué")
                        map[field] = data.copyOfRange(cursor.pos, cursor.pos + len)
                        cursor.pos += len
                    }
                    5 -> cursor.pos += 4
                    else -> throw IOException("Message illisible")
                }
            }
            return map
        }

        /** Lecteur séquentiel d'un message. */
        private class Cursor(val data: ByteArray) {
            var pos = 0

            /** Entier « varint » ; [maxShift] borne le nombre de bits lus (message illisible au-delà). */
            fun varint(maxShift: Int): Long {
                var value = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("Message tronqué")
                    val b = data[pos].toInt() and 0xFF
                    pos++
                    value = value or ((b and 0x7F).toLong() shl shift)
                    if ((b and 0x80) == 0) return value
                    shift += 7
                    if (shift > maxShift) throw IOException("Message illisible")
                }
            }
        }
    }
}
