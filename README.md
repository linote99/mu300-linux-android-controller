# MU300 Linux OTG 控制器

这是一个独立的 Android 控制端 APK：安装在支持 USB Host/OTG 的手机或平板上，通过 USB ADB 控制另一台 ZTE MU300/F50/U30 Air。控制端本机不需要 root；`su`、Magisk 和作者 helper 命令只在 USB 连接的目标设备上执行。

当前版本：**0.4.0**（versionCode 13，最低 Android API 26）。目标设备需要开启 USB 调试、允许控制端的 ADB 主机密钥，并具备 root 与 Magisk 26+。应用会动态读取上游 MU300 Linux 发布的 Magisk 安装 ZIP 和校验清单，在控制端与目标端复核 SHA-256；dry-run 计划通过后，必须由用户明确确认 `INSTALL` 才会执行安装。

## 下载

- GitHub Release：<https://github.com/linote99/mu300-linux-android-controller/releases/tag/v0.4.0>
- APK：<https://github.com/linote99/mu300-linux-android-controller/releases/download/v0.4.0/MU300-Linux-OTG-Controller-0.4.0.apk>
- 源码 ZIP：<https://github.com/linote99/mu300-linux-android-controller/releases/download/v0.4.0/MU300-Linux-OTG-Controller-0.4.0-source.zip>
- 夸克下载：<https://pan.quark.cn/s/d2c91b254022>
- 项目博客：<https://www.linote.top/posts/mu300-linux-android-controller/>

下载后建议同时使用同名 `.sha256` 文件校验文件完整性。

## 使用前提

1. **控制手机**：Android 8.0（API 26）或更新版本，支持 USB Host/OTG；准备可靠的 OTG 线材和供电。
2. **目标设备**：MU300/F50/U30 Air，已开启 USB 调试并能授予 Shell root；Magisk versionCode 为 26000 或更高。无屏幕目标要提前解决 ADB 主机密钥授权。
3. **操作条件**：目标处于可操作状态，安装所需的官方 ZIP 能够完成下载和校验。首次使用请先阅读目标状态、计划页和所有风险提示。

## 四步操作

1. **设备连接**：在控制手机安装 APK，用 OTG 连接目标设备，授予 USB/ADB 权限并扫描设备。
2. **系统与 ZIP**：选择上游 MU300 Linux 的 Magisk 安装 ZIP 并填写安装问卷；应用显示目标系统、版本、内核、设备、启动槽、region、更新类型和启动偏好等信息。
3. **目标传输**：应用将内置 helper 和所选 ZIP 推送到目标，分别进行身份与 SHA-256 校验。USB 中断后重新连接会查询并跟随原任务，不会自动启动第二个安装任务。
4. **计划与安装**：先运行 dry-run，确认完整计划和风险提示；只有计划通过并由用户明确输入 `INSTALL` 后，才执行正式安装。安装期间不要切换步骤、强制停止应用、拔线或断电。

当前问卷覆盖单系统安装的 Linux 默认启动、失败回退次数、导入 Android 热点设置、Mali GPU/OpenCL 支持和可选新密码。自定义密码只在本次进程内暂存，经 helper 的受限 stdin 传输，不写入日志或本机事务文件。多系统、SD 卡、VPN/面板额外模块、卸载及设备管理功能不在本版本范围内。

## 清理入口

“关于与使用说明”页提供目标端暂存清理和本机占用清理。目标端清理只处理本应用在 `/data/local/tmp/mu300-web` 中的预期普通文件；运行中的任务、未知条目、符号链接或文件大小变化都会阻止清理，不会递归删除，也不会触碰 `/data/adb/mu300-web` 中的任务、备份、ZIP 或待续装资料。

本机清理按缓存、运行日志和已完成恢复资料分类。已完成恢复资料必须先导出 ZIP 并回读校验；未完成、未知或异常的恢复事务不提供删除选项。ADB 主机密钥始终保留。备份 ZIP 含设备识别信息，应按敏感资料保管。

## 实验性 userdata 缩容

连续点击页面顶部“MU300 Linux”标题 5 次可打开实验性入口，再次连续点击 5 次关闭。该入口不是普通安装流程的一部分，仅适用于满足严格预检条件的 32GB 机型。

此操作会清空 Android 全部 userdata：应用、照片、下载、账户和设置都会被删除。开始前必须备份个人文件、Magisk 及需要恢复的应用 APK；应用 APK 备份不包含 App 数据，照片、下载和其他个人文件必须另行备份。完整 eMMC 不会被备份，控制端保存的是作者恢复流程要求的 15 项 GPT/分区文件及其校验值。

应用只有在目标 root、Magisk、Android 启动状态、加密状态、GPT 双表/CRC、设备型号、空闲区和现有分区等检查全部通过后才会开放操作。写入前需要明确输入 `ERASE`，重启还需要单独确认。准备或写入过程中若出现异常，不会自动重试或盲目回滚：保留应用数据、事务记录和所有备份，不要清除应用数据或卸载应用。完成清除后，按页面提示完成 Android 初始设置、重新开启 ADB、恢复 Magisk/应用并重新授权目标 Shell root，再继续恢复流程。

任何写入期间都不要拔线、断电或强制停止应用。请把该功能视为高风险实验性高级入口，并在操作前准备独立的恢复方案。

## 构建与测试

```bash
bash build.sh
./tests/verify-build.sh
```

构建脚本包含纯 Java helper 协议、GPT、缩容几何、恢复事务、远端任务门控和暂存清理合成测试，以及清理入口、危险确认、文件保护、推送反馈、页面导航和 INSTALL 弹窗的静态检查。APK 包名为 `org.linote.mu300controller`。

## 上游来源与许可证

本项目是非官方 Android 客户端，不代表 MU300 Linux 上游项目。APK 内嵌作者 helper 脚本副本，并保留 MIT 许可证与必要来源说明；APK 不包含 GPL 内核补丁、GPL 内核源代码或内核二进制。MU300 Linux 的 Magisk 安装 ZIP 在运行时从作者 GitHub Release 获取并按上游校验清单验证。

- [作者仓库](https://github.com/dikeckaan/mu300-linux)
- [上游 Android helper](https://github.com/dikeckaan/mu300-linux/blob/main/tools/android-web.sh)
- [网页 USB 客户端](https://github.com/dikeckaan/mu300-linux/blob/main/site/assets/web-installer/device.js)
- [安装自动化协议](https://github.com/dikeckaan/mu300-linux/blob/main/docs/INSTALLER-AUTOMATION.md)
- [实验性 make-room 与恢复](https://github.com/dikeckaan/mu300-linux/blob/main/install.sh)、[resume.py](https://github.com/dikeckaan/mu300-linux/blob/main/tools/resume.py)、[resume.sh](https://github.com/dikeckaan/mu300-linux/blob/main/tools/resume.sh)

详细的技术研究与上游说明见 [RESEARCH.md](RESEARCH.md) 和 [UPSTREAM-NOTICES.md](UPSTREAM-NOTICES.md)。
