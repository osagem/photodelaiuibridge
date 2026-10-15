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

import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Build;

/** 开机自启：让桥服务无需人工干预即可长期在线 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            BridgeService.ensureStarted(context);
            BridgeServer.log("BOOT_COMPLETED 触发桥服务自启");
        } catch (Throwable t) {
            // 后台启动前台服务可能被系统拒绝，静默即可
        }
    }
}
