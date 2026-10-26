# Research notes — Android OTG controller direction

## Corrected target

The controller APK is installed on a *separate Android phone/tablet* acting as USB host, connected over USB OTG to the target ZTE MU300/F50/U30 Air. The target runs rooted Android with USB debugging enabled. Do not use the previous same-device APK as the product path. The previous project `/workspace/mu300-linux-android-installer` was based on the wrong assumption and should not be delivered as this task's solution.

## Official web installer behavior

- Official Chinese page: https://kaandikec.com/mu300-linux/zh/web-installer/
- Official install guide: https://kaandikec.com/mu300-linux/zh/install/
- Official implementation notes: https://github.com/dikeckaan/mu300-linux/blob/main/docs/web-installer.md
- Official web device client: https://github.com/dikeckaan/mu300-linux/blob/main/site/assets/web-installer/device.js
- Official helper: https://github.com/dikeckaan/mu300-linux/blob/main/tools/android-web.sh

The website is a USB ADB client. It pushes a fixed helper to `/data/local/tmp/mu300-web`, verifies helper checksums, authenticates via ADB, probes Android and root, receives the selected official Magisk ZIP, verifies the release SHA-256 on target, then runs the ZIP's own installer under root. It first runs `MU300_DRY_RUN=1` to show a target-derived plan, then asks for `INSTALL` before doing the real install. Choices are passed in `MU300_ANSWERS`; progress events use `MU300_PROGRESS=1`. The official code is at the links above; do not invent partition write commands.

Official page says Android Chrome + OTG can in principle run the web page, but it is not designed or tested for mobile; controller Android/OTG and U30 Air were not tested by upstream. The webpage's end-to-end tested case was F50 + macOS Chrome, not U30 Air. Target needs root/Magisk 26+, USB debugging, and target-side ADB authorization. First-time ADB host key authorization may require tapping Allow on target; U30 Air has no screen, so the user must already have a way to authorize the key or use an existing authorized host key. Android OTG power stability is important.

## Existing local reference only

The untouched `/workspace/openwrt-android-installer` contains generic ADB-over-USB host protocol classes that can be examined or copied into the separate new project as reference: `UsbAdbTransport.java`, `AdbConnection.java`, `AdbKey.java`, plus Android USB enumeration/permission flow around `JobManager.java` lines 700–830. They implement ADB packets, RSA authorization, shell, and sync push. Do not edit that project. Its install logic is an OpenWrt package installer and is not a substitute for upstream MU300 Linux web installer logic.

## Current upstream release checked

At the time of inspection the official latest tag was `v2026.10.26.1`. Current Magisk asset naming is `mu300-magisk-<tag>-<system>-k<kernel>.zip`; OS slugs include `openwrt`, `openwrt-luci`, `ubuntu-24.04`, and `ubuntu-26.04`; Ubuntu 26.04 has no 5.4 kernel ZIP. Latest release metadata and SHA256SUMS should be fetched dynamically at runtime and verified before transferring. Avoid pinning these current values into the app.


## 本轮控制端实现（尚待完整构建与模拟审计）

- 新工程：`/workspace/mu300-linux-android-controller`，包名 `org.linote.mu300controller`；仅使用 USB Host ADB 控制目标，不会在控制端执行 root。
- 当前移植代码以作者 `tools/android-web.sh`、`site/assets/web-installer/device.js`、`flow.js`、`app.js` 与 `docs/INSTALLER-AUTOMATION.md` 为协议来源；helper/checksum 随独立 APK 内嵌，ZIP 先验证 SHA256SUMS，再由 helper 接收、验证 manifest/SHA、dry-run 并展示参数计划，只有用户输入 `INSTALL` 后运行作者 helper 的正式 install job。
- helper job 在 USB 断开后保留于目标；重连读取真实 job 状态并 follow 原任务，未知安装终态会锁定重复安装。密码只经 stdin 传输、不写事务/日志。
- 新增 32GB userdata make-room 的只读预检、GPT 双表移植、容量计算、手机本地事务/15 项备份与 SHA256SUMS、marker/BCB/read-back 与 identity 恢复校验；目标 GPT 写入前必须检查目标新几何 free region。所有破坏性动作要求 ERASE 和后续单独确认重启。
- 尚未在真实 Android/U30 Air/MU300 设备测试；Android/OTG 行为、ADB 授权/供电、helper shell 行为仍是仿真和代码审计范围，不能宣称实机可用。无真实设备连接、无发布。

## 上游 32GB userdata make-room 与续接（作者源码）

- 作者源码仓库：<https://github.com/dikeckaan/mu300-linux>；缩容原实现：<https://github.com/dikeckaan/mu300-linux/blob/main/install.sh>；GPT 头/尾修改器：<https://github.com/dikeckaan/mu300-linux/blob/main/tools/resize-last-partition.py>；事务/恢复实现：<https://github.com/dikeckaan/mu300-linux/blob/main/tools/resume.py> 与 <https://github.com/dikeckaan/mu300-linux/blob/main/tools/resume.sh>；自动化协议：<https://github.com/dikeckaan/mu300-linux/blob/main/docs/INSTALLER-AUTOMATION.md>。
- 32GB userdata make-room 是作者标注的实验性行为，不是 Magisk ZIP 默认安装路径。上游在确认目标、槽位、分区表和加密状态后，先把 GPT head/tail、misc head、两槽 boot 和两槽 boot chain 备份到控制端并逐项校验 SHA-256；事务记录会绑定设备序列号、eMMC CID、槽位、userdata/GPT 几何、容量、预留区域、token 与 ZIP 内工具/负载身份。
- 上游写入顺序不可变：备份 GPT → 主 GPT → 在新 Linux region 写入 4096 字节 token marker → misc 中写入 2048 字节 recovery wipe_data BCB；每一步都读回校验。resume 会检查 recovery 请求已被清理、设备身份/GPT/region/token 与保存事务一致，再调用同一安装器；prepared-request 状态拒绝续接。
- 缩容会清除 Android userdata，应要求用户先自行备份所有 Android 个人数据。APK 本机备份 GPT head/tail、misc head、两槽 boot 和两槽 boot chain 并逐项 SHA-256 校验；不备份完整 eMMC。任何身份、加密、GPT、CRC 或 read-back 不一致都必须停止；无真实设备验证时不得声称可安全实机运行。
- 上游 Magisk ZIP 的打包清单来自 <https://github.com/dikeckaan/mu300-linux/blob/main/tools/make-magisk-zips.sh> 与 <https://github.com/dikeckaan/mu300-linux/blob/main/android/magisk/computer/files.txt>；resume identity 使用 ZIP 内 computer/tools 与 computer/boot 文件以及 mu300/manifest 的 payload 哈希。保留作者许可证和必要版权；不得把 GPL 内核复制进 APK。

## Android helper 空闲区和状态语义复核

- 最新本地源码相应位置：<https://github.com/dikeckaan/mu300-linux/blob/main/tools/android-web.sh> 的 `status`/`region_probe`/`region_find_existing`；<https://github.com/dikeckaan/mu300-linux/blob/main/tools/storage.sh> 的空闲区 16 点采样与 region bounds。
- APK 的 make-room 首次预检要求 helper status 与 Java GPT/sysfs 两方 geometries 相同、`last_end` 等于 GPT 最后一分区 end+1、`mu300root` 分区数为 0、没有 `/data` foreign image、没有 existing Linux filesystem，并独立 raw-read 16 点确认目标 region 每点仅为 NUL/0xFF；unknown dirty 值按 fail-closed 处理。
- Python/Android identity JSON 采用有序键与紧凑编码；local recovery 每次重用前复核完整备份及 SHA256SUMS、ZIP SHA、system/manifest、设备 serial/CID/node、slot、GPT usable range、marker、BCB 和 region geometry。源码参考版本为当日克隆的上游 main；正式交付前需固定 commit SHA，防止分支变化。
