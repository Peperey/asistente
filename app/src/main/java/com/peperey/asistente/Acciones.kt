package com.peperey.asistente

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import java.text.Normalizer

object Acciones {
    class Contacto(val nombre: String, val numero: String)

    private fun norm(s: String): String {
        return Normalizer.normalize(s, Normalizer.Form.NFD).lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
    }

    fun tiene(ctx: Context, p: String): Boolean {
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    private fun limpio(n: String): String {
        return n.filter { it.isDigit() || it == '+' }
    }

    fun buscar(ctx: Context, quien: String): Contacto? {
        val buscado = norm(quien)
        if (buscado.isEmpty()) return null
        var exacto: Contacto? = null
        var parcial: Contacto? = null
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val c = ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)
        c?.use {
            while (it.moveToNext()) {
                val nombre = it.getString(0) ?: continue
                val numero = it.getString(1) ?: continue
                val n = norm(nombre)
                if (n.isEmpty()) continue
                if (n == buscado) {
                    if (exacto == null) exacto = Contacto(nombre, numero)
                } else if (n.contains(buscado) || buscado.contains(n)) {
                    if (parcial == null) parcial = Contacto(nombre, numero)
                }
            }
        }
        return exacto ?: parcial
    }

    fun llamar(ctx: Context, c: Contacto): String {
        if (!tiene(ctx, Manifest.permission.CALL_PHONE)) return "Falta el permiso de llamadas en la app"
        val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + limpio(c.numero)))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "Llamando a " + c.nombre
    }

    fun mensaje(ctx: Context, c: Contacto, texto: String): String {
        if (!tiene(ctx, Manifest.permission.SEND_SMS)) return "Falta el permiso de mensajes en la app"
        val sms = SmsManager.getDefault()
        val partes = sms.divideMessage(texto)
        sms.sendMultipartTextMessage(limpio(c.numero), null, partes, null, null)
        return "Mensaje enviado a " + c.nombre
    }
}
