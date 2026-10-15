# Photo Delete AIUI Bridge（照片删除智能体服务）

[![Release](https://img.shields.io/github/v/release/osagem/photodelaiuibridge)](https://github.com/osagem/photodelaiuibridge/releases)
[![Platform](https://img.shields.io/badge/platform-Rokid%20Glasses-blue)](https://www.rokid.com/)

在 **Rokid Glasses** 上，让 AIUI 智能体「照片删除」通过语音浏览、播放与删除系统相册（DCIM）中的照片和视频。

本项目是配套的**伴生服务 App**（Android，包名 `com.osagem.photodelaiuibridge`，显示名「照片删除智能体服务 / Photo Delete AIUI Service」）。

---

## 为什么需要这个 App

Rokid Glasses 的 **AIUI 智能体运行时不提供任何系统相册读写 API**：

- 智能体只能管理自己的媒体库（包内资源 / 网络 URL / OPFS），无法访问 `/sdcard/DCIM`；
- `<image>` / `<video>` 的 `src` 只接受包内相对路径、`/assets/...` 和 `http(s)://`，**不支持** `file://`、`content://`、`data:`、`blob:`；
- 智能体也**没有任何跨应用/跨进程启动能力**，无法自行拉起一个原生 App。

因此，智能体要拿到相册里的字节，**唯一可行的通道就是 HTTP**。
本 App 就是提供这条通道的一侧：它常驻后台，持有所需的存储权限，在本机回环上暴露一个只读+删除的轻量 HTTP 接口。

```
┌──────────────────────────── Rokid Glasses ────────────────────────────┐
│                                                                       │
│   AIUI 智能体「照片删除」               本 App（前台服务，开机自启）    │
│   ┌──────────────────┐   http://127.0.0.1:38999   ┌───────────────┐   │
│   │  wx.request      │ ─────────────────────────► │  BridgeServer │   │
│   │  <image src=...> │                            │  (回环 HTTP)  │   │
│   │  <video src=...> │                            └───────┬───────┘   │
│   └──────────────────┘                                    │           │
│                                                    MediaIndex / 文件 IO │
│                                                           │           │
│                                          /sdcard/DCIM/Camera            │
│                                          /sdcard/Movies/Camera         │
└───────────────────────────────────────────────────────────────────────┘
```

---

## 下载与安装

1. 到 [**Releases**](https://github.com/osagem/photodelaiuibridge/releases) 下载最新的
   `photodelaiuibridge-release.apk`（正式签名包）。
2. 用 adb 安装到眼镜：

   ```bash
   adb install -r photodelaiuibridge-release.apk
   ```

   > ⚠️ 若设备上先前装过**调试签名**的同名 App，覆盖安装会失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
   > 见下方「常见问题」。

3. **首次使用需手动授权一次**（这是 Android 对「所有文件访问」的强制要求）：

   - 在眼镜上打开 **「照片删除智能体服务」**；
   - 底部按钮默认已获焦点，**单击**「启用」→ 跳到系统「所有文件访问」页；
   - 镜腿上**向前滑动**移动焦点到开关 → **单击**打开 → **双击返回**；
   - 回到 App 后服务自动启动，按钮变为不可点击的「已启用」，随后 5 秒倒计时自动关闭界面。

4. 现在可以对眼镜说：**「乐奇，打开 照片删除」**（或「打开 照片删除 智能体」）。

---

## 功能

| 能力 | 说明 |
|:--|:--|
| 相册索引 | 扫描 `DCIM/Camera`（照片）与 `Movies/Camera`（视频） |
| 浏览 | 智能体侧网格/列表展示（缩略图走 `/thumb`，视频封面走同一接口） |
| 播放 | `/media/<id>` 支持 HTTP Range，供 `<video>` 流式播放 |
| 删除 | 二次确认后 `DELETE`，直接 `File.delete()` |
| 常驻 | 前台服务（`dataSync`）+ 开机自启，空闲几乎不耗电（实测 60 秒 CPU 增量 0 ticks） |

### 接口一览

| 方法 | 路径 | 说明 |
|:--|:--|:--|
| `GET` | `/ping` | 存活探测（App 启动自检也用这个） |
| `GET` | `/info` | 版本、权限状态、扫描范围与媒体数量 |
| `GET` | `/list` | 媒体清单（JSON） |
| `GET` | `/thumb/<id>` | 缩略图 |
| `GET` | `/media/<id>` | 原图 / 视频流（支持 Range） |
| `POST` | `/delete/<id>` | 删除指定媒体 |
| `GET` | `/log` | 排障日志回读（环形缓冲） |
| `GET` | `/diag` | 环境诊断 |

---

## 从源码构建

无需 Gradle，一条命令完成 `aapt2` → `javac` → `d8` → 注入 dex → `zipalign` → `apksigner` 全流程。

```bash
./build.sh            # 调试签名 → photodelaiuibridge.apk
./build.sh release    # 发布签名 → photodelaiuibridge-release.apk（需自备 release.keystore）
```

**依赖**：Android SDK（build-tools 35.x + platform android-33）、JDK（可用 Android Studio 自带的 JBR）、Python 3。
所有路径都可用环境变量覆盖：`ANDROID_SDK_ROOT` / `JAVA_HOME` / `PYTHON` / `BT_DIR`。
脚本默认面向 **Windows（Git Bash）**；Linux / macOS 下需去掉脚本里的 `.exe` 后缀。

> 🔐 本仓库**不包含任何密钥**。发布签名需要你自己生成 keystore：
> 直接用 `./build.sh release`（首次会自动生成 `release.keystore` + `keystore.properties`），
> 或用 `./build.sh release <属性文件>` 指定外部密钥。详见 [RELEASE.md](RELEASE.md)。
> `.gitignore` 已排除 `*.keystore`、`keystore.properties`、`build/` 与 `*.apk`，避免误提交。

---

## 权限说明

| 权限 | 用途 |
|:--|:--|
| `MANAGE_EXTERNAL_STORAGE` | 枚举并删除相册文件（`DCIM/Camera`、`Movies/Camera`）—— 需用户在系统页手动开启 |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | Android 13+ 的媒体读取声明 |
| `FOREGROUND_SERVICE(_DATA_SYNC)` | 常驻前台服务，持有本地 HTTP 端口 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启（眼镜重启后无需手动打开） |
| `POST_NOTIFICATIONS` | 前台服务的常驻通知（Android 13+） |
| `INTERNET` | 创建本地 socket 所需（Android 上即使只监听回环也需要） |

---

## 隐私

- 所有媒体**只在设备本机流转**（回环 HTTP），App **不会**把照片、视频或任何内容上传到网络；
- 无统计、无广告、无第三方 SDK；
- 唯一的网络行为是监听本机端口，以及 App 启动时对 `127.0.0.1` 的一次 `/ping` 自检。

---

## 耗电情况

本软件在首次授权后随 RG 眼镜开机自动启动，但**只在「照片删除」智能体激活使用时产生服务活动**，没有任何额外的耗电产生。内存消耗大约 30 MB，一般无需担心耗电与资源占用问题。

---

## 兼容性

| 项目 | 值 |
|:--|:--|
| 设备 | Rokid Glasses（RG-glasses） |
| 系统 | Android 12 / API 32（`minSdk = targetSdk = 32`） |
| ABI | arm64-v8a |
| 显示 | 480×640 @240dpi（智能体侧全屏画布 480×352） |

---

## 常见问题

**Q：`adb install` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`？**
A：设备上已有**不同签名**的同名 App。先 `adb uninstall com.osagem.photodelaiuibridge` 再安装，
注意卸载会清掉「所有文件访问」授权，需要重新授权一次。

**Q：界面停在「未授权」，相册里没有内容？**
A：说明「所有文件访问」没开。按界面底部的操作指引开启即可（或 `adb shell appops set com.osagem.photodelaiuibridge MANAGE_EXTERNAL_STORAGE allow`）。

**Q：更新后 App 又要求重新授权？**
A：个别安装方式（如 `--no-incremental` 流式安装）会清掉授权。装完复查
`adb shell appops get com.osagem.photodelaiuibridge` 即可确认。

**Q：语音「乐奇，打开 照片删除」没反应？**
A：确认 App 已装且已授权（`/ping` 通），另外设备上不应存在**多个同名智能体**（会造成语音消歧失败）。

---

## 相关项目

- 配套 AIUI 智能体「照片删除」（DCIM 桥接版）—— 智能体工程的源码不在本仓库。
- 智能体运行时的能力边界（为何必须走 HTTP 桥）已在上文「为什么需要这个 App」中说明。

---

## 许可证 License

本项目以 **GNU Affero General Public License v3.0（AGPL-3.0）** 发布。

- 许可证全文：[LICENSE](LICENSE)
- 官方页面：<https://www.gnu.org/licenses/agpl-3.0.html>
- 中文参考译文：<https://www.gnu.org/licenses/agpl-3.0.zh-cn.html>

> AGPL-3.0 是强著佐权（copyleft）许可：分发本软件、分发其修改版，或以网络服务形式向用户提供本软件时，
> 都必须以 AGPL-3.0 开放相应源代码。若需在闭源项目中使用，请先联系作者获取另行授权。

---

## English

**Photo Delete AIUI Bridge** is a companion Android app for Rokid Glasses that lets a Rokid
**AIUI** agent browse, play back and delete photos/videos from the system gallery (DCIM) by voice.

The AIUI runtime has **no gallery API** and no way to launch another app, so a locally hosted
HTTP bridge is the only practical channel: this app holds the storage permission and exposes a
loopback-only HTTP API (`/list`, `/media/<id>` with Range support, `/delete/<id>`, …) that the
agent consumes via `wx.request` and `<image>` / `<video>`.

Download the signed APK from [Releases](https://github.com/osagem/photodelaiuibridge/releases),
install with `adb install -r`, then tap **Enable** on the glasses and turn on
**All files access** once. After that, just say: *“Hey Rokid, open Photo Delete.”*

Build from source with `./build.sh` (no Gradle required). No keys are shipped in this repository —
see [RELEASE.md](RELEASE.md) for signing.

**Power:** the service auto-starts with the glasses after the one-time authorization, but only
becomes active while the “Photo Delete” agent is in use — it adds no noticeable battery drain and
its memory footprint stays around 30 MB.

**License:** AGPL-3.0 — see [LICENSE](LICENSE).
