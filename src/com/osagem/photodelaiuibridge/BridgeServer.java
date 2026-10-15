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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本机 HTTP 桥服务。
 *
 * 暴露给 AIUI 智能体的接口（默认 127.0.0.1:38999）：
 *   GET  /ping                 健康检查
 *   GET  /info                 权限 / 存储 / 媒体统计
 *   GET  /list                 媒体清单（文件系统枚举，非 MediaStore）
 *   GET  /media/<id>           原始字节流（支持 Range，供 <video> 拖动）
 *   GET  /thumb/<id>?w=480     缩略图（JPEG）
 *   GET  /delete/<id>          删除（真删文件；视频连带同名 .txt）
 *   POST /delete?id=<id>       同上
 *   POST /scan                 触发 MediaScanner 重建索引
 *   GET  /diag                 目录体检（排查「照片看不见」）
 *   POST /log?m=xxx            智能体→PC 回读通道（环形缓冲）
 *   GET  /log                  取回缓冲
 *
 * 安全：**只绑定回环地址 127.0.0.1**（局域网内不可达）；即便请求来自非回环来源，
 *       也必须携带 X-Bridge-Token —— 令牌由 BridgeService 在进程启动时随机生成，不是固定值。
 */
public class BridgeServer {

    public static final String TAG = "DcimBridge";
    public static final int PORT = 38999;

    private static final int MAX_LOG = 300;
    private static final List<String> LOG_RING =
            Collections.synchronizedList(new ArrayList<String>());

    private final String token;
    /** 回环监听套接字（同时含 IPv4 127.0.0.1 与 IPv6 ::1，见 start() 注释） */
    private final List<ServerSocket> servers = new ArrayList<ServerSocket>();
    private volatile boolean running = false;
    private ExecutorService pool;

    public BridgeServer(String token) {
        this.token = token;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() throws IOException {
        // ★ 只绑「回环地址」：局域网内不可达（曾绑通配地址便于调试，但会让同网设备直达删除接口）。
        //   必须**两个地址族都绑**：Android 上 InetAddress.getLoopbackAddress() 返回的是 IPv6 回环 ::1，
        //   只绑它会令走 IPv4 字面量 127.0.0.1 的调用方（智能体与自检均如此）连接被拒 → "服务未能启动"。
        //   IPv4 是必须项；IPv6 为兼容项（设备不支持 IPv6 时忽略）。
        IOException lastErr = null;
        for (String addr : new String[] { "127.0.0.1", "::1" }) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName(addr), PORT), 50);
                servers.add(s);
            } catch (IOException e) {
                lastErr = e;
                Log.w(TAG, "bind " + addr + ":" + PORT + " failed: " + e);
            }
        }
        if (servers.isEmpty()) {
            throw (lastErr != null ? lastErr : new IOException("no loopback address available"));
        }
        running = true;
        pool = Executors.newFixedThreadPool(8);
        for (final ServerSocket ss : servers) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(ss);
                }
            }, "bridge-accept-" + ss.getInetAddress().getHostAddress());
            t.setDaemon(true);
            t.start();
        }
        log("服务已启动，监听 127.0.0.1:" + PORT + "（仅回环，本机可达）");
        Log.i(TAG, "bridge listening on 127.0.0.1:" + PORT + " (loopback-only)");
    }

    public void stop() {
        running = false;
        for (ServerSocket s : servers) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        servers.clear();
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop(final ServerSocket ss) {
        while (running) {
            try {
                final Socket s = ss.accept();
                s.setSoTimeout(15000);
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handle(s);
                        } catch (Throwable t) {
                            Log.w(TAG, "handle error: " + t);
                        } finally {
                            try {
                                s.close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                });
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept: " + e);
            }
        }
    }

    /**
     * 写入环形日志（PC 侧 `GET /log` 读回），同时镜像到 Logcat。
     *
     * ★ 这里的文案**刻意不做本地化**（保持中文），这是 Android 的通行做法：
     *   · 日志是**面向开发者**的诊断信息，不是 UI —— 系统的日志/异常信息本身也从不随语言变化；
     *   · 若走资源本地化，排查时日志语言会随设备而变，反而影响检索与比对；
     *   · 调用点遍布静态上下文（BridgeServer / BootReceiver 无 Context），
     *     本地化还需额外引入 Context 依赖，得不偿失。
     *   用户可见的全部文案（首屏、按钮、通知、渠道名、应用名……）一律走
     *   `getString(R.string.*)`，见 res/values/strings.xml 与 res/values-zh/strings.xml。
     */
    public static void log(String msg) {
        String line = System.currentTimeMillis() + " " + msg;
        LOG_RING.add(line);
        while (LOG_RING.size() > MAX_LOG) LOG_RING.remove(0);
        Log.i(TAG, "LOG " + msg);
    }

    /* ============================ 请求处理 ============================ */

    private void handle(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);

        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            writeSimple(out, 400, "text/plain", "bad request".getBytes("UTF-8"));
            return;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            writeSimple(out, 400, "text/plain", "bad request".getBytes("UTF-8"));
            return;
        }
        String method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];

        Map<String, String> headers = new HashMap<String, String>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int i = line.indexOf(':');
            if (i > 0) {
                headers.put(line.substring(0, i).trim().toLowerCase(Locale.US),
                        line.substring(i + 1).trim());
            }
        }

        int bodyLen = 0;
        try {
            String cl = headers.get("content-length");
            if (cl != null) bodyLen = Integer.parseInt(cl.trim());
        } catch (NumberFormatException ignored) {
        }
        String body = "";
        if (bodyLen > 0) {
            byte[] buf = new byte[Math.min(bodyLen, 1 << 20)];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            body = new String(buf, 0, read, "UTF-8");
        }

        // 绝对形式请求行（部分运行时以代理风格发出）：GET http://host:port/path HTTP/1.1
        if (target.startsWith("http://") || target.startsWith("https://")) {
            int idx = target.indexOf("://");
            int slash = target.indexOf('/', idx + 3);
            target = slash < 0 ? "/" : target.substring(slash);
        }

        if ("OPTIONS".equals(method)) {
            preflight(out);
            return;
        }

        String query = "";
        int qi = target.indexOf('?');
        if (qi >= 0) {
            query = target.substring(qi + 1);
            target = target.substring(0, qi);
        }
        Map<String, String> params = parseQuery(query);

        // 非回环来源需要 token（回环来源放行，智能体走回环）
        boolean loopback = s.getInetAddress() != null && s.getInetAddress().isLoopbackAddress();
        if (!loopback && token != null && !token.isEmpty()) {
            String got = headers.get("x-bridge-token");
            if (!token.equals(got)) {
                writeSimple(out, 403, "application/json",
                        ("{\"ok\":false,\"error\":\"token required\"}").getBytes("UTF-8"));
                log("拒绝非回环请求（缺 token）from " + s.getInetAddress());
                return;
            }
        }

        route(method, target, params, headers, body, out, s);
    }

    private void route(String method, String path, Map<String, String> params,
                       Map<String, String> headers, String body, OutputStream out, Socket s)
            throws IOException {

        if ("/ping".equals(path)) {
            json(out, "{" + Json.kv("ok", true) + "," + Json.kv("service", "dcim-bridge")
                    + "," + Json.kv("port", PORT) + "," + Json.kv("ts", System.currentTimeMillis()) + "}");
            return;
        }

        if ("/info".equals(path)) {
            json(out, buildInfo(isTrue(params.get("deep"))));
            return;
        }

        if ("/list".equals(path)) {
            json(out, buildList(params));
            return;
        }

        if ("/diag".equals(path)) {
            json(out, buildDiag());
            return;
        }

        if (path.startsWith("/media/")) {
            serveFile(out, headers, decodeId(path.substring("/media/".length())), false);
            return;
        }

        if (path.startsWith("/thumb/")) {
            serveThumb(out, decodeId(path.substring("/thumb/".length())), params);
            return;
        }

        if (path.startsWith("/delete/")) {
            doDelete(out, decodeId(path.substring("/delete/".length())), s);
            return;
        }

        if ("/delete".equals(path)) {
            String id = params.get("id");
            if (id == null) id = body == null ? null : body.trim();
            doDelete(out, id, s);
            return;
        }

        if ("/scan".equals(path)) {
            json(out, doScan());
            return;
        }

        if ("/log".equals(path)) {
            String m = params.get("m");
            if (m == null || m.isEmpty()) m = body;
            if (m != null && !m.isEmpty() && !"GET-REPORT-ONLY".equals(method)) {
                log("[AGENT] " + m);
            }
            if ("POST".equals(method)) {
                json(out, "{" + Json.kv("ok", true) + "}");
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("{\"count\":").append(LOG_RING.size()).append(",\"lines\":[");
                synchronized (LOG_RING) {
                    for (int i = 0; i < LOG_RING.size(); i++) {
                        if (i > 0) sb.append(',');
                        sb.append(Json.q(LOG_RING.get(i)));
                    }
                }
                sb.append("]}");
                json(out, sb.toString());
            }
            return;
        }

        writeSimple(out, 404, "application/json",
                ("{\"ok\":false,\"error\":\"no route: " + Json.esc(path) + "\"}").getBytes("UTF-8"));
    }

    private String decodeId(String raw) {
        if (raw == null) return null;
        int q = raw.indexOf('?');
        if (q >= 0) raw = raw.substring(0, q);
        return raw;
    }

    /* ============================ 各接口实现 ============================ */

    private String buildInfo(boolean deep) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append(Json.kv("ok", true)).append(",");
        sb.append(Json.kv("port", PORT)).append(",");
        sb.append(Json.kv("storageRoot", MediaIndex.root().getAbsolutePath())).append(",");
        boolean mgr;
        try {
            mgr = android.os.Environment.isExternalStorageManager();
        } catch (Throwable t) {
            mgr = false;
        }
        sb.append(Json.kv("isExternalStorageManager", mgr)).append(",");

        List<File> roots = MediaIndex.scanRoots();
        StringBuilder rr = new StringBuilder("[");
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) rr.append(',');
            rr.append(Json.q(roots.get(i).getAbsolutePath()));
        }
        rr.append("]");
        sb.append(Json.kv("roots", rr.toString())).append(",");

        // 扫描范围可观测：camera=精确拍摄目录 / fallback=回落父目录 / none=均未命中
        String scopeLabel = MediaIndex.scopeLabel();
        sb.append(Json.kv("scope", scopeLabel)).append(",");
        sb.append(Json.kv("scanDirs",
                "camera".equals(scopeLabel) ? "DCIM/Camera,Movies/Camera"
                        : "fallback".equals(scopeLabel) ? "DCIM,Movies" : "")).append(",");

        List<File> all = MediaIndex.scan();
        int img = 0, vid = 0;
        long bytes = 0;
        for (File f : all) {
            if (MediaIndex.isVideo(f)) vid++;
            else img++;
            bytes += f.length();
        }
        sb.append(Json.kv("count", all.size())).append(",");
        sb.append(Json.kv("images", img)).append(",");
        sb.append(Json.kv("videos", vid)).append(",");
        sb.append(Json.kv("bytes", bytes)).append(",");

        List<File> deepAll = null;
        if (deep) {
            deepAll = MediaIndex.scan(true);
            int dimg = 0, dvid = 0;
            for (File f : deepAll) {
                if (MediaIndex.isVideo(f)) dvid++;
                else dimg++;
            }
            sb.append(Json.kv("allCount", deepAll.size())).append(",");
            sb.append(Json.kv("allImages", dimg)).append(",");
            sb.append(Json.kv("allVideos", dvid)).append(",");
        }
        sb.append(Json.kv("ts", System.currentTimeMillis()));
        sb.append("}");
        return sb.toString();
    }

    private String buildList(Map<String, String> params) {
        boolean allRoots = isTrue(params.get("all"));
        List<File> all0 = MediaIndex.scan(allRoots);
        List<File> all = all0;
        String dirFilter = params.get("dir");
        if (dirFilter != null && !dirFilter.isEmpty()) {
            List<File> filtered = new ArrayList<File>();
            for (File f : all0) {
                if (f.getAbsolutePath().contains(dirFilter)) filtered.add(f);
            }
            all = filtered;
        }
        int offset = parseInt(params.get("offset"), 0);
        int limit = parseInt(params.get("limit"), 0);
        if (limit <= 0) limit = all.size();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"total\":").append(all.size())
                .append(",\"scope\":").append(Json.q(allRoots ? "all" : "standard"))
                .append(",\"offset\":").append(offset).append(",\"items\":[");
        int emitted = 0;
        for (int i = offset; i < all.size() && emitted < limit; i++, emitted++) {
            File f = all.get(i);
            boolean video = MediaIndex.isVideo(f);
            if (emitted > 0) sb.append(',');
            sb.append("{");
            sb.append(Json.kv("id", MediaIndex.idOf(f))).append(",");
            sb.append(Json.kv("name", f.getName())).append(",");
            sb.append(Json.kv("type", video ? "video" : "photo")).append(",");
            sb.append(Json.kv("size", f.length())).append(",");
            sb.append(Json.kv("mtime", f.lastModified())).append(",");
            sb.append(Json.kv("path", f.getAbsolutePath())).append(",");
            sb.append(Json.kv("url", "/media/" + MediaIndex.idOf(f)));
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildDiag() {
        File base = MediaIndex.root();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"root\":").append(Json.q(base.getAbsolutePath())).append(",\"dirs\":[");
        File[] fs = base.listFiles();
        int emitted = 0;
        if (fs != null) {
            for (File d : fs) {
                if (!d.isDirectory() || d.getName().startsWith(".")) continue;
                if ("Android".equals(d.getName())) continue;
                int[] stat = countMedia(d, 0, new int[]{0, 0, 0, 0});
                if (stat[0] == 0) continue;
                if (emitted > 0) sb.append(',');
                sb.append("{");
                sb.append(Json.kv("name", d.getName())).append(",");
                sb.append(Json.kv("files", stat[0])).append(",");
                sb.append(Json.kv("images", stat[1])).append(",");
                sb.append(Json.kv("videos", stat[2])).append(",");
                sb.append(Json.kv("bytes", stat[3]));
                sb.append("}");
                emitted++;
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private int[] countMedia(File dir, int depth, int[] acc) {
        if (depth > 2) return acc;
        File[] fs;
        try {
            fs = dir.listFiles();
        } catch (SecurityException e) {
            return acc;
        }
        if (fs == null) return acc;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (f.getName().startsWith(".")) continue;
                countMedia(f, depth + 1, acc);
            } else if (MediaIndex.isVideo(f)) {
                acc[0]++;
                acc[2]++;
                acc[3] += f.length();
            } else if (MediaIndex.isImage(f)) {
                acc[0]++;
                acc[1]++;
                acc[3] += f.length();
            }
        }
        return acc;
    }

    private String doScan() {
        List<File> all = MediaIndex.scan();
        String[] paths = new String[all.size()];
        for (int i = 0; i < all.size(); i++) paths[i] = all.get(i).getAbsolutePath();
        try {
            android.media.MediaScannerConnection.scanFile(
                    BridgeService.appContext(), paths, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "scan failed: " + t);
        }
        log("触发 MediaScanner 重建索引，文件数 " + paths.length);
        return "{" + Json.kv("ok", true) + "," + Json.kv("scanned", paths.length) + "}";
    }

    private void doDelete(OutputStream out, String id, Socket s) throws IOException {
        if (id == null || id.isEmpty()) {
            json(out, "{" + Json.kv("ok", false) + "," + Json.kv("error", "missing id") + "}");
            return;
        }
        File f = MediaIndex.fileOf(id);
        if (f == null) {
            json(out, "{" + Json.kv("ok", false) + "," + Json.kv("error", "bad id / out of scope") + "}");
            return;
        }
        boolean isVideo = MediaIndex.isVideo(f);
        String path = f.getAbsolutePath();
        boolean existed = f.exists();
        boolean ok = existed && f.delete();
        boolean txtDeleted = false;
        if (ok && isVideo) {
            // 与既有 App 行为对齐：眼镜录像会同时生成同名 .txt
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String sibling = (dot > 0 ? name.substring(0, dot) : name) + ".txt";
            File txt = new File(f.getParentFile(), sibling);
            if (txt.exists() && txt.delete()) txtDeleted = true;
        }
        try {
            List<String> ps = new ArrayList<String>();
            ps.add(path);
            android.media.MediaScannerConnection.scanFile(
                    BridgeService.appContext(), ps.toArray(new String[0]), null, null);
        } catch (Throwable ignored) {
        }
        log((ok ? "删除成功 " : "删除失败 ") + path + (txtDeleted ? " (+同名txt)" : ""));
        json(out, "{" + Json.kv("ok", ok) + "," + Json.kv("existed", existed) + ","
                + Json.kv("path", path) + "," + Json.kv("type", isVideo ? "video" : "photo") + ","
                + Json.kv("txtDeleted", txtDeleted) + "}");
    }

    /* ============================ 文件传输 ============================ */

    private void serveFile(OutputStream out, Map<String, String> headers, String id, boolean headOnly)
            throws IOException {
        File f = MediaIndex.fileOf(id);
        if (f == null || !f.exists() || !f.isFile()) {
            writeSimple(out, 404, "application/json",
                    "{\"ok\":false,\"error\":\"not found\"}".getBytes("UTF-8"));
            return;
        }
        long len = f.length();
        String range = headers.get("range");
        long start = 0;
        long end = len - 1;
        int code = 200;

        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1));
                    start = Math.max(0, len - suffix);
                } else {
                    start = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) {
                        end = Long.parseLong(spec.substring(dash + 1));
                    }
                }
                if (start > end || start >= len) {
                    Map<String, String> h = new LinkedHashMap<String, String>();
                    h.put("Content-Range", "bytes */" + len);
                    writeHead(out, 416, "text/plain", 0, h);
                    return;
                }
                end = Math.min(end, len - 1);
                code = 206;
            } catch (NumberFormatException ignored) {
                start = 0;
                end = len - 1;
                code = 200;
            }
        }

        long count = end - start + 1;
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", MediaIndex.mimeOf(f));
        h.put("Accept-Ranges", "bytes");
        h.put("Cache-Control", "no-store");
        if (code == 206) {
            h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
        }
        writeHead(out, code, MediaIndex.mimeOf(f), count, h);
        if (headOnly) return;

        FileInputStream fis = new FileInputStream(f);
        try {
            if (start > 0) {
                long skipped = 0;
                while (skipped < start) {
                    long n = fis.skip(start - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
            }
            byte[] buf = new byte[64 * 1024];
            long left = count;
            while (left > 0) {
                int n = fis.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
            out.flush();
        } finally {
            try {
                fis.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void serveThumb(OutputStream out, String id, Map<String, String> params) throws IOException {
        File f = MediaIndex.fileOf(id);
        if (f == null || !f.exists()) {
            writeSimple(out, 404, "text/plain", "not found".getBytes("UTF-8"));
            return;
        }
        int w = parseInt(params.get("w"), 480);
        if (w <= 0) w = 480;
        if (MediaIndex.isVideo(f)) {
            // 视频缩略图交回原始流（由播放器/解码器首帧处理）
            serveFile(out, new HashMap<String, String>(), id, false);
            return;
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            if (bm == null) {
                serveFile(out, new HashMap<String, String>(), id, false);
                return;
            }
            if (bm.getWidth() > w) {
                int hh = Math.max(1, (int) (bm.getHeight() * (w / (float) bm.getWidth())));
                Bitmap scaled = Bitmap.createScaledBitmap(bm, w, hh, true);
                if (scaled != bm) bm.recycle();
                bm = scaled;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            bm.recycle();
            byte[] bytes = bos.toByteArray();
            writeSimple(out, 200, "image/jpeg", bytes);
        } catch (Throwable t) {
            serveFile(out, new HashMap<String, String>(), id, false);
        }
    }

    /* ============================ HTTP 输出 ============================ */

    private void writeSimple(OutputStream out, int code, String type, byte[] body) throws IOException {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Content-Type", type);
        writeHead(out, code, type, body.length, h);
        out.write(body);
        out.flush();
    }

    private void writeHead(OutputStream out, int code, String type, long len,
                           Map<String, String> extra) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n");
        sb.append("Content-Length: ").append(len).append("\r\n");
        if (type != null && extra.get("Content-Type") == null) {
            sb.append("Content-Type: ").append(type).append("\r\n");
        }
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: *\r\n");
        sb.append("Access-Control-Expose-Headers: *\r\n");
        sb.append("Connection: close\r\n");
        for (Map.Entry<String, String> e : extra.entrySet()) {
            if ("Content-Length".equalsIgnoreCase(e.getKey())) continue;
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
    }

    private void preflight(OutputStream out) throws IOException {
        Map<String, String> h = new LinkedHashMap<String, String>();
        writeHead(out, 204, "text/plain", 0, h);
        out.flush();
    }

    private void json(OutputStream out, String body) throws IOException {
        writeSimple(out, 200, "application/json; charset=utf-8", body.getBytes("UTF-8"));
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            default:  return "OK";
        }
    }

    /* ============================ 工具 ============================ */

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] b = bos.toByteArray();
                int n = b.length;
                if (n > 0 && b[n - 1] == '\r') n--;
                return new String(b, 0, n, "ISO-8859-1");
            }
            bos.write(c);
            if (bos.size() > 8192) break;
        }
        if (bos.size() == 0) return null;
        return new String(bos.toByteArray(), "ISO-8859-1");
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<String, String>();
        if (query == null || query.isEmpty()) return m;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                m.put(k, v);
            }
        }
        return m;
    }

    private static boolean isTrue(String s) {
        return s != null && ("1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s));
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
