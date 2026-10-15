/*
 * Photo Delete AIUI Bridge - companion HTTP bridge for Rokid Glasses AIUI gallery access
 * Copyright (C) 2026 OSAGEM
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.osagem.photodelaiuibridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

/**
 * 常驻前台服务：保持本机 HTTP 桥在线，供 AIUI 智能体随时访问。
 */
public class BridgeService extends Service {

    private static final String CHANNEL_ID = "photo-delete-agent-service";
    private static final int NOTIFY_ID = 0x0DC1;

    /**
     * 访问令牌：**每次进程启动随机生成**，不再硬编码为固定常量。
     *
     * 用途说明：
     *   · 智能体走回环（127.0.0.1）访问，回环来源免 token，故随机化**不影响智能体**；
     *   · 本令牌只服务于两处：本 App 自身的回环 /ping 自检；以及同机其它应用
     *     访问回环端口时的纵深防御（服务只绑回环，跨设备本就不可达）。
     */
    private static final String TOKEN = newToken();

    private static String newToken() {
        byte[] b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /** 供 MainActivity 的 /ping 自检复用同一枚令牌 */
    public static String token() {
        return TOKEN;
    }

    private static Context sCtx;
    private static BridgeServer sServer;

    public static Context appContext() {
        return sCtx;
    }

    public static boolean isRunning() {
        return sServer != null && sServer.isRunning();
    }

    public static void ensureStarted(Context ctx) {
        try {
            Intent i = new Intent(ctx, BridgeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Log.w(BridgeServer.TAG, "ensureStarted failed: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sCtx = getApplicationContext();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sCtx = getApplicationContext();
        try {
            startForegroundCompat();
        } catch (Throwable t) {
            Log.w(BridgeServer.TAG, "startForeground failed: " + t);
        }
        if (sServer == null || !sServer.isRunning()) {
            try {
                sServer = new BridgeServer(TOKEN);
                sServer.start();
            } catch (Throwable t) {
                Log.e(BridgeServer.TAG, "server start failed", t);
                BridgeServer.log("服务启动失败: " + t);
            }
        }
        return START_STICKY;
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        // 1) 带类型（需 manifest 声明 foregroundServiceType）
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                return;
            } catch (Throwable t) {
                Log.w(BridgeServer.TAG, "typed startForeground failed: " + t);
                BridgeServer.log("typed startForeground 失败，回退无类型：" + t);
            }
        }
        // 2) 无类型
        try {
            startForeground(NOTIFY_ID, n);
            return;
        } catch (Throwable t) {
            Log.w(BridgeServer.TAG, "startForeground failed: " + t);
            BridgeServer.log("startForeground 失败（进程可能被系统回收）：" + t);
        }
        // 3) 退而求其次：仅弹通知，服务以普通后台服务方式运行
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFY_ID, n);
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        // 未授权时服务也会被提前拉起（见 MainActivity 跳转指引处的说明），
        // 此时通知要说明"等待授权"，避免用户误以为已经开始读写相册。
        boolean granted = false;
        try {
            granted = Environment.isExternalStorageManager();
        } catch (Throwable ignored) {
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(getString(granted ? R.string.notif_title : R.string.notif_title_waiting))
                .setContentText(getString(granted ? R.string.notif_text : R.string.notif_text_waiting))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true);
        return b.build();
    }

    @Override
    public void onDestroy() {
        if (sServer != null) {
            sServer.stop();
            sServer = null;
        }
        BridgeServer.log("服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
