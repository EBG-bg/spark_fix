## 本模组为 针对在游玩沃玛岛服务器www.warma.fans/mc中遇到的各类问题提供的修复模组(面向 Minecraft 26.2 Fabric 客户端)

未触发问题时尽量保持原模组和原版行为。

## 已包含修复

- 修复退出世界或关闭客户端时，集成服务器线程等待导致的长时间卡死。
- 清理 Leawind's Third Person、PatPat 和 TabTPS 遗留的非守护调度线程。(有部分模组为ai迁移至26.2版本 可在沃玛岛服务器主群获取)
- 避免 ALI 的 JEI 兼容层在不支持 ALI 数据通道的服务器上等待多轮超时。
- 修复 Chat Patches 记录富文本物品组件时的 `HolderSetCodec` 编码失败，并保留完整记录。
- 接收服务端孤立的路径点 UPDATE 时，将其作为首次 TRACK 保存，避免 `ClientWaypointManager` 空指针断连。
- 仅在 Axiom 中文字体图集构建失败时使用受限字体回退，并在游戏内显示一次恢复提示。
- 在不支持 REI 移动物品数据包的插件服上，使用原版容器点击恢复一键配方摆放。
- 允许玩家划船时开始食用物品，并清除下船后可能残留的双手忙碌状态。

所有第三方模组兼容项都是可选的；未安装对应模组时不会形成运行时依赖。

## 安装

1. 安装 Minecraft 26.2、Fabric Loader 和 Fabric API。
2. 将发布的 `warmaislandfix-1.7.2.jar` 放入客户端 `mods` 目录。
3. 不要安装调试构建，也不需要在服务器安装本模组。

## 构建

需要 Java 25。项目使用 Gradle Wrapper：

```shell
./gradlew build
```

Windows PowerShell 或命令提示符可运行：

```powershell
.\gradlew.bat build
```

构建结果位于 `build/libs/`。Axiom 与 REI 仅作为 `compileOnly` 依赖从公开 Maven 仓库获取，不会打包进成品 JAR。

## 报告问题

提交问题时请附上其中几个(至少一个)：

- 完整的 `latest.log`
- 对应的崩溃或断连报告
- 可复现步骤，以及服务器类型和代理类型（如果问题发生在多人游戏）

## 许可证

本项目使用 [CC0 1.0 Universal](LICENSE)。仓库不包含 Axiom、REI 或其他第三方模组的代码和二进制文件。

## 其它:
注意!本模组为纯!AI!制作(readme仅做人工轻微修改)
