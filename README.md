# HY_VQ

> 极简高效的 Android 文件管理与连接工具

HY_VQ 是一个面向 Android 的文件管理器，采用「单列混排 + 双窗格独立浏览」的交互，
支持 Root / SAF / 包管理三套存储访问策略，并内置代码编辑器、压缩解压、回收站等常用能力。
应用本身以「外壳 + 模块」架构组织，文件管理为内置一级功能，其余能力以可插拔模块形式扩展。

## 功能特性

### 文件管理
- **双窗格独立浏览**：左右窗格各自维护路径、多选与滚动位置，对齐桌面级双窗口体验
- **多存储源路由**：Root / SAF（`content://`）/ 包管理 三套策略按路径自动选择
- **书签与快速访问**：侧边栏收藏目录、按扩展名归类的分类入口、回收站
- **目录缓存**：按「路径 → 条目快照 + 目录指纹（mtime / 子项数）」缓存，大目录切换显著加速
- **滚动位置记忆**：按路径缓存 `RecyclerView` 布局状态（含像素偏移），返回目录时精确还原
- **内置代码编辑器**：多标签页、语法高亮、查找替换、编码与行尾符切换、字号/等宽字体/配色偏好
- **压缩与解压**：zip 处理、批量重命名、属性查看
- **状态持久化**：排序方式、显示隐藏文件、左右窗格上次目录、活动窗格全部跨重启保留

### 其他
- **插件系统**：以标准 zip（`module.json` + `classes.dex`）分发的外部模块，可动态加载
- **在线更新**：从公开仓库只读直链读取版本清单，在线下载 APK 并校验 MD5 后交由系统安装器
- **界面**：统一的浅蓝 Material 3 配色

## 系统要求

| 项 | 版本 |
|---|---|
| 最低 Android | 9 (API 28) |
| 目标 Android | 13 (API 33) |
| 编译 SDK | 33 |
| AGP | 8.0.0 |
| Java | 11 |

## 构建

```bash
# 需先准备 Android SDK（platforms/android-33），并在 local.properties 指明路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

sh gradlew :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

> 在部分受限环境（如 Termux）中，因 FUSE 文件系统限制需用 `sh gradlew` 而非 `./gradlew`。

## 项目结构

```
app/src/main/java/com/aliya/hy_vq/
├── MainActivity.java          首页 / 侧边栏 / 设置 / 更新中心
├── ThemeHelper.java           主题叠加（固定浅蓝 ThemeOverlay）
├── CredentialStore.java       Android Keystore AES-256-GCM 凭据加密存储
├── module/                    模块框架（HyVqModule / ModuleRegistry / ModuleServices / ModuleUiKit）
│   └── impl/
│       ├── FileManagerModule  文件管理（内置一级功能）
│       └── ChatModule         聊天模块（暂未启用）
├── filemgr/                   文件列表与适配器
├── access/                    Root / SAF / 包管理 三套访问策略
├── service/                   聊天服务、WebSocket、STUN
├── terracotta/                房间联机（P2P 打洞）
└── update/                    更新管理（版本校验 / 在线安装）

tools/                         签名、审计、校验与打包脚本
plugins/                       外部模块示例（B 站 OAuth 插件）
modules/                       模块模板
libs/terracotta/               Terracotta P2P 运行库
```

## 更新源

本仓库**同时充当软件更新源**（不再单独维护更新仓库）：

| 用途 | 地址 |
|---|---|
| 版本清单 | `https://raw.githubusercontent.com/haoyou999/HY_VQ/main/latest.json` |
| 安装包 | `https://github.com/haoyou999/HY_VQ/releases/latest/download/<apk 文件名>` |

公开仓库的 raw 文件与 Release 资产均为**无鉴权直链**，因此客户端 APK 内不含任何账号或密钥。
应用内「设置 → 软件更新」即按上述地址检查新版本、下载 APK 并校验 MD5 后交由系统安装器安装。

## 开源许可

本项目以 **GNU General Public License v3.0** 发布，详见 [LICENSE](LICENSE)。

## 致谢

本项目的交互与实现参考了以下优秀的开源项目：

- [Material Files](https://github.com/zhanghai/MaterialFiles) —— 目录滚动位置记忆的设计思路
- [ZhuFiler](https://github.com/) —— 主题叠加与目录缓存的实现参考
- [Terracotta](https://github.com/bmax121/Terracotta) —— 局域网 P2P 直连
- [Material Components for Android](https://github.com/material-components/material-components-android) —— Material 3 组件
- [AndroidX](https://developer.android.com/jetpack/androidx) —— 基础支持库
