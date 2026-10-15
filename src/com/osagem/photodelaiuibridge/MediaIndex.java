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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import android.os.Environment;
import android.text.TextUtils;

/**
 * 媒体索引：直接对 /sdcard 下的**相机拍摄目录**做文件系统枚举。
 *
 * 扫描范围（精确限定）：
 *   /sdcard/DCIM/Camera/    照片
 *   /sdcard/Movies/Camera/  视频
 * 二者同时缺失时回落到 /sdcard/DCIM/ 与 /sdcard/Movies/（parent 级），保底可用。
 *
 * 为什么不用 MediaStore：宿主升级后 MediaStore 索引可能不完整/路径变更，
 * 导致「有些照片视频看不见」。文件系统枚举不受索引状态影响，所见即所得。
 */
public final class MediaIndex {

    public static final Set<String> IMAGE_EXT = new HashSet<String>();
    public static final Set<String> VIDEO_EXT = new HashSet<String>();

    static {
        String[] img = {"jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp", "dng", "tif", "tiff"};
        for (String e : img) IMAGE_EXT.add(e);
        String[] vid = {"mp4", "m4v", "mov", "3gp", "3gpp", "mkv", "avi", "ts", "webm", "mpg", "mpeg", "wmv", "flv"};
        for (String e : vid) VIDEO_EXT.add(e);
    }

    /** 递归深度上限，防止极端目录结构拖慢扫描 */
    private static final int MAX_DEPTH = 6;
    /** 单次扫描文件数上限 */
    private static final int MAX_FILES = 20000;

    private MediaIndex() {
    }

    public static File root() {
        return Environment.getExternalStorageDirectory();
    }

    public static String extOf(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.US);
    }

    public static boolean isImage(File f) {
        return IMAGE_EXT.contains(extOf(f));
    }

    public static boolean isVideo(File f) {
        return VIDEO_EXT.contains(extOf(f));
    }

    public static boolean isMedia(File f) {
        return isImage(f) || isVideo(f);
    }

    /**
     * 参与扫描的精确子目录 —— 系统相机拍摄产物的实际落点。
     * 真机核实：照片 → DCIM/Camera/，视频 → Movies/Camera/（Pictures/ 下无拍摄产物）。
     */
    private static final String[] SCAN_DIRS = {"DCIM/Camera", "Movies/Camera"};

    /**
     * 回落目录：两个精确子目录**同时**缺失时启用（固件更换落盘路径的场景），
     * 宁可多扫一级父目录，也不让相册整体消失。
     */
    private static final String[] FALLBACK_DIRS = {"DCIM", "Movies"};

    /** 实际生效的扫描根：精确目录优先，全缺失时回落父目录 */
    public static List<File> scanRoots() {
        File base = root();
        List<File> out = existingDirs(base, SCAN_DIRS);
        if (out.isEmpty()) out = existingDirs(base, FALLBACK_DIRS);
        return out;
    }

    private static List<File> existingDirs(File base, String[] rels) {
        List<File> out = new ArrayList<File>();
        for (String r : rels) {
            File d = new File(base, r);
            if (d.isDirectory()) out.add(d);
        }
        return out;
    }

    /**
     * 文件是否落在当前扫描范围内。
     * 读取（/media、/thumb）与删除（/delete）统一走此校验，
     * 避免出现「清单里扫不到、却能被删掉」的不对称。
     */
    public static boolean inScope(File f) {
        if (f == null) return false;
        try {
            return inScopeCanon(f.getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    /** canon 必须是已归一化的绝对路径（fileOf 已做过一次，避免重复系统调用） */
    private static boolean inScopeCanon(String canon) {
        try {
            for (File r : scanRoots()) {
                if (canon.startsWith(r.getCanonicalPath() + File.separator)) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** 当前生效范围的标签：camera = 精确拍摄目录；fallback = 回落父目录；none = 均未命中 */
    public static String scopeLabel() {
        File base = root();
        if (!existingDirs(base, SCAN_DIRS).isEmpty()) return "camera";
        if (!existingDirs(base, FALLBACK_DIRS).isEmpty()) return "fallback";
        return "none";
    }

    /** 全量扫描（仅 DCIM/Camera 与 Movies/Camera），按修改时间倒序（最新在前） */
    public static List<File> scan() {
        return scan(false);
    }

    /**
     * @param allRoots true = 扫描整个外部存储（跳过 Android/ 与隐藏目录），
     *                 纯粹用于排查「拍摄产物跑到非标准目录」的情况；
     *                 注意该模式的产物**不可读不可删**（fileOf 仍受扫描范围约束）
     */
    public static List<File> scan(boolean allRoots) {
        List<File> out = new ArrayList<File>();
        Set<String> seen = new HashSet<String>();
        if (allRoots) {
            collect(root(), out, seen, 0, true);
        } else {
            for (File r : scanRoots()) collect(r, out, seen, 0);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    private static void collect(File dir, List<File> out, Set<String> seen, int depth) {
        collect(dir, out, seen, depth, false);
    }

    private static void collect(File dir, List<File> out, Set<String> seen, int depth, boolean skipSystemDirs) {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        File[] fs;
        try {
            fs = dir.listFiles();
        } catch (SecurityException e) {
            return;
        }
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.startsWith(".")) continue;
                if (skipSystemDirs && "Android".equals(n)) continue;
                collect(f, out, seen, depth + 1, skipSystemDirs);
            } else if (isMedia(f)) {
                String p = f.getAbsolutePath();
                if (seen.add(p)) out.add(f);
            }
        }
    }

    /* ------------------------- id 编解码 ------------------------- */

    /** 绝对路径 → URL 安全 id（base64url 无填充） */
    public static String idOf(File f) {
        return android.util.Base64.encodeToString(
                f.getAbsolutePath().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    /** id → 文件；路径必须落在**当前扫描范围**内（防目录穿越 + 与扫描范围对称） */
    public static File fileOf(String id) {
        if (TextUtils.isEmpty(id)) return null;
        byte[] raw;
        try {
            raw = android.util.Base64.decode(id,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String path = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        File f = new File(path);
        try {
            String canon = f.getCanonicalPath();
            String base = root().getCanonicalPath();
            if (!canon.startsWith(base + File.separator)) return null;
            if (!inScopeCanon(canon)) return null; // D3：范围外拒绝读写
            return new File(canon);
        } catch (Exception e) {
            return null;
        }
    }

    public static String mimeOf(File f) {
        String e = extOf(f);
        if ("jpg".equals(e) || "jpeg".equals(e)) return "image/jpeg";
        if ("png".equals(e)) return "image/png";
        if ("webp".equals(e)) return "image/webp";
        if ("gif".equals(e)) return "image/gif";
        if ("bmp".equals(e)) return "image/bmp";
        if ("heic".equals(e)) return "image/heic";
        if ("heif".equals(e)) return "image/heif";
        if ("dng".equals(e)) return "image/x-adobe-dng";
        if ("mp4".equals(e) || "m4v".equals(e)) return "video/mp4";
        if ("mov".equals(e)) return "video/quicktime";
        if ("3gp".equals(e) || "3gpp".equals(e)) return "video/3gpp";
        if ("mkv".equals(e)) return "video/x-matroska";
        if ("avi".equals(e)) return "video/x-msvideo";
        if ("webm".equals(e)) return "video/webm";
        if ("ts".equals(e)) return "video/mp2t";
        return "application/octet-stream";
    }
}
