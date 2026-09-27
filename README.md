# spark_fix

面向 **Minecraft 26.2 Fabric 客户端**的修复模组，源自 `warmaislandfix`，最初用于 [沃玛岛服务器](https://www.warma.fans/mc)。未触发问题时尽量保持原版和原模组行为。

当前源码版本为 **2.1.0（尚未发布）**，下文功能与配置以该版本为准；公开下载仍为 **2.0.1**。

## 已包含功能

1. 修复原版关闭单人世界时卡死
2. 清理 TabTPS 退出后遗留的线程，解决窗口关闭后进程不退出
3. 避免 ALI 在不兼容的服务器上长时间等待
4. 修复 Chat Patches 富文本聊天记录保存失败
5. 修复原版路径点更新引起的断连
6. 补全 Axiom 部分中文显示，并在中文字体加载失败时回退
7. 恢复 REI 在插件服上的一键配方摆放
8. 支持划船使用物品

**整合功能：**

9. 已整合 `adofaigo` 模组
10. 已整合 `REI Recipe Bridge` 模组
11. 可选 `better litematica setting`（投影设置）

第三方模组修复仅在安装对应模组时生效。

## 安装

[下载 spark_fix-2.0.1.jar](https://github.com/EBG-bg/spark_fix/releases/download/v2.0.1/spark_fix-2.0.1.jar) · [发布页](https://github.com/EBG-bg/spark_fix/releases/tag/v2.0.1)

1. 使用 Java 25，安装 Minecraft 26.2、Fabric Loader 0.19.3 或更高版本，以及对应版本的 Fabric API。
2. 将 JAR 放入客户端 `mods` 目录；停用旧的 `warmaislandfix`、独立版 `adofaigo` 和 `REI Recipe Bridge`。
3. 使用投影设置时，另行安装 Litematica、MaLiLib 及需要的投影附属。

`owo-lib 0.13.1+26.2` 为可选界面依赖，未安装也可使用全部功能。

## 提交issue

提交问题时请至少提供一项：

- 完整的 `latest.log`
- 对应的崩溃或断连报告
- 可复现步骤；多人游戏问题请注明服务器和代理类型

收集默认常用别名种类

- 合理理由即可

## 配置

安装 Mod Menu 后打开 `spark_fix 设置`，右上角进入 `整合模组设置`。三个整合功能默认关闭，启用或停用须重启 Minecraft；点击模组卡片可展开子设置。

### 投影设置

从整合页或 Litematica 主菜单进入，自动识别本体和关联附属的设置。

- 收藏项与其他设置分别搜索、按分类筛选；左上角可隐藏指定模组的选项，默认不影响收藏。
- 每行显示 **1～5 张卡片**，高度自动适应内容。
- 右键收藏或取消收藏；拖动顶部白条排序，限所属区域，其他设置还限当前分类。
- 点击名称旁的 `+` 编辑主名和搜索别名，悬停 `i` 查看原版说明。
- 右上角重置只恢复其他设置的布局，保留收藏和设置值；单项重置恢复该选项的默认值。
- 支持原版快捷键、数字滑条/输入切换、自制调色板和渲染层设置。投影页滚动速度独立调整，最高 **4 倍**。

关闭投影设置页时统一保存选项、布局和别名。

### 配置文件与别名包

主配置保存在实例的 `config/spark_fix.properties`；个人别名保存在 `config/spark_fix/常用别名_自定义.json`。

将 [常用别名_默认_蓝图.json](_artifacts/config-presets/2026-09-27/常用别名_默认_蓝图.json) 放入 `config/spark_fix/` 可补充“蓝图”等搜索词。别名包只追加别名，不覆盖主名和设置值；自制包格式见 [别名包说明](_artifacts/config-presets/README.md)。

## 构建

使用 Java 25，在项目目录运行：

```powershell
.\gradlew.bat compileClientJava
.\gradlew.bat build
git diff --check
```

成品位于 `build/libs/spark_fix-2.1.0.jar`。

## 许可证

本项目使用 [CC0 1.0 Universal](LICENSE)。

## 作者

DianBing、Codex

## 其它:

注意!本模组为纯!AI!制作(readme仅做人工轻微修改)
