package com.local.vproxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ProxyService.ACTION_WATCHDOG.equals(intent.getAction())) {
            return;
        }
        ProxyService.startServiceIfEnabled(context, intent.getAction());
    }
}
