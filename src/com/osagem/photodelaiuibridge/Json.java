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

/** 极简 JSON 拼装工具（避免引入任何第三方依赖） */
public final class Json {

    private Json() {
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String q(String s) {
        return "\"" + esc(s) + "\"";
    }

    public static String kv(String k, String v) {
        return q(k) + ":" + q(v);
    }

    public static String kv(String k, long v) {
        return q(k) + ":" + v;
    }

    /** 小数：统一用 Locale.US 格式化，避免某些区域把小数点写成逗号导致 JSON 非法 */
    public static String kv(String k, double v) {
        return q(k) + ":" + String.format(java.util.Locale.US, "%.3f", v);
    }

    public static String kv(String k, boolean v) {
        return q(k) + ":" + (v ? "true" : "false");
    }
}
