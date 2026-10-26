# 上游代码与许可证说明

本独立 APK 内嵌了 MU300 Linux 作者仓库 `dikeckaan/mu300-linux` 的 WebUSB 设备端 helper 脚本副本，文件位于 `app/src/main/assets/upstream/device/`。该目录带有由作者站点构建流程生成的 `SHA256SUMS`，用于校验这些原样拷贝的脚本。项目内 `app/src/main/assets/upstream/LICENSE` 保留了作者仓库根目录的 MIT 许可证原文；作者版权声明适用于其开源软件副本。

本项目的 Android Java 代码另行移植了作者的 `tools/resize-last-partition.py`、`tools/resume.py` 和 `tools/resume.sh` 所体现的 GPT 校验、make-room 事务和恢复检查逻辑。上游来源链接：<https://github.com/dikeckaan/mu300-linux>、<https://github.com/dikeckaan/mu300-linux/blob/main/install.sh>、<https://github.com/dikeckaan/mu300-linux/blob/main/tools/resize-last-partition.py>、<https://github.com/dikeckaan/mu300-linux/blob/main/tools/resume.py>、<https://github.com/dikeckaan/mu300-linux/blob/main/tools/resume.sh>。缩容仍是作者明确标注的实验性路径。

作者许可证明确指出 `kernel/patches/*.patch` 修改 GPL-2.0 内核代码。本 APK 不包含这些内核补丁、GPL 内核源代码或内核二进制；用户选择的官方 Magisk ZIP 仍由作者 GitHub Release 动态下载并按其官方发布校验清单验证。
