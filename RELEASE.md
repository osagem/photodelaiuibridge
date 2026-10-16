# 桥 App 发布构建（release）说明

> 工程：本仓库根目录｜包名 `com.osagem.photodelaiuibridge`｜显示名「照片删除智能体服务 / Photo Delete AIUI Service」

---

## 1. 产物

| 文件 | 用途 | 签名（证书 SHA-256 前 8 位） |
|:--|:--|:--|
| `photodelaiuibridge.apk` | 调试/自用 | `debug.keystore`（脚本自动生成） |
| `photodelaiuibridge-release.apk` | 正式发布（GitHub Release 附件即此包） | `release.keystore`（**不入库**，见 §4） |

构建命令：

```bash
./build.sh                              # 调试签名 → photodelaiuibridge.apk
./build.sh release                      # ★ 发布签名（内置 release.keystore）→ photodelaiuibridge-release.apk
./build.sh release <属性文件>            # 备选：外部/自备 keystore，模板见 keystore.external.properties.template
```

两套构建共用同一条流水线（`aapt2 compile/link` → `javac` → `d8 --release` → 注入 dex → `zipalign -p 4` → `apksigner sign`），
**只有第 8 步签名与其后校验不同**，功能行为完全一致。

工具链定位全部可用环境变量覆盖（`ANDROID_SDK_ROOT` / `JAVA_HOME` / `PYTHON` / `BT_DIR`），
无 SDK/JDK 时会给出明确报错。脚本默认在 **Windows（Git Bash）** 下运行，可执行文件带 `.exe` 后缀。

> **★ 校验认「证书指纹」，不认 APK 文件 SHA-256**：APK 文件哈希每次构建都会变（zip 内时间戳不同），
> **证书 SHA-256 才是稳定不变量**。

---

## 2. ⚠️ 两种签名不能混装

| 项目 | debug 包 | release 包 |
|:--|:--|:--|
| 证书 DN | `CN=Android Debug, O=Android, C=US` | `CN=Photo Delete AIUI Bridge, O=osagem, C=CN` |
| `adb install -r` 跨签名覆盖 | ❌ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | ❌ 同上 |

**为什么这是大问题**：切换签名必须**先卸载**，而卸载会连锁触发两件事——

1. **「所有文件访问」授权丢失**（`appops get` 回到 `Default mode: default`）；
2. **应用 uid 会变** → 旧 uid 级的 `MANAGE_EXTERNAL_STORAGE: allow` 一并失效。

卸载重装后**必须在眼镜上重新授权一次**（打开 App →「启用」→ 系统「所有文件访问」页开启开关）。

---

## 3. 从 debug 切换到 release（只需做一次）

```bash
S=<你的设备序列号>            # 用 adb devices 查看
./build.sh release

# ① 记录切换前状态（可选）
adb -s $S shell "appops get com.osagem.photodelaiuibridge MANAGE_EXTERNAL_STORAGE"

# ② 卸载调试包（会丢授权，预期内）
adb -s $S uninstall com.osagem.photodelaiuibridge

# ③ 装正式包
adb -s $S install photodelaiuibridge-release.apk

# ④ ★ 在眼镜上手动操作：打开「照片删除智能体服务」→ 底部「启用」
#     → 系统「所有文件访问」页 → 镜腿前滑移动焦点到开关 → 单击开启 → 双击返回

# ⑤ 校验
adb -s $S shell "appops get com.osagem.photodelaiuibridge MANAGE_EXTERNAL_STORAGE"
adb -s $S forward tcp:38999 tcp:38999
curl -s --max-time 6 http://127.0.0.1:38999/ping; echo
curl -s --max-time 8 http://127.0.0.1:38999/info; echo
adb -s $S forward --remove tcp:38999
```

> ⚠️ 覆盖安装并非 100% 保授权：`adb install -r --no-incremental`（Streamed Install）实测会连
> uid 级 + 包级授权一起清掉。**每次覆盖后都复查一遍 `appops get`**，缺失就重新授权一次。

---

## 4. 凭据保管（丢了就再也发不了更新）

> ★ **凭据存放在工程目录之外**：`build.sh` 从 **`~/.photodelaiuibridge/`**
> （Windows 即 `C:\Users\<你>\.photodelaiuibridge\`）读写密钥与口令；默认位置可用环境变量
> **`BRIDGE_KEYSTORE_DIR`** 覆盖。因此**本工程目录本身不含任何敏感文件**，可整体公开/分享。
> 首次运行 `./build.sh release` 时若该目录为空，脚本会自动生成密钥与随机口令。

| 文件 | 说明 |
|:--|:--|
| `~/.photodelaiuibridge/release.keystore` | 发布私钥（PKCS12 / RSA 4096）— **工程外**，需自行备份 |
| `~/.photodelaiuibridge/keystore.properties` | 口令文件 — **工程外**，必须与 keystore 一起备份 |
| `~/.photodelaiuibridge/debug.keystore` | 调试私钥（`./build.sh` 用，可随时重建，不用于发布） |

**发布凭据必须成对保管**（U 盘 / 密码管理器 / 私有仓库）。同包名更新**必须用同一把密钥签名**，
否则已安装的用户无法覆盖升级。

> **★ 防呆保护**：若 `release.keystore` 存在而 `keystore.properties` 缺失，脚本会**直接报错退出**，
> 绝不静默生成新口令；只有两者都不存在时才会新建密钥，并打出醒目警告。

查看指纹 / 校验密钥：

```bash
KS="${BRIDGE_KEYSTORE_DIR:-$HOME/.photodelaiuibridge}"
KT="<Android Studio JBR 或 JDK>/bin/keytool"
"$KT" -list -v -keystore "$KS/release.keystore" -storetype PKCS12 -alias photodelaiuibridge
```

---

## 5. release 构建做了 / 刻意没做什么

**已具备**

| 项 | 状态 |
|:--|:--|
| `android:debuggable` | 未设置 → **false** |
| 代码优化 | `d8 --release --min-api 28` |
| 4 字节对齐 | `zipalign -p 4` 校验通过 |
| 签名方案 | v1（JAR）+ v3 双签 |
| | ※ v2 被 apksigner 按 `minSdk=32` 自动省略 —— v3 已覆盖 API 28+，Android 12 正常安装 |
| 备份开关 | `android:allowBackup="false"` |
| 监听地址 | **仅回环**：同时绑定 `127.0.0.1`(IPv4) 与 `::1`(IPv6)，局域网内不可达 |
| 访问令牌 | 进程启动时 `SecureRandom` 随机生成（非固定常量） |
| 版本号 | `versionCode=3` / `versionName=1.0.2`（1.0.2：转码音频流式 remux / 任务表与 probe 缓存上限 / HTTP 线程池 3 / 续转内存门禁） |

**刻意没做（附理由）**

- **R8 混淆/裁剪**：源码零反射（`Class.forName`/`getMethod` 全无），理论可安全混淆；但收益很小，
  而任何混淆改动都需真机回归（卸载重装 + 重新授权），**收益低于代价**。
- **收敛明文流量**：当前 `android:usesCleartextTraffic="true"` 全放开。桥只对 `http://127.0.0.1:38999`
  做启动自检 `/ping`，理论上可用 `networkSecurityConfig` 只放行回环；但该策略对 IP 字面量的生效行为需真机实测，
  一旦失效会导致「启动自检失败 → 服务起不来」的硬故障，**风险大于收益**。

---

## 6. 校验命令速查

```bash
BT="${ANDROID_SDK_ROOT}/build-tools/35.0.1"
A="photodelaiuibridge-release.apk"

"$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" verify --verbose --print-certs "$A"
"$BT/aapt2.exe" dump badging "$A" | head -6
"$BT/aapt2.exe" dump xmltree "$A" --file AndroidManifest.xml | grep -i debuggable
"$BT/zipalign.exe" -c 4 "$A" && echo "对齐 OK"
sha256sum "$A"
```

---

## 7. 版本策略

当前 `versionCode=3` / `versionName=1.0.2`。
**每次对外发布都要递增 `versionCode`**（改 `AndroidManifest.xml`），`versionName` 按语义化版本递进。
签名不变则用户可无感覆盖升级。
