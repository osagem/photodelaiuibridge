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

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 「照片删除智能体服务」控制台。
 *
 * 界面结构（自上而下，垂直居中 / 按钮贴底）：
 *   ① 图标（屏幕中间居中）
 *   ② 说明文字（本程序为「照片删除」智能体配套服务…）
 *   ③ 提示行（未授权时 = 常驻「操作指引」；另承载启动中 / 启动失败等状态）
 *   ④ 底部按钮：可点击的「启用」→ 不可点击的「已启用」
 *
 * 交互流程：
 *   · 启动后**停留在本说明界面**：绝不自动跳授权页、绝不自动退出
 *   · 默认焦点落在底部「启用」按钮上，眼镜端单击确认（或点击屏幕任意位置）
 *     才进入授权流程；**每次 onResume（含从授权页返回）都重新把焦点要回按钮**
 *   · 未授权时，界面底部常驻**完整操作指引**（见 {@link #hint}）：
 *     「启用【所有文件访问】权限操作指引 / RG镜腿上单击进入系统权限页，镜腿上向前滑动
 *     移动焦点到开关上，单击开启，再双击返回即可」—— 用户点「启用」前即可从容读完
 *   · 点「启用」→ **立即**跳系统授权页，不再弹任何 Toast（原因见下方长注释）
 *   · 授权返回后自动启动服务；服务经 /ping 确认运行后，界面复用不切换，
 *     按钮变「已启用」并置灰，按钮上方以 Toast 逐秒倒计时 5→1，随后自动关闭
 *
 * 硬规则：**权限不齐绝不自动退出** —— 否则用户丧失唯一的授权入口。
 *
 * 排障口径：界面不显示机型/权限/目录/接口诊断；一律通过 PC 侧
 *   GET http://127.0.0.1:38999/log 读取桥日志。
 */
public class MainActivity extends Activity {

    /** ping 探测参数：以真实监听为准，而不是只看 BridgeService 的静态状态。
     *  冷启动实测：前台服务从 startForegroundService 到端口可连需 1~3 秒，
     *  窗口必须覆盖这一段（8 次 × 500ms ≈ 4s，首次立即） */
    private static final int PROBE_ATTEMPTS = 8;
    private static final int PROBE_INTERVAL_MS = 500;
    private static final int PROBE_TIMEOUT_MS = 800;
    /** 服务确认运行后，自动关闭程序前的倒计时秒数（逐秒 Toast：5→1，第 5 秒关闭） */
    private static final int EXIT_COUNTDOWN_SEC = 5;
    /** 倒计时 Toast 抬升量：按钮上边界距底 64dp，+8dp 间隙 → 提示紧贴按钮上方 */
    private static final int TOAST_OFFSET_DP = 72;
    /*
     * ★ 跳转授权页的「指引 Toast」已按用户要求整体移除（2026-10-15 23:58）。
     *
     * 移除原因（真机 5 轮实测的硬结论，留作后人参考，勿重复踩坑）：
     *  · Toast 时长由系统写死（SHORT 2s / LONG 3.5s），**无法延长**；
     *  · Toast 在 enqueue 与"开始显示"两个时刻都要求应用在前台，应用退到系统页后被判后台
     *    → 循环续显被拦（"Blocking custom toast ... not in the foreground"），排队上限 5 条；
     *  · 悬浮窗（TYPE_APPLICATION_OVERLAY）本可自由控制位置与时长，但系统设置页带
     *    `mForceHideNonSystemOverlayWindow=true`（Android 12 反屏幕覆盖攻击），
     *    会强制隐藏一切非系统浮层 → 同样做不到。
     *  结论：3.5 秒是平台天花板，观感"不完美"无法通过工程手段消除 →
     *  **改为把完整操作指引常驻在说明界面底部（hint 行）**，跳转前用户可从容读完，
     *  跳转后不再有任何浮层干扰系统授权页。
     *
     * 因此「启用」按钮的跳转改为**立即跳转**（不再有跳转前的延迟等待）。
     */

    private TextView hint;
    private Button startButton;
    private Toast countdownToast;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 已跳转授权页，防止同一次点击/连点重复拉起系统页（onResume 时复位） */
    private boolean jumping = false;
    /** 已进入倒计时/退出流程，避免 onResume 重复触发探测（运行期状态，不持久化） */
    private boolean exiting = false;
    /** 探测进行中，避免并发 */
    private boolean probing = false;
    /** 倒计时剩余秒数 */
    private int countdown = 0;

    /** 逐秒递减；到 1 之后再等 1 秒才真正关闭（总时长 = EXIT_COUNTDOWN_SEC 秒） */
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (countdown <= 1) {
                ui.postDelayed(exitRunnable, 1000);
                return;
            }
            countdown--;
            showCountdownToast(getString(R.string.toast_countdown, countdown));
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable exitRunnable = new Runnable() {
        @Override
        public void run() {
            if (countdownToast != null) countdownToast.cancel();
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);   // 纯黑：与眼镜系统界面融为一体，视觉上"无背景"
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // ---- 顶部弹性空白：与底部空白配合，让「图标 + 说明」落在可视区中部 ----
        root.addView(new View(this), lp(-1, 0, 1f));

        // ---- ① 图标 ----
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.applogo);
        root.addView(icon, lp(dp(88), dp(88), 0f));

        // ---- ② 说明文字 ----
        TextView desc = new TextView(this);
        desc.setText(R.string.desc_intro);
        desc.setTextColor(Color.parseColor("#C9D3DC"));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(dp(3), 1f);
        desc.setPadding(dp(6), dp(16), dp(6), 0);
        root.addView(desc, lp(-1, -2, 0f));

        // ---- 底部弹性空白 ----
        root.addView(new View(this), lp(-1, 0, 1f));

        // ---- ③ 提示行（默认隐藏：操作指引 / 启动中 / 启动失败）----
        //   未授权时承载**常驻操作指引**（标题 + 正文两行，见 hint_permission），
        //   跳转授权页后不再有任何浮层提示 —— 指引在跳转前就已完整可见。
        hint = new TextView(this);
        hint.setTextColor(Color.parseColor("#E8C36A"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(4), 1f);   // 多行指引的行距，避免两行贴在一起
        hint.setPadding(dp(2), dp(8), dp(2), dp(2));
        hint.setVisibility(View.GONE);
        root.addView(hint, lp(-1, -2, 0f));

        // ---- ④ 底部按钮 ----
        //   ★ 样式对齐 photoDel4rokidglasses 首屏底部按钮（Material 定制 OutlinedButton）：
        //     透明底 + 绿色圆角描边（12px）+ 描边/文字同色（40%→80%→100% 绿的状态选择器）
        //     + **获得焦点时描边 2px→4px**（单绿显示下唯一能看清的焦点表达）。
        //   桥工程无 Material 依赖，因此用框架自带的 **StateListDrawable + ColorStateList**
        //   等价复刻（资源见 res/drawable/btn_outlined.xml、res/color/button_stroke_color_selector.xml）。
        //   尺寸：固定 **176dp 宽** × 48dp 高（176dp = 相册 Material outlined 默认 minWidth 88dp 的
        //   **两倍宽**，用户 2026-10-16 明确要求"两倍宽"；240dpi 下即 264px × 72px）。
        startButton = new Button(this);
        startButton.setText(R.string.btn_enable);
        startButton.setAllCaps(false);
        startButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);   // Material 按钮字号的等价值
        startButton.setTextColor(getResources().getColorStateList(R.color.button_stroke_color_selector));
        // 必须先清掉主题给的按钮底色 tint，否则会把描边背景整体染成灰底（框架 Button 的坑）
        startButton.setBackgroundTintList(null);
        startButton.setBackgroundResource(R.drawable.btn_outlined);
        startButton.setStateListAnimator(null);                    // 去掉按下抬升阴影（Material Outlined 亦为 0 高度）
        startButton.setPadding(dp(20), 0, dp(20), 0);              // 对齐相册 CustomDialogButton 的左右内边距
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onEnableAction();
            }
        });
        LinearLayout.LayoutParams btnLp = lp(dp(176), dp(48), 0f);  // 两倍宽（88dp → 176dp）
        btnLp.gravity = Gravity.CENTER_HORIZONTAL;                 // 居中（相册为 wrap_content 紧凑按钮）
        root.addView(startButton, btnLp);

        // 整屏兜底：眼镜的单击若以触摸事件落在空白/图标/文字区，同样进入授权流程。
        // ★ 刻意**不**设 root.setClickable(true) —— 那会把整屏变成可点击/可悬停目标，
        //   在部分 ROM（含本机）上焦点/悬停高亮是整块亮色，返回界面时会顶替掉纯黑背景。
        //   用 OnTouchListener 可达同样效果，但不改变视图的 clickable/hoverable 属性。
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) onEnableAction();
                return false;   // 不消费事件，保持系统默认行为
            }
        });

        setContentView(root);

        // ---- 默认光标停在底部「启用」按钮上：眼镜端单击确认即进入授权流程 ----
        startButton.setFocusable(true);
        startButton.setFocusableInTouchMode(true);
        startButton.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        jumping = false;       // 回到本界面即可再次点击「启用」
        if (exiting) return;   // 正在倒计时/自动退出，不再触发新一轮

        // ★ 焦点确定性（授权页返回路径）：onCreate 里只请求过一次焦点，从系统授权页返回时
        //   走的是 onRestart→onStart→onResume，不会重跑 onCreate —— 此时谁持有焦点由系统决定，
        //   若落到整屏容器上会被 ROM 的焦点高亮顶掉纯黑背景，且用户的"单击确认"也可能失效。
        //   这里每次都把焦点显式要回底部「启用」按钮，让冷启动与返回后的视觉/交互完全一致。
        startButton.requestFocus();
        getWindow().getDecorView().invalidate();   // 顺带强制重绘一帧：避免恢复窗口时合成器沿用旧帧

        if (hasFileAccess()) {
            hideHint();
            startAndProbe();
        } else {
            // 权限不齐：只显示指引，**停在说明界面等用户按「启用」**。
            // 这里绝不能自动跳授权页 —— 否则说明界面刚画出来就被系统页盖住，用户根本看不见。
            showHint(getString(R.string.hint_permission));
        }
    }

    /** 窗口焦点变化时留痕，便于用 GET /log 远程核对返回后的焦点归属 */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            BridgeServer.log("桥界面焦点：窗口失去");
            return;
        }
        // 视图焦点在窗口获得焦点后「随后」才分配，立即读会拿到 null → 延后记录稳定态
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                View f = getCurrentFocus();
                BridgeServer.log("桥界面焦点[窗口获得后]：" + (f == null ? "无视图持有焦点"
                        : f.getClass().getSimpleName()
                          + (f == startButton ? "（底部启用按钮）" : "（非按钮！）")));
            }
        }, 400);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (countdownToast != null) {
            countdownToast.cancel();
            countdownToast = null;
        }
        super.onDestroy();
    }

    /* ==================== 交互动作 ==================== */

    /**
     * 「启用」动作（底部按钮单击、屏幕任意处点击共用）：
     *   权限已具备 → 启动服务并用 /ping 确认运行；
     *   权限缺失   → **立即**跳系统「所有文件访问」授权页。
     * 跳转前不弹任何 Toast（原因见类顶部注释）：界面底部的操作指引常驻可读，
     * 用户点「启用」前已能看完整指引。
     */
    private void onEnableAction() {
        if (exiting || probing) return;
        if (hasFileAccess()) {
            startAndProbe();
        } else {
            if (jumping) return;   // 防连点重复拉起系统页
            jumping = true;
            openAllFilesAccess();
        }
    }

    /* ==================== 权限 ==================== */

    /**
     * 「所有文件访问」（MANAGE_EXTERNAL_STORAGE）是否已授予。
     *
     * 说明：这是 Android 的**特殊权限**，任何第三方 App 都无法静默获取，
     * 必须由用户在系统界面手动拨一次开关。本机（Rokid Glasses / API 32）只有
     * 「所有文件访问」全局列表页（Settings$ManageExternalStorageActivity），
     * 没有"直达本应用"的授权页，因此只能跳到列表页由用户自行找到本应用。
     */
    private boolean hasFileAccess() {
        try {
            return Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 跳转授权页：优先「直达本应用」（多数机型存在），失败则回退到全局列表页
     * （本机可用的就是后者）。
     */
    private void openAllFilesAccess() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        } catch (Throwable ignored) {
            // 本机不存在该页面，继续回退
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        } catch (Throwable t) {
            BridgeServer.log("无法打开「所有文件访问」设置页: " + t);
            showHint(getString(R.string.hint_no_settings, getPackageName()));
        }
    }

    /* ==================== 启动 + 确认 + 倒计时关闭 ==================== */

    /**
     * 启动桥服务，并以 /ping 实测确认端口真的在监听；
     * 确认成功后按钮切「已启用」并置灰，逐秒 Toast 倒计时后自动关闭界面。
     * 失败则按钮恢复可点 + 提示重试（界面不退出，保住唯一入口）。
     */
    private void startAndProbe() {
        if (probing || exiting) return;
        probing = true;
        startButton.setEnabled(false);                                     // 防重复触发
        showHint(getString(R.string.hint_starting));                       // 覆盖服务冷启动窗口
        BridgeService.ensureStarted(this);

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                for (int i = 0; i < PROBE_ATTEMPTS && !ok; i++) {
                    ok = ping();
                    if (!ok) {
                        try {
                            Thread.sleep(PROBE_INTERVAL_MS);
                        } catch (InterruptedException ignored) {
                            // 继续重试
                        }
                    }
                }

                final boolean running = ok;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        probing = false;
                        if (running) {
                            beginCountdownAndExit();
                        } else {
                            startButton.setEnabled(true);
                            showHint(getString(R.string.hint_start_failed));
                            BridgeServer.log("桥界面：服务启动后 ping 探测失败"
                                    + (lastPingError != null ? "，原因：" + lastPingError : ""));
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 服务已确认运行：界面**原地复用**，按钮变不可点击的「已启用」，
     * 按钮上方 Toast 逐秒倒计时 EXIT_COUNTDOWN_SEC → 1，随后关闭程序。
     */
    private void beginCountdownAndExit() {
        exiting = true;

        startButton.setEnabled(false);
        startButton.setText(R.string.btn_enabled);
        startButton.setFocusable(false);
        hideHint();

        BridgeServer.log("桥界面：服务已确认运行（127.0.0.1:" + BridgeServer.PORT + "），"
                + EXIT_COUNTDOWN_SEC + " 秒倒计时后自动关闭程序");

        countdown = EXIT_COUNTDOWN_SEC;
        showCountdownToast(getString(R.string.toast_countdown, countdown));
        ui.postDelayed(tickRunnable, 1000);
    }

    /** 复用同一个 Toast 实例逐秒刷新（Android 官方推荐的"原地更新"用法，不会排队堆积） */
    private void showCountdownToast(String text) {
        if (countdownToast == null) {
            countdownToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
            countdownToast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(TOAST_OFFSET_DP));
        } else {
            countdownToast.setText(text);
        }
        countdownToast.show();
    }

    /** 以 HTTP /ping 实测本机回环，确认真实可服务 */
    private boolean ping() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + BridgeServer.PORT + "/ping").openConnection();
            c.setConnectTimeout(PROBE_TIMEOUT_MS);
            c.setReadTimeout(PROBE_TIMEOUT_MS);
            c.setRequestProperty("X-Bridge-Token", BridgeService.token());
            if (c.getResponseCode() != 200) {
                lastPingError = "HTTP " + c.getResponseCode();
                return false;
            }
            InputStream is = c.getInputStream();
            byte[] buf = new byte[64];
            int n = is.read(buf);
            is.close();
            return n > 0;
        } catch (Throwable t) {
            // 记录最后一次失败原因，供 /log 诊断（如 cleartext 被策略拦截等）
            lastPingError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 最近一次 ping 失败原因（走桥日志可观测，不占用界面） */
    private volatile String lastPingError = null;

    /* ==================== UI 辅助 ==================== */

    private void showHint(String text) {
        if (hint == null) return;
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
    }

    private void hideHint() {
        if (hint != null) hint.setVisibility(View.GONE);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        p.topMargin = dp(6);
        return p;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
