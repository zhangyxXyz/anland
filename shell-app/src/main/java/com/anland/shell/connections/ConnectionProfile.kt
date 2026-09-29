package com.anland.shell.connections

import org.json.JSONObject
import java.util.UUID

/** Secrets stay in memory or the encrypted vault, never in Intents or saved UI state. */
data class ConnectionProfile(
    val id:String=UUID.randomUUID().toString(),
    val name:String="",
    val kind:String="local",
    val container:String="",
    val username:String="",
    val host:String="",
    val port:Int=22,
    val auth:String="password",
    val password:String="",
    val privateKey:String="",
    val passphrase:String="",
    val hostKey:String=""
) {
    override fun toString()="ConnectionProfile(id=$id, kind=$kind)"
    fun toJson()=JSONObject().put("id",id).put("name",name).put("kind",kind)
        .put("container",container).put("username",username).put("host",host).put("port",port)
        .put("auth",auth).put("password",password).put("privateKey",privateKey)
        .put("passphrase",passphrase).put("hostKey",hostKey)
    fun valid()=name.isNotBlank() && username.isNotBlank() && !username.any{it.isWhitespace()||it=='\u0000'} &&
        password.length<=4096 && !password.contains('\u0000') && passphrase.length<=4096 && privateKey.length<=256*1024 &&
        when(kind) {
            "local"->container.isNotBlank() && password.isNotEmpty()
            "ssh"->host.isNotBlank() && !host.any{it.isWhitespace()||it=='\u0000'} && port in 1..65535 &&
                ((auth=="password" && password.isNotEmpty()) || (auth=="key" && privateKey.isNotBlank()))
            else->false
        }
    companion object {
        fun fromJson(j:JSONObject)=ConnectionProfile(j.getString("id"),j.getString("name"),j.getString("kind"),
            j.optString("container"),j.getString("username"),j.optString("host"),j.optInt("port",22),
            j.optString("auth","password"),j.optString("password"),j.optString("privateKey"),
            j.optString("passphrase"),j.optString("hostKey"))
    }
}
