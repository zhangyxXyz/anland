package com.anland.shell.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** Standalone instrumentation; no test framework or application-specific fixtures. */
public final class IconInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if ("maintenance".equals(arguments.getString("mode"))) {
                result.putString("stream",MaintenanceProbe.run(getTargetContext()));
            } else if ("keystore".equals(arguments.getString("mode"))) {
                result.putString("stream",KeystoreProbe.run());
            } else if ("launch-credentials".equals(arguments.getString("mode"))) {
                getTargetContext().startActivity(new android.content.Intent(getTargetContext(),com.anland.shell.ShellActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                result.putString("stream",LaunchCredentialProbe.run(getTargetContext(),arguments));
            } else if ("ssh-launch-credentials".equals(arguments.getString("mode"))) {
                getTargetContext().startActivity(new android.content.Intent(getTargetContext(),com.anland.shell.ShellActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                result.putString("stream",SshLaunchCredentialProbe.run(getTargetContext(),arguments));
            } else if ("credentials".equals(arguments.getString("mode"))) {
                result.putString("stream",CredentialProbe.run(getTargetContext(),arguments));
            } else {
                IconRenderingProbe.main(new String[0]);
                result.putString("stream","PASS: icon rendering and ranking\n");
            }
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("stream",android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
