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

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HEVC → H.264 硬件转码器（方案见 docs/AIUI-视频转码-落地方案.md）。
 *
 * 为什么必须转码：AIUI 运行时的自研 MP4 管线只认 **H.264/AVC + AAC-LC 的 progressive MP4**
 * （so 字符串：`MP4 has no H.264 video track` / `Only AAC-LC is supported by the V1 profile`），
 * 而眼镜实拍视频是 HEVC。运行时既不用系统解码器、也没静态链接开源解码器，
 * 所以**唯一出路是桥端把 HEVC 硬解 → 缩放/旋转 → AVC 硬编 → MP4 落缓存**，再经 HTTP 送给播放器。
 *
 * 管线（解码器 → SurfaceTexture → EGL/GL → 编码器输入面）：
 * <pre>
 *   MediaExtractor(HEVC) → MediaCodec 解码器 ──► SurfaceTexture(OES 纹理)
 *                                                      │
 *                            EGL 上下文 + GL 缩放/旋转 shader
 *                                                      ▼
 *                                   MediaCodec AVC 编码器 inputSurface
 *                                                      │
 *                      音频轨：AAC-LC 样本直接 remux ──► MediaMuxer → MP4 缓存
 * </pre>
 *
 * 设计要点（与方案决策的对应）：
 *   · 输出 **720p 级**（长边 ≤1280、短边 ≤720、均 16 对齐）—— 运行时对单个 access unit 有字节上限，
 *     3072x1728 直接编码单帧过大；720p 对 480x640 的显示面已绰绰有余（D2）。
 *   · **按需触发**（切到该视频才转），转完写缓存，二次播放秒开（D3）。
 *   · 音频**不重编码**：AAC-LC 样本原样 remux —— 省一次有损转换，也避开音频编码器兼容问题。
 *   · **物理旋转**：源视频带 rotation（眼镜竖拍 = 90），但运行时播放器读不读旋转元数据未知，
 *     因此不依赖 `setOrientationHint`，而是直接在 GL 里把画面转正、并把输出尺寸设为旋转后的宽高
 *     （竖屏源 → 720x1280）。这样播放端不需要任何旋转支持。
 *   · **任务串行化**：单线程队列 —— 本机解码/编码各 8 并发，但并发任务会互相抢硬件、谁也不快。
 *   · 缓存：`/sdcard/.dcimbridge/transcode/<sha1(路径)>_<mtime>_<高>.mp4`，1GB 上限 + LRU（D5）。
 */
public final class Transcoder {

    private static final String TAG = "DcimBridge";

    /* ---------------------------- 常量 ---------------------------- */

    /** 输出面积上限 ≈ 480p：长边 854 / 短边 480（用户 2026-10-16 定稿，转码提速） */
    public static final int MAX_LONG_SIDE = 854;
    public static final int MAX_SHORT_SIDE = 480;

    /** 缓存上限 1 GB（D5） */
    public static final long CACHE_LIMIT_BYTES = 1024L * 1024 * 1024L;

    /** 缓存目录（隐藏目录：MediaScanner 忽略，用户可整目录清理） */
    public static final String CACHE_DIR_NAME = ".dcimbridge/transcode";

    /**
     * 任务表上限：已到终态（done/failed/canceled/none）的旧任务按创建时间淘汰。
     * status() 对已有缓存的视频每次轮询都会经 doneJob 造一个新 done Job 入表，
     * 智能体播放期间高频轮询 → 若无上限，进程常驻数周会慢性增长。
     * queued/running 的任务永不被淘汰（轮询结果不能丢）。
     */
    private static final int MAX_JOBS = 32;

    /** probe 缓存条数上限（插入序淘汰最旧；单条仅几十字节，防慢性增长即可） */
    private static final int MAX_PROBE = 256;

    /**
     * 旋转方向换算开关（★ 真机实测定死，勿凭文档猜）。
     *
     * 实测（2026-10-16，vid-20261015-190326-c5.mp4）：同一个文件，
     *   · ffprobe 报 rotation=90（其语义 = 逆时针旋转 90° 后为正确显示）；
     *   · Android `MediaExtractor` 报 `rotation-degrees` = **270**。
     * 两者互为补角，而"哪边是正"最终以画面比对为准：
     *   产物首帧与 ffmpeg autoorient 的正确首帧做 PSNR 比对，**需要顺时针 90°** 才重合（29.9dB vs <9dB）。
     * → 结论：Android 这里的 rotation 数值要按"**逆时针**"解释，即 θ(逆时针) = R。
     *
     * 本开关的含义 = "Android 的 rotation 是否按顺时针解释"：
     *   true  → θ = (360 − R) % 360
     *   false → θ = R % 360   ← 当前取值（实测正确）
     * 若换设备/固件后发现画面躺倒（差 180°），翻转本开关并看日志里的 θ 值即可。
     */
    private static final boolean ROTATION_ANDROID_IS_CLOCKWISE = false;

    /** 单个任务的墙钟保护：视频时长 ×6 + 30s */
    private static final long JOB_TIMEOUT_MARGIN_MS = 30_000L;

    /**
     * 待转码任务持久化文件（缓存目录下 pending.txt，每行一个媒体 id）。
     * ★ 背景：LMK 在系统内存紧张时会杀掉本前台服务（实测 7 分钟视频转码中被杀两次），
     *   任务全在内存里，进程一死任务就丢 —— 用户再回到该视频就得从头再等。
     *   把"排队中/进行中"的任务 id 落盘，服务重启（START_STICKY）后自动续转，
     *   用户稍后再来大概率已转完（幂等：submit 对运行中任务复用、对已完成直接回缓存）。
     */
    private static final File PENDING_FILE = new File(cacheDir(), "pending.txt");

    public static final String STATE_NONE = "none";
    public static final String STATE_QUEUED = "queued";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_DONE = "done";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_CANCELED = "canceled";

    /* ---------------------------- 全局状态 ---------------------------- */

    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "bridge-transcode");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** id → 任务（保留历史，供智能体轮询取结果） */
    private static final Map<String, Job> JOBS = new HashMap<String, Job>();
    /** 源文件 key → 任务 id（同源复用同一任务，幂等） */
    private static final Map<String, String> KEY_TO_JOB = new HashMap<String, String>();
    /** probe 缓存：path@mtime@size → Info，避免每次播放都开 MediaExtractor（有上限，见 MAX_PROBE） */
    private static final Map<String, Info> PROBE_CACHE =
            new LinkedHashMap<String, Info>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Info> eldest) {
                    return size() > MAX_PROBE;
                }
            };

    /** 清理缓存时留出的余量（不把磁盘用到顶） */
    private static final long CACHE_PURGE_SLACK = 200L * 1024 * 1024;

    private Transcoder() {
    }

    /* ============================ 探测 ============================ */

    /** 媒体轨道信息（/probe 的输出载体） */
    public static final class Info {
        public String container = "mp4";
        public String videoMime = null;
        public String audioMime = null;
        /** avc | hevc | mpeg4 | h263 | vp8 | vp9 | av1 | other | none */
        public String videoCodec = "none";
        /** aac-lc | other | none */
        public String audioCodec = "none";
        public boolean hasVideo = false;
        public boolean hasAudio = false;
        public int width = 0;
        public int height = 0;
        /** 显示所需的顺时针旋转角度（Android rotation-degrees） */
        public int rotation = 0;
        public long durationUs = 0;
        public long bitrate = 0;
        public int frameRate = 0;
        public int audioSampleRate = 0;
        public int audioChannels = 0;

        /** 旋转归正后的目标输出尺寸 */
        public int outWidth = 0;
        public int outHeight = 0;
        /** 运行时能否**直接**播（H.264 + AAC-LC / 无音轨） */
        public boolean playable = false;
        /** playable=false 时的原因码 */
        public String reason = null;
    }

    /** 读容器/轨道元信息（不开解码器，毫秒级） */
    public static Info probe(File f) {
        String key = f.getAbsolutePath() + "@" + f.lastModified() + "@" + f.length();
        synchronized (PROBE_CACHE) {
            Info hit = PROBE_CACHE.get(key);
            if (hit != null) return hit;
        }
        Info info = new Info();
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            info.container = extLower(f);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && !info.hasVideo) {
                    info.hasVideo = true;
                    info.videoMime = mime;
                    info.videoCodec = codecOf(mime);
                    info.width = intOrDefault(fmt, MediaFormat.KEY_WIDTH, 0);
                    info.height = intOrDefault(fmt, MediaFormat.KEY_HEIGHT, 0);
                    info.rotation = intOrDefault(fmt, MediaFormat.KEY_ROTATION, 0);
                    info.durationUs = longOrDefault(fmt, MediaFormat.KEY_DURATION, 0);
                    info.bitrate = intOrDefault(fmt, MediaFormat.KEY_BIT_RATE, 0);
                    info.frameRate = intOrDefault(fmt, "frame-rate", 0);
                } else if (mime.startsWith("audio/") && !info.hasAudio) {
                    info.hasAudio = true;
                    info.audioMime = mime;
                    info.audioCodec = "audio/mp4a-latm".equals(mime) ? "aac-lc" : "other";
                    info.audioSampleRate = intOrDefault(fmt, MediaFormat.KEY_SAMPLE_RATE, 0);
                    info.audioChannels = intOrDefault(fmt, MediaFormat.KEY_CHANNEL_COUNT, 0);
                }
            }
        } catch (Throwable t) {
            info.reason = "probe-failed";
            Log.w(TAG, "probe failed: " + f + " -> " + t);
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }

        // 目标尺寸按"旋转归正后"的画面算
        boolean swap = info.rotation == 90 || info.rotation == 270;
        int dispW = swap ? info.height : info.width;
        int dispH = swap ? info.width : info.height;
        int[] out = targetSize(dispW, dispH);
        info.outWidth = out[0];
        info.outHeight = out[1];

        info.playable = "avc".equals(info.videoCodec)
                && ("none".equals(info.audioCodec) || "aac-lc".equals(info.audioCodec));
        if (!info.playable && info.reason == null) {
            if (!info.hasVideo) info.reason = "no-video-track";
            else if (!"avc".equals(info.videoCodec)) info.reason = info.videoCodec + "-not-supported";
            else info.reason = "audio-" + info.audioCodec + "-not-supported";
        }
        synchronized (PROBE_CACHE) {
            PROBE_CACHE.put(key, info);
        }
        return info;
    }

    private static int intOrDefault(MediaFormat fmt, String key, int def) {
        try {
            if (!fmt.containsKey(key)) return def;
            return fmt.getInteger(key);
        } catch (Throwable t) {
            try {
                return (int) fmt.getFloat(key);
            } catch (Throwable t2) {
                return def;
            }
        }
    }

    private static long longOrDefault(MediaFormat fmt, String key, long def) {
        try {
            if (!fmt.containsKey(key)) return def;
            return fmt.getLong(key);
        } catch (Throwable t) {
            return intOrDefault(fmt, key, (int) def);
        }
    }

    private static String codecOf(String mime) {
        if ("video/avc".equals(mime)) return "avc";
        if ("video/hevc".equals(mime)) return "hevc";
        if ("video/mp4v-es".equals(mime)) return "mpeg4";
        if ("video/3gpp".equals(mime)) return "h263";
        if ("video/x-vnd.on2.vp8".equals(mime)) return "vp8";
        if ("video/x-vnd.on2.vp9".equals(mime)) return "vp9";
        if ("video/av01".equals(mime)) return "av1";
        return "other";
    }

    private static String extLower(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.US);
    }

    /**
     * 目标尺寸：长边 ≤1280、短边 ≤720、宽高均 16 对齐（编码器 block-size 16x16）。
     * 源更小时不放大（避免无意义的插值损耗）。
     */
    private static int[] targetSize(int w, int h) {
        if (w <= 0 || h <= 0) return new int[] {0, 0};
        int tw = w;
        int th = h;
        if (w > MAX_LONG_SIDE || h > MAX_LONG_SIDE || Math.min(w, h) > MAX_SHORT_SIDE) {
            double s1 = MAX_LONG_SIDE / (double) Math.max(w, h);
            double s2 = MAX_SHORT_SIDE / (double) Math.min(w, h);
            double s = Math.min(s1, s2);
            if (s < 1.0) {
                tw = (int) Math.round(w * s);
                th = (int) Math.round(h * s);
            }
        }
        tw = Math.max(16, tw & ~15);
        th = Math.max(16, th & ~15);
        return new int[] {tw, th};
    }

    /* ============================ 缓存 ============================ */

    public static File cacheDir() {
        File d = new File(MediaIndex.root(), CACHE_DIR_NAME);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** 缓存文件名：sha1(绝对路径) + 源 mtime（源变了自动失效）+ 目标高 */
    public static File cacheFile(File src, int outHeight) {
        String name = sha1(src.getAbsolutePath()) + "_" + src.lastModified() + "_" + outHeight + ".mp4";
        return new File(cacheDir(), name);
    }

    /** 该文件对应的可播缓存（不存在则 null） */
    public static File cachedFor(File src) {
        Info info = probe(src);
        if (info.outHeight <= 0) return null;
        File c = cacheFile(src, info.outHeight);
        return c.exists() && c.length() > 0 ? c : null;
    }

    public static long cacheBytes() {
        long sum = 0;
        File[] fs = cacheDir().listFiles();
        if (fs != null) {
            for (File f : fs) sum += f.length();
        }
        return sum;
    }

    public static int cacheFiles() {
        File[] fs = cacheDir().listFiles();
        return fs == null ? 0 : fs.length;
    }

    /** 超限时按 mtime 从旧到新删除（跳过正在写入的产物）；返回释放字节数 */
    public static long purgeCache() {
        List<File> fs = new ArrayList<File>();
        File[] all = cacheDir().listFiles();
        if (all != null) Collections.addAll(fs, all);
        Collections.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long total = 0;
        for (File f : fs) total += f.length();
        long target = CACHE_LIMIT_BYTES - CACHE_PURGE_SLACK;
        long removed = 0;
        for (File f : fs) {
            if (total <= target) break;
            if (isInUse(f)) continue;
            long n = f.length();
            if (f.delete()) {
                total -= n;
                removed += n;
            }
        }
        if (removed > 0) log("缓存清理：释放 " + (removed / 1024 / 1024) + " MB");
        return removed;
    }

    private static boolean isInUse(File f) {
        String path = f.getAbsolutePath();
        synchronized (JOBS) {
            for (Job j : JOBS.values()) {
                if (STATE_RUNNING.equals(j.state) && path.equals(j.outputPath)) return true;
            }
        }
        return false;
    }

    /** 删除某条源文件对应的全部缓存（源被删除时联动调用） */
    public static void dropCacheFor(File src) {
        File[] fs = cacheDir().listFiles();
        if (fs == null) return;
        String prefix = sha1(src.getAbsolutePath()) + "_";
        for (File f : fs) {
            if (f.getName().startsWith(prefix) && f.delete()) {
                log("联动删除转码缓存 " + f.getName());
            }
        }
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static void log(String m) {
        BridgeServer.log(m);
    }

    /** 任务表超限时淘汰最旧的终态任务（queued/running 不动） */
    private static void evictJobs() {
        synchronized (JOBS) {
            if (JOBS.size() <= MAX_JOBS) return;
            List<Job> finished = new ArrayList<Job>();
            for (Job j : JOBS.values()) {
                if (STATE_QUEUED.equals(j.state) || STATE_RUNNING.equals(j.state)) continue;
                finished.add(j);
            }
            Collections.sort(finished, new Comparator<Job>() {
                @Override
                public int compare(Job a, Job b) {
                    return Long.compare(a.createdAt, b.createdAt);
                }
            });
            int remove = JOBS.size() - MAX_JOBS;
            for (int i = 0; i < finished.size() && remove > 0; i++) {
                Job j = finished.get(i);
                JOBS.remove(j.id);
                KEY_TO_JOB.values().remove(j.id);
                remove--;
            }
        }
    }

    /* ============================ 任务 ============================ */

    public static final class Job {
        public final String id;
        public final long createdAt = System.currentTimeMillis();
        public volatile String state = STATE_QUEUED;
        public volatile float progress = 0f;
        public volatile long processedMs = 0;
        public volatile long durationMs = 0;
        public volatile long outBytes = 0;
        public volatile long elapsedMs = 0;
        public volatile String error = null;
        public volatile boolean canceled = false;
        public volatile String outputPath = null;
        public volatile int outWidth = 0;
        public volatile int outHeight = 0;

        Job(String id) {
            this.id = id;
        }
    }

    /** 源文件 key（路径 + mtime） */
    private static String keyOf(File src) {
        return src.getAbsolutePath() + "@" + src.lastModified();
    }

    private static String jobIdFor(File src, File out) {
        return sha1(keyOf(src) + "#" + out.getName()).substring(0, 16);
    }

    /* ------------------------ 待转码任务持久化 ------------------------ */

    private static List<String> readPending() {
        List<String> ids = new ArrayList<String>();
        try {
            BufferedReader r = new BufferedReader(new FileReader(PENDING_FILE));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() > 0 && !ids.contains(line)) ids.add(line);
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
        }
        return ids;
    }

    private static void writePending(List<String> ids) {
        try {
            BufferedWriter w = new BufferedWriter(new FileWriter(PENDING_FILE, false));
            try {
                for (String id : ids) {
                    w.write(id);
                    w.newLine();
                }
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 任务入队时落盘；到达终态（done/canceled/failed）后从盘上移除 */
    private static void persistPending(String id) {
        List<String> ids = readPending();
        if (!ids.contains(id)) {
            ids.add(id);
            writePending(ids);
        }
    }

    private static void unpersistPending(String id) {
        List<String> ids = readPending();
        if (ids.remove(id)) writePending(ids);
    }

    /**
     * 续转的内存门禁：MemAvailable 低于此值就**不自动开转码器**（转码期 RSS 峰值实测 70~80MB，
     * 低水位开转 = 「续转→LMK 杀→重启→再续转」死循环，实测 6~7 秒被杀一轮）。
     * 门禁只管**后台自动续转**；用户在前台主动点播仍照常提交（体验优先，由任务超时兜底）。
     */
    private static final long RESUME_MIN_MEM_AVAILABLE = 384L * 1024 * 1024;

    /** 读 /proc/meminfo 的 MemAvailable（读不到返回 Long.MAX_VALUE = 不设限） */
    private static long memAvailableBytes() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("MemAvailable:")) {
                        return Long.parseLong(line.trim().split("\\s+")[1]) * 1024L;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
        }
        return Long.MAX_VALUE;
    }

    /**
     * 服务重启后续转：把上一次进程"死前还没转完"的任务重新排队。
     * 由 BridgeService.onCreate 延迟调用（给系统一点恢复余量）。
     * 幂等：源已删/已有缓存 → 顺带清掉记录；submit 自身对运行中任务复用。
     * ★ id→路径用**直接 base64 解码**，不走 MediaIndex.fileOf：
     *   刚重启时索引/范围未必就绪，fileOf 会返回 null，若据此清理会把未完成任务误删
     *   （id 是我们自己写入的，无不可信来源，直接解码安全）。
     * ★ 内存门禁：MemAvailable 不足时直接返回（pending.txt 保留，等下一轮重试），
     *   避免「续转→被 LMK 杀→重启→再续转」死循环（2026-10-16 实测 6~7 秒被杀一轮）。
     *
     * @return true = 已处理完（含无待办）；false = 因低内存暂缓，需稍后重试
     */
    public static boolean reenqueuePending() {
        List<String> ids = readPending();
        if (ids.isEmpty()) return true;
        int n = 0;
        for (String id : ids) {
            File src = fileOfRaw(id);
            if (src == null) {
                // 解不开（历史版本落过 jobId）→ 直接清掉，避免垃圾行常驻
                unpersistPending(id);
                continue;
            }
            if (!src.exists() || !src.isFile()) {
                unpersistPending(id);
                continue;
            }
            if (cachedFor(src) != null) {
                // 已有缓存 → 只需清记录（含「转完但未来得及清 pending 就被杀」的残留），无需门禁
                unpersistPending(id);
                continue;
            }
            // 真正要开转码器才受内存门禁管；不足时保留 pending、整批暂缓
            long avail = memAvailableBytes();
            if (avail < RESUME_MIN_MEM_AVAILABLE) {
                log("系统内存不足（可用 " + (avail / 1024 / 1024) + "MB），暂延续转，稍后重试");
                return false;
            }
            submit(src, false);
            n++;
        }
        if (n > 0) log("服务重启续转 " + n + " 个未完成转码任务");
        return true;
    }

    /** 与 MediaIndex 的 id 编码一致（URL_SAFE / NO_WRAP / NO_PADDING 的 base64 → 路径） */
    private static File fileOfRaw(String id) {
        try {
            byte[] raw = android.util.Base64.decode(id,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            return new File(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 提交转码（幂等）：已有缓存且非 force → done；同源已在跑 → 复用同一任务 */
    public static Job submit(File src, boolean force) {
        Info info = probe(src);
        if (info.outHeight <= 0) {
            Job bad = new Job("bad-" + System.currentTimeMillis());
            bad.state = STATE_FAILED;
            bad.error = "unprobeable";
            return bad;
        }
        File out = cacheFile(src, info.outHeight);

        if (out.exists() && out.length() > 0 && !force) {
            return doneJob(src, out, info);
        }

        String key = keyOf(src);
        synchronized (JOBS) {
            String existing = KEY_TO_JOB.get(key);
            if (existing != null) {
                Job j = JOBS.get(existing);
                if (j != null && (STATE_QUEUED.equals(j.state) || STATE_RUNNING.equals(j.state))) {
                    return j;
                }
            }
        }

        final Job job = new Job(jobIdFor(src, out));
        job.outputPath = out.getAbsolutePath();
        job.durationMs = info.durationUs / 1000;
        job.outWidth = info.outWidth;
        job.outHeight = info.outHeight;
        synchronized (JOBS) {
            JOBS.put(job.id, job);
            KEY_TO_JOB.put(key, job.id);
        }
        // ★ 落盘必须用「媒体 id」（MediaIndex.idOf = base64url(绝对路径)），不能用 job.id：
        //   续转 reenqueuePending 按 id 解码出源路径重新排队，jobId 解不开 = 任务永远续不上
        //   （2026-10-16 实测踩坑：1:57 新视频转码中进程被杀，pending 里是 jobId，续转静默失效）。
        persistPending(MediaIndex.idOf(src));

        final File srcF = src;
        final Info infoF = info;
        final File outF = out;
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    runJob(job, srcF, infoF, outF);
                    job.outBytes = outF.exists() ? outF.length() : 0;
                    job.state = STATE_DONE;
                    job.progress = 1f;
                    job.processedMs = job.durationMs;
                } catch (CanceledException ce) {
                    job.state = STATE_CANCELED;
                    job.error = "canceled";
                    //noinspection ResultOfMethodCallIgnored
                    outF.delete();
                } catch (Throwable t) {
                    job.state = STATE_FAILED;
                    job.error = String.valueOf(t.getMessage() != null ? t.getMessage() : t);
                    Log.w(TAG, "transcode failed", t);
                    log("转码失败 " + srcF.getName() + "：" + job.error);
                    //noinspection ResultOfMethodCallIgnored
                    outF.delete();
                } finally {
                    job.elapsedMs = System.currentTimeMillis() - t0;
                    unpersistPending(MediaIndex.idOf(srcF));
                    evictJobs();
                    if (STATE_DONE.equals(job.state)) {
                        log("转码完成 " + srcF.getName() + " → " + job.outWidth + "x" + job.outHeight
                                + "，" + (job.outBytes / 1024) + " KB，耗时 "
                                + (job.elapsedMs / 1000.0) + "s（" + speedX(job) + "× 实时）");
                    }
                    purgeCache();
                }
            }
        });
        log("转码排队 " + src.getName() + "（源 " + info.videoCodec + " " + info.width + "x" + info.height
                + " 旋转 " + info.rotation + "° → 目标 " + info.outWidth + "x" + info.outHeight
                + "，" + (info.durationUs / 1000) + "ms）");
        return job;
    }

    private static Job doneJob(File src, File out, Info info) {
        Job done = new Job(jobIdFor(src, out));
        done.state = STATE_DONE;
        done.progress = 1f;
        done.outBytes = out.length();
        done.outputPath = out.getAbsolutePath();
        done.durationMs = info.durationUs / 1000;
        done.processedMs = done.durationMs;
        done.outWidth = info.outWidth;
        done.outHeight = info.outHeight;
        synchronized (JOBS) {
            JOBS.put(done.id, done);
            KEY_TO_JOB.put(keyOf(src), done.id);
        }
        evictJobs();
        return done;
    }

    public static String speedX(Job job) {
        if (job.elapsedMs <= 0) return "0";
        return String.format(Locale.US, "%.1f", job.durationMs / (double) job.elapsedMs);
    }

    /** 查询某源文件当前的任务（没有任务但有缓存 → 构造 done；都没有 → none） */
    public static Job status(File src) {
        synchronized (JOBS) {
            String id = KEY_TO_JOB.get(keyOf(src));
            if (id != null) {
                Job j = JOBS.get(id);
                if (j != null) return j;
            }
        }
        Info info = probe(src);
        File out = info.outHeight > 0 ? cacheFile(src, info.outHeight) : null;
        if (out != null && out.exists() && out.length() > 0) {
            return doneJob(src, out, info);
        }
        Job j = new Job(jobIdFor(src, out == null ? new File("none") : out));
        j.state = STATE_NONE;
        j.outWidth = info.outWidth;
        j.outHeight = info.outHeight;
        j.durationMs = info.durationUs / 1000;
        return j;
    }

    public static Job cancel(File src) {
        synchronized (JOBS) {
            String id = KEY_TO_JOB.get(keyOf(src));
            if (id == null) return null;
            Job j = JOBS.get(id);
            if (j != null && (STATE_QUEUED.equals(j.state) || STATE_RUNNING.equals(j.state))) {
                j.canceled = true;
                log("转码取消 " + src.getName());
            }
            return j;
        }
    }

    /** 源文件被删除时清理任务与探测缓存 */
    public static void forget(File src) {
        synchronized (JOBS) {
            String id = KEY_TO_JOB.remove(keyOf(src));
            if (id != null) JOBS.remove(id);
        }
        synchronized (PROBE_CACHE) {
            List<String> del = new ArrayList<String>();
            String p = src.getAbsolutePath() + "@";
            for (String k : PROBE_CACHE.keySet()) {
                if (k.startsWith(p)) del.add(k);
            }
            for (String k : del) PROBE_CACHE.remove(k);
        }
    }

    /* ============================ 转码主流程 ============================ */

    private static final class CanceledException extends Exception {
    }

    private static void checkCancel(Job job) throws CanceledException {
        if (job.canceled || Thread.currentThread().isInterrupted()) throw new CanceledException();
    }

    private static void runJob(Job job, File src, Info info, File outFile) throws Exception {
        job.state = STATE_RUNNING;
        long deadline = System.currentTimeMillis() + job.durationMs * 6 + JOB_TIMEOUT_MARGIN_MS;

        MediaExtractor ex = new MediaExtractor();
        MediaExtractor exAudio = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        Surface encoderSurface = null;
        GlPipeline gl = null;
        EncoderDrain drain = null;
        File tmp = new File(outFile.getAbsolutePath() + ".tmp");
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();

        try {
            ex.setDataSource(src.getAbsolutePath());
            int vIdx = -1;
            int aIdx = -1;
            MediaFormat vFmt = null;
            MediaFormat aFmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && vIdx < 0) {
                    vIdx = i;
                    vFmt = f;
                } else if (mime.startsWith("audio/") && aIdx < 0) {
                    aIdx = i;
                    aFmt = f;
                }
            }
            if (vIdx < 0 || vFmt == null) throw new IllegalStateException("no video track");

            int outW = info.outWidth;
            int outH = info.outHeight;
            if (outW < 16 || outH < 16) throw new IllegalStateException("bad output size");

            // ---------------- 1) 音频：独立 extractor（AAC-LC 原样 remux，不重编码） ----------------
            // ★ 必须用**独立**的 MediaExtractor：selectTrack 是"加选"不是"排他"，
            //   在同一个实例上先选音频再选视频，两轨都处于选中态，音频样本会混进视频流
            //   （实测 143 视频样本被喂成 214，71 个 AAC 样本进了 HEVC 解码器 → 渲染只剩 105 帧）。
            // ★ 内存（2026-10-16）：改为**流式** remux —— 旧实现把整条音频轨预读进 Java 堆
            //   （List<AudioSample>，内存 ∝ 视频时长：7 分钟 ≈ 7MB、30 分钟 ≈ 28MB+），
            //   长视频转码正是 LMK 杀桥的高危窗口，白白抬高峰值不划算。
            //   现在保留 exAudio 到编码结束，由 EncoderDrain 按 PTS 交错**按需读取**
            //   （峰值 = 一个 max-input-size 缓冲，约 64KB），收尾再把尾部音频补完。
            boolean muxAudio = aIdx >= 0 && "aac-lc".equals(info.audioCodec);
            if (muxAudio) {
                exAudio = new MediaExtractor();
                exAudio.setDataSource(src.getAbsolutePath());
                exAudio.selectTrack(aIdx);
                log("音频轨流式 remux：" + info.audioSampleRate + "Hz/" + info.audioChannels
                        + "ch AAC-LC（不预读、不重编码）");
            }

            // ---------------- 2) 编码器（H.264 / Surface 输入） ----------------
            int fps = info.frameRate > 0 ? Math.min(60, info.frameRate) : 30;
            MediaFormat ef = MediaFormat.createVideoFormat("video/avc", outW, outH);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(outW, outH, fps));
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            // 关闭 B 帧：输出 PTS 单调（MediaMuxer 要求），也没有首帧延迟
            try {
                ef.setInteger("max-b-frames", 0);
            } catch (Throwable ignored) {
            }
            encoder = MediaCodec.createEncoderByType("video/avc");
            encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();

            // ---------------- 3) GL 管线（内部先建 EGL 上下文，再建外部纹理/SurfaceTexture） --------
            gl = new GlPipeline(encoderSurface, outW, outH, info.rotation, info.width, info.height);

            // ---------------- 4) 解码器：输出到 SurfaceTexture ----------------
            decoder = MediaCodec.createDecoderByType(vFmt.getString(MediaFormat.KEY_MIME));
            decoder.configure(vFmt, gl.decoderSurface(), null, 0);

            // ---------------- 5) 封装器 + 编码输出消费线程 ----------------
            muxer = new MediaMuxer(tmp.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            drain = new EncoderDrain(encoder, muxer, exAudio, job, muxAudio, aFmt, outW, outH);

            encoder.start();
            decoder.start();
            drain.start();

            // ---------------- 6) 主循环：喂解码器 → 取输出 → 渲染到编码器输入面 ----------------
            ex.selectTrack(vIdx);
            ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long firstPts = -1;
            long frames = 0;
            int frameMisses = 0;
            int fed = 0;
            int zeroSizeFrames = 0;
            int cfgFrames = 0;
            int inputWaits = 0;
            int outputWaits = 0;

            while (!outputDone) {
                checkCancel(job);
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("transcode timeout");
                }

                // 6.1 喂输入
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(inIdx);
                        int sz = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                            fed++;
                        }
                    } else {
                        inputWaits++;
                    }
                }

                // 6.2 取输出
                // ★ 渲染判定不能用 `bi.size != 0`：本机解码器在 surface 模式下相当一部分
                //   **正常画面帧**的 size 也是 0，按 size 判会丢掉约 1/4 的帧（实测 143 帧只渲染 105 帧）。
                //   正确判据是 flags：codec-config（无画面）与 EOS（无内容可渲染）之外的都渲染。
                int outIdx = decoder.dequeueOutputBuffer(bi, 10_000);
                if (outIdx >= 0) {
                    boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean cfg = (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    boolean render = !cfg && !eos;
                    if (bi.size == 0 && !eos && !cfg) zeroSizeFrames++;
                    if (cfg) cfgFrames++;
                    if (render) {
                        decoder.releaseOutputBuffer(outIdx, true);
                        if (firstPts < 0) firstPts = bi.presentationTimeUs;
                        long ptsUs = Math.max(0, bi.presentationTimeUs - firstPts);

                        if (!gl.awaitFrame(3000)) frameMisses++;
                        gl.draw(ptsUs * 1000L);
                        frames++;

                        job.processedMs = ptsUs / 1000;
                        if (job.durationMs > 0) {
                            job.progress = (float) Math.min(0.999,
                                    job.processedMs / (double) job.durationMs);
                        }
                    } else {
                        decoder.releaseOutputBuffer(outIdx, false);
                    }
                    if (eos) outputDone = true;
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    outputWaits++;
                }
            }

            long expectFrames = Math.max(1, job.durationMs * fps / 1000);
            log("视频渲染完成：" + frames + " 帧（预期约 " + expectFrames + "，喂入 " + fed
                    + " 样本，size=0 帧 " + zeroSizeFrames + "，config 帧 " + cfgFrames
                    + "，输入等待 " + inputWaits + " 次，输出等待 " + outputWaits + " 次）→ "
                    + outW + "x" + outH + "@" + fps
                    + (frameMisses > 0 ? "（其中 " + frameMisses + " 帧等待超时）" : ""));

            // ---------------- 7) 收尾：通知编码器 EOS，等数据写完 ----------------
            encoder.signalEndOfInputStream();
            drain.finishAndJoin();
            if (drain.error != null) throw new IllegalStateException(drain.error);

        } finally {
            safeReleaseDecoder(decoder);
            safeStopRelease(encoder);
            if (gl != null) gl.release();
            try {
                if (encoderSurface != null) encoderSurface.release();
            } catch (Throwable ignored) {
            }
            try {
                if (drain != null && drain.muxerStarted) muxer.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (muxer != null) muxer.release();
            } catch (Throwable ignored) {
            }
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
            try {
                if (exAudio != null) exAudio.release();
            } catch (Throwable ignored) {
            }
        }

        // ---------------- 8) 原子落盘 ----------------
        if (!tmp.exists() || tmp.length() == 0) throw new IllegalStateException("empty output");
        //noinspection ResultOfMethodCallIgnored
        outFile.delete();
        if (!tmp.renameTo(outFile)) {
            copyFile(tmp, outFile);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static void safeReleaseDecoder(MediaCodec c) {
        if (c == null) return;
        try {
            c.stop();
        } catch (Throwable ignored) {
        }
        try {
            c.release();
        } catch (Throwable ignored) {
        }
    }

    private static void safeStopRelease(MediaCodec c) {
        if (c == null) return;
        try {
            c.stop();
        } catch (Throwable ignored) {
        }
        try {
            c.release();
        } catch (Throwable ignored) {
        }
    }

    /** 码率：按像素面积与帧率估算，限制在 0.8–4 Mbps */
    private static int bitrateFor(int w, int h, int fps) {
        long px = (long) w * h;
        int bps = (int) (px * fps * 0.09);
        if (bps < 800_000) bps = 800_000;
        if (bps > 4_000_000) bps = 4_000_000;
        return bps;
    }

    private static void copyFile(File a, File b) throws Exception {
        InputStream in = new FileInputStream(a);
        OutputStream out = new FileOutputStream(b);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /* ============================ 编码输出消费线程 ============================ */

    /**
     * 独立线程消费编码器输出并写 Muxer。
     *
     * 为什么必须独立线程：主线程要阻塞在 `eglSwapBuffers`（等编码器空出输入缓冲），
     * 若同一线程再去 drain 编码器输出，编码器缓冲满时就会**死锁**。
     * 所有 Muxer 写入（视频 + 音频）都集中在本线程，保证 MediaMuxer 单写者。
     */
    private static final class EncoderDrain {
        private final MediaCodec encoder;
        private final MediaMuxer muxer;
        /** 音频轨独立 extractor（流式读取，进程内只保留一个样本缓冲，见 drainAudio） */
        private final MediaExtractor exAudio;
        private final Job job;
        private final boolean muxAudio;
        private final MediaFormat aFmt;
        private final int outW;
        private final int outH;

        /** 音频样本复用缓冲（容量 = 轨道 max-input-size，单样本上限） */
        private final ByteBuffer audioBuf;
        private final MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
        private boolean audioEos = false;

        private Thread thread;
        volatile boolean muxerStarted = false;
        volatile String error = null;
        private int videoTrack = -1;
        private int audioTrack = -1;

        EncoderDrain(MediaCodec encoder, MediaMuxer muxer, MediaExtractor exAudio, Job job,
                     boolean muxAudio, MediaFormat aFmt, int outW, int outH) {
            this.encoder = encoder;
            this.muxer = muxer;
            this.exAudio = exAudio;
            this.job = job;
            this.muxAudio = muxAudio;
            this.aFmt = aFmt;
            this.outW = outW;
            this.outH = outH;
            int maxIn = aFmt == null ? 64 * 1024
                    : intOrDefault(aFmt, MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
            if (maxIn <= 0) maxIn = 64 * 1024;
            this.audioBuf = ByteBuffer.allocate(maxIn);
        }

        void start() {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "bridge-encoder-drain");
            thread.setDaemon(true);
            thread.start();
        }

        void finishAndJoin() throws Exception {
            if (thread == null) return;
            thread.join(60_000);
            if (thread.isAlive()) throw new IllegalStateException("encoder drain timeout");
        }

        private void loop() {
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long lastVideoUs = 0;
            try {
                while (true) {
                    int idx;
                    try {
                        idx = encoder.dequeueOutputBuffer(bi, 10_000);
                    } catch (IllegalStateException ise) {
                        break; // 编码器已 stop/release
                    }
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = encoder.getOutputFormat();
                        try {
                            of.setInteger(MediaFormat.KEY_WIDTH, outW);
                            of.setInteger(MediaFormat.KEY_HEIGHT, outH);
                        } catch (Throwable ignored) {
                        }
                        videoTrack = muxer.addTrack(of);
                        if (muxAudio && aFmt != null) {
                            audioTrack = muxer.addTrack(aFmt);
                        }
                        muxer.start();
                        muxerStarted = true;
                        log("转码封装启动：视频轨 " + videoTrack + " / 音频轨 " + audioTrack
                                + "，编码档位 " + outW + "x" + outH);
                        continue;
                    }
                    if (idx < 0) continue;

                    boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean cfg = (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    int size = bi.size;

                    if (muxerStarted && !cfg && size > 0 && videoTrack >= 0) {
                        ByteBuffer buf = encoder.getOutputBuffer(idx);
                        if (buf != null) {
                            // 音频按 PTS 交错写入（流式读取），避免 MediaMuxer 内部无限缓冲
                            drainAudio(bi.presentationTimeUs);
                            buf.position(bi.offset);
                            buf.limit(bi.offset + size);
                            muxer.writeSampleData(videoTrack, buf, bi);
                            if (bi.presentationTimeUs > lastVideoUs) lastVideoUs = bi.presentationTimeUs;
                        }
                    }
                    encoder.releaseOutputBuffer(idx, false);
                    if (eos) break;
                }

                // 收尾：视频已结束，把尚未写出的尾部音频补上
                if (muxerStarted && muxAudio && audioTrack >= 0) {
                    drainAudio(Long.MAX_VALUE);
                }
            } catch (Throwable t) {
                error = String.valueOf(t.getMessage() != null ? t.getMessage() : t);
                Log.w(TAG, "encoder drain failed", t);
            }
        }

        /**
         * 流式写出音频样本：从 exAudio 按需读取，写出所有 PTS ≤ untilPtsUs 的样本后返回。
         * 未到界时**不消费**当前样本（readSampleData 可重复读同一样本），下轮接着写。
         * 内存占用恒定 = 一个样本缓冲（max-input-size，典型 64KB），与视频时长无关。
         */
        private void drainAudio(long untilPtsUs) throws Exception {
            if (!muxAudio || audioTrack < 0 || exAudio == null) return;
            while (!audioEos) {
                audioBuf.clear();
                int n = exAudio.readSampleData(audioBuf, 0);
                if (n < 0) {
                    audioEos = true;
                    break;
                }
                long pts = exAudio.getSampleTime();
                if (pts > untilPtsUs) break; // 留给下个视频帧的间隙再写（不 advance）
                audioInfo.set(0, n, Math.max(0, pts), 0);
                audioBuf.position(0);
                audioBuf.limit(n);
                muxer.writeSampleData(audioTrack, audioBuf, audioInfo);
                if (!exAudio.advance()) audioEos = true;
            }
        }
    }

    /* ============================ GL 管线 ============================ */

    /**
     * EGL + GLES2 缩放/旋转管线：把 SurfaceTexture 上的解码帧绘制到编码器的输入 Surface。
     *
     * 坐标系：NDC 是 y 向上，四角固定 (-1,-1)…(1,1) 覆盖整个输出画面；
     * 旋转通过在顶点着色器里对位置乘旋转矩阵实现（纹理坐标跟着顶点走，于是**画面内容**被旋转），
     * 因此输出分辨率要按"旋转归正后"的宽高设置，旋转后正好铺满、不变形。
     */
    private static final class GlPipeline {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;

        private static final String VERTEX_SHADER =
                "uniform mat3 uRot;\n"
                        + "uniform mat4 uTexMatrix;\n"
                        + "attribute vec4 aPosition;\n"
                        + "attribute vec4 aTexCoord;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "void main() {\n"
                        + "  vec3 p = uRot * vec3(aPosition.xy, 1.0);\n"
                        + "  gl_Position = vec4(p.xy, 0.0, 1.0);\n"
                        + "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n"
                        + "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "uniform samplerExternalOES sTexture;\n"
                        + "void main() {\n"
                        + "  gl_FragColor = texture2D(sTexture, vTexCoord);\n"
                        + "}\n";

        private final EGLDisplay display;
        private EGLSurface eglSurface;
        private EGLContext context;
        private SurfaceTexture texture;
        private Surface decSurface;
        private int textureId = 0;
        private int program = 0;
        private int aPosition = -1;
        private int aTexCoord = -1;
        private int uTexMatrix = -1;
        private int uRot = -1;
        private int uTexture = -1;
        private FloatBuffer quad;
        private final float[] texMatrix = new float[16];
        private final float[] rotMatrix = new float[9];
        private final Object frameLock = new Object();
        private volatile boolean frameReady = false;
        private final int rotationDeg;
        private final int thetaDeg;
        private boolean rotationDecided = false;
        private boolean decoderAlreadyRotated = false;

        GlPipeline(Surface encoderSurface, int outW, int outH, int rotationDeg,
                   int srcW, int srcH) throws Exception {
            this.rotationDeg = rotationDeg;
            // 内容在 NDC（y 向上）中逆时针旋转 θ。θ 与 Android rotation 的换算见常量注释。
            // ★ 是否真的要转，首帧看解码矩阵再定（见 draw()），这里先按"需要转"准备。
            this.thetaDeg = ROTATION_ANDROID_IS_CLOCKWISE
                    ? (360 - rotationDeg) % 360
                    : rotationDeg % 360;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("no EGL display");
            int[] maj = new int[1];
            int[] min = new int[1];
            if (!EGL14.eglInitialize(display, maj, 0, min, 0)) {
                throw new IllegalStateException("eglInitialize failed");
            }

            int[] attribs = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                throw new IllegalStateException("no suitable EGL config");
            }
            int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                throw new IllegalStateException("eglCreateContext failed");
            }
            int[] surfAttribs = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface,
                    surfAttribs, 0);
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("eglCreateWindowSurface failed (0x"
                        + Integer.toHexString(EGL14.eglGetError()) + ")");
            }
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                throw new IllegalStateException("eglMakeCurrent failed (0x"
                        + Integer.toHexString(EGL14.eglGetError()) + ")");
            }

            // ---- 外部纹理 + SurfaceTexture（必须在 EGL 上下文 current 之后创建）----
            int[] holder = new int[1];
            GLES20.glGenTextures(1, holder, 0);
            textureId = holder[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            texture = new SurfaceTexture(textureId);
            texture.setDefaultBufferSize(srcW, srcH);
            decSurface = new Surface(texture);

            // 帧回调**不指定 Handler**：转码线程没有 Looper，指定主线程 Handler 会让每帧
            // 多绕一次主线程 Looper（实测 ~26ms/帧，转码掉到 1.0× 实时）。
            // 不带 Handler 时回调直接在投递线程执行，唤醒延迟降到亚毫秒级。
            texture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture st) {
                    synchronized (frameLock) {
                        frameReady = true;
                        frameLock.notifyAll();
                    }
                }
            });

            float[] q = {
                    // x, y, u, v（TRIANGLE_STRIP 顺序：左下、右下、左上、右上）
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
            };
            quad = ByteBuffer.allocateDirect(q.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(q).position(0);

            int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] != GLES20.GL_TRUE) {
                throw new IllegalStateException("program link failed: "
                        + GLES20.glGetProgramInfoLog(program));
            }
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
            uRot = GLES20.glGetUniformLocation(program, "uRot");
            uTexture = GLES20.glGetUniformLocation(program, "sTexture");

            log("GL 就绪：输出 " + outW + "x" + outH + "，源 " + srcW + "x" + srcH
                    + "，rotation=" + rotationDeg + "°（θ=" + thetaDeg + "°，是否应用见首帧矩阵判定）");

            GLES20.glViewport(0, 0, outW, outH);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        }

        /** 生成 3x3 旋转矩阵（列主序，NDC 逆时针 θ 度） */
        private void setRotation(int thetaDeg) {
            double r = Math.toRadians(thetaDeg % 360);
            float c = (float) Math.cos(r);
            float s = (float) Math.sin(r);
            // 行主序 [[c,-s,0],[s,c,0],[0,0,1]] → 列主序数组
            rotMatrix[0] = c;
            rotMatrix[1] = s;
            rotMatrix[2] = 0f;
            rotMatrix[3] = -s;
            rotMatrix[4] = c;
            rotMatrix[5] = 0f;
            rotMatrix[6] = 0f;
            rotMatrix[7] = 0f;
            rotMatrix[8] = 1f;
        }

        private static int compile(int type, String src) {
            int id = GLES20.glCreateShader(type);
            GLES20.glShaderSource(id, src);
            GLES20.glCompileShader(id);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] != GLES20.GL_TRUE) {
                String log = GLES20.glGetShaderInfoLog(id);
                GLES20.glDeleteShader(id);
                throw new IllegalStateException("shader compile failed: " + log);
            }
            return id;
        }

        Surface decoderSurface() {
            return decSurface;
        }

        /** 等解码器把新帧投递到 SurfaceTexture（超时返回 false，避免卡死） */
        boolean awaitFrame(long timeoutMs) {
            synchronized (frameLock) {
                long waitUntil = System.currentTimeMillis() + timeoutMs;
                while (!frameReady) {
                    long left = waitUntil - System.currentTimeMillis();
                    if (left <= 0) return false;
                    try {
                        frameLock.wait(left);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                frameReady = false;
                return true;
            }
        }

        /**
         * 取样 → 绘制 → 提交，并把该帧的显示时间戳交给编码器。
         * （surface 输入模式下编码器输出 PTS 完全来自这里，不设置就会用系统时间。）
         */
        void draw(long ptsNs) {
            texture.updateTexImage();
            texture.getTransformMatrix(texMatrix);
            if (!rotationDecided) {
                rotationDecided = true;
                // ★ 首帧判定：有些解码器会把容器的 rotation **编进 SurfaceTexture 变换矩阵**
                //   （本机 c2.qti.hevc.decoder 就是——实测它输出前已把画面转正）。
                //   判据：列主序 4x4 无旋转时对角 ±1、非对角≈0；含 90/270 旋转时 b/c 元素非零。
                //   矩阵已含旋转 → GL 只缩放；不含 → GL 才按 θ 旋转。这样换设备也不用改代码。
                decoderAlreadyRotated =
                        Math.abs(texMatrix[1]) > 0.5f || Math.abs(texMatrix[4]) > 0.5f;
                log("解码矩阵" + (decoderAlreadyRotated
                        ? "已含旋转 → GL 不再旋转（只缩放）"
                        : "不含旋转 → GL 按 rotation=" + rotationDeg + "°（θ=" + thetaDeg + "°）旋转"));
                setRotation(decoderAlreadyRotated ? 0 : thetaDeg);
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(uTexture, 0);
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
            GLES20.glUniformMatrix3fv(uRot, 1, false, rotMatrix, 0);

            quad.position(0);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPosition);
            quad.position(2);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aTexCoord);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTexCoord);
            GLES20.glFinish();

            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs);
            // 注：EGLExt 只提供 eglPresentationTimeANDROID；换缓冲仍用 EGL14.eglSwapBuffers
            //    （presentation time 是挂在 surface 上的，下一次 swap 生效）
            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                throw new IllegalStateException("eglSwapBuffers failed (0x"
                        + Integer.toHexString(EGL14.eglGetError()) + ")");
            }
        }

        void release() {
            try {
                if (decSurface != null) decSurface.release();
            } catch (Throwable ignored) {
            }
            try {
                if (texture != null) texture.release();
            } catch (Throwable ignored) {
            }
            try {
                if (textureId != 0) {
                    int[] h = {textureId};
                    GLES20.glDeleteTextures(1, h, 0);
                }
            } catch (Throwable ignored) {
            }
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
            } catch (Throwable ignored) {
            }
            try {
                if (eglSurface != null) EGL14.eglDestroySurface(display, eglSurface);
            } catch (Throwable ignored) {
            }
            try {
                if (context != null) EGL14.eglDestroyContext(display, context);
            } catch (Throwable ignored) {
            }
            try {
                if (display != null) EGL14.eglTerminate(display);
            } catch (Throwable ignored) {
            }
        }
    }
}
