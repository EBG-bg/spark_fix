# spark_fix

这是从已归档的 `warmaislandfix` 项目独立延续的版本。由于模组 ID 已更改为 `spark_fix`，请先移除旧 JAR，再安装本项目的 JAR。

本项目最初针对 [沃玛岛服务器](https://www.warma.fans/mc)，现也为其他 Minecraft 模组游玩过程中遇到的各类问题提供客户端修复（面向 Minecraft 26.2 Fabric 客户端）。

未触发问题时尽量保持原模组和原版行为。

## 已包含功能

1. 修复 Minecraft 原版退出卡死
2. 清理 Leawind's Third Person、PatPat、TabTPS 线程
3. 避免 ALI 连接超时
4. 修复 Chat Patches 聊天记录
5. 修复原版路径点断连
6. 补全 Axiom 中文显示
7. 恢复 REI 配方摆放
8. 支持划船使用物品

**新版本：**

9. 已整合 `adofaigo` 模组
10. 已整合 `REI Recipe Bridge` 模组
11. 添加整合模组设置

[查看修复详情](#修复详情)

## 安装

下载：

- [下载 spark_fix-2.0.1.jar](https://github.com/EBG-bg/spark_fix/releases/download/v2.0.1/spark_fix-2.0.1.jar)
- [查看 v2.0.1 发布页](https://github.com/EBG-bg/spark_fix/releases/tag/v2.0.1)

1. 安装 Minecraft 26.2、Fabric Loader 和 Fabric API。
2. (如有旧版则需执行)删除旧的 `warmaislandfix-*.jar`，再将发布的 `spark_fix-2.0.1.jar` 放入客户端 `mods` 目录。
3. 不要安装调试构建，也不需要在服务器安装本模组。

## 配置

安装 Mod Menu 后，可在模组列表中打开设置页面：

- `Axiom：遍历当前语言全部翻译文件`：默认关闭；开启后只影响字体回退时的字形收集。
- 在 `spark_fix 设置` 页面右上角点击 `整合模组设置`，可以进入整合模组页面。
- `启用 adofaigo 模组` 与 `启用 REI Recipe Bridge 模组`：首次安装时均默认关闭，主开关在重启 Minecraft 后生效。
- REI Recipe Bridge 的独立设置页面提供 `向 REI 提供原版配方`、`捕获并保存已解锁配方` 和 `刷新 REI 配方统计`；修改后会保存并刷新 REI 配方数据。

配置也会保存到 `config/spark_fix.properties`。修改后重新打开相关界面即可生效；Axiom 字形选项对下一次字体图集回退生效。

首次启动时，如果发现旧的 `config/warmaislandfix.properties`，会自动复制为 `config/spark_fix.properties`；旧文件会保留，不会被删除。

## 构建

需要 Java 25。项目使用 Gradle Wrapper：

```shell
./gradlew build
```

Windows PowerShell 或命令提示符可运行：

```powershell
.\gradlew.bat build
```

构建结果位于 `build/libs/`。Axiom、REI 与 Mod Menu 仅作为 `compileOnly` 依赖从公开 Maven 仓库获取，不会打包进成品 JAR。

## 报告问题

提交问题时请附上其中几个(至少一个)：

- 完整的 `latest.log`
- 对应的崩溃或断连报告
- 可复现步骤，以及服务器类型和代理类型（如果问题发生在多人游戏）

## 修复详情

1. 修复 Minecraft 26.2 关闭单人世界时，从集成服务器线程刷新客户端聊天界面所引发的渲染线程长时间等待。该问题来自 Minecraft 本身的线程调用，未确认由某个第三方模组导致。
2. 清理 Leawind's Third Person、PatPat 和 TabTPS 在游戏关闭后遗留的非守护调度线程。它们会导致 Minecraft 窗口关闭后 Java 进程无法正常结束，与第 1 项的单人世界关闭卡死不是同一个问题。（部分模组由 AI 迁移至 Minecraft 26.2，可在沃玛岛服务器主群获取。）
3. 避免 ALI 的 JEI 兼容层在不支持 ALI 数据通道的服务器上等待多轮超时。
4. 修复 Chat Patches 记录富文本物品组件时的 `HolderSetCodec` 编码失败，并保留完整记录。
5. 修复 Minecraft 原版 `ClientWaypointManager` 接收孤立路径点 UPDATE 时的空指针断连。该异常来自服务端或代理未先发送对应 TRACK，尚未确认由某个具体插件或模组导致，也不是 Xaero 地图问题。
6. 仅在 Axiom 中文字体图集构建失败时使用受限字体回退，并在游戏内显示一次恢复提示。
7. 补充 Axiom 对称工具提示，以及剪贴板工具中“复制空气”“复制实体”“保留现有”等选项的简体中文显示，不改变英文界面。
8. Axiom 字体回退默认使用常见中文字形；可在 Mod Menu 中选择遍历当前语言的全部翻译文本。
9. 在不支持 REI 移动物品数据包的插件服上，使用原版容器点击恢复一键配方摆放。
10. 允许玩家划船时开始食用物品，并清除下船后可能残留的双手忙碌状态。
11. 将 `adofaigo` 整合到 spark_fix，不再需要单独安装；可在整合模组设置中启用或停用，切换后需重启 Minecraft。
12. 将 `REI Recipe Bridge` 整合到 spark_fix，不再需要单独安装；可在整合模组设置中启用或停用，切换后需重启 Minecraft。
13. 在 spark_fix 设置页面右上角添加“整合模组设置”入口，用于管理两个整合模组。
14. 为 REI Recipe Bridge 提供独立设置入口，包含原版配方回退、已解锁配方捕获和配方统计；内部设置可在运行中保存并刷新。

除已整合的 `adofaigo` 和 `REI Recipe Bridge` 外，其他第三方模组兼容项都是可选的；未安装对应模组时不会形成运行时依赖。

## 许可证

本项目使用 [CC0 1.0 Universal](LICENSE)。仓库已整合本项目所属的 `adofaigo` 和 `REI Recipe Bridge` 源码；不包含 Axiom、REI 或其他可选兼容模组的代码和二进制文件。

## 作者

DianBing、Codex

## 其它:
注意!本模组为纯!AI!制作(readme仅做人工轻微修改)
