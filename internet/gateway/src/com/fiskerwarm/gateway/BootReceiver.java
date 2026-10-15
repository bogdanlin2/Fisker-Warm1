package com.fiskerwarm.gateway;
import android.content.*;
/** BOOT_COMPLETED is delivered after first unlock. No direct-boot credentials and no stale-command execution. */
public final class BootReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent i){if(!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction()))return;
  if(c.getSharedPreferences(GatewayService.PREF,0).getBoolean("enabled",false))try{GatewayService.start(c);}catch(RuntimeException e){c.getSharedPreferences(GatewayService.PREF,0).edit().putString("state","BOOT_START_BLOCKED_OPEN_APP").apply();}
 }
}
