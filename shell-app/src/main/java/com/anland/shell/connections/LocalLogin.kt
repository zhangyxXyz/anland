package com.anland.shell.connections

import android.content.Context
import com.anland.shell.R
import com.anland.shell.ds.DsCli
import com.anland.shell.ds.RootExec
import com.anland.shell.ds.ShellUtils
import org.json.JSONObject

object LocalLogin {
    fun authenticate(context:Context,profile:ConnectionProfile) {
        require(profile.kind=="local" && profile.valid())
        LaunchLogin.ensureRunning(context,profile.container)
        val helper=context.assets.open("authenticate-user.py").bufferedReader().use{it.readText()}
        val bin=DsCli.ds()?:error(context.getString(R.string.credential_container_failed))
        // DS_NO_PROXY is essential: proxied `run` does not forward stdin. Only
        // non-secret helper source appears in argv; the password is pipe input.
        val command="DS_NO_PROXY=1 ${ShellUtils.shQuote(bin)} -n ${ShellUtils.shQuote(profile.container)} run "+
            ShellUtils.shQuote("python3 -c "+ShellUtils.shQuote(helper))
        val input=JSONObject().put("username",profile.username).put("password",profile.password).toString().toByteArray(Charsets.UTF_8)
        val result=try{RootExec.exec(command,20000,input)}finally{input.fill(0)}
        val response=runCatching{JSONObject(result.stdout.trim())}.getOrNull()
        if(!result.ok || response?.optBoolean("ok")!=true) {
            val code=response?.optInt("code",-1)?:-1
            error(context.getString(if(code<0)R.string.credential_auth_unavailable else R.string.credential_auth_failed))
        }
    }
}
