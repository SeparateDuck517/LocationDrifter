# LocationDrifter (定位漂移助手)

Android 地图选点与定位防漂移工具，采用 **零三方应用注入（Zero App Hook）** 架构，支持 **Root + LSPosed**、**Shizuku 增强** 与 **纯免 Root** 三种运行模式。

## 模式对比

| 能力维度 | Root + LSPosed 完整模式 | 免 Root + Shizuku 增强模式 | 纯免 Root 基础模式 |
| :--- | :--- | :--- | :--- |
| **三方应用侵入性** | **零注入**（仅作用域 `android` + `com.android.phone`） | **零注入**（系统 API + ADB Shell） | **零注入**（纯系统公开 API） |
| **模拟位置授权** | 自动授予 | **Shizuku 一键自动授权** | 需在开发者选项手动设置 |
| **后台扫描防回弹** | 无需关闭（IPC 源头隔离） | **漂移时自动关闭，停止自动恢复** | 需在系统位置设置手动关闭 |
| **卫星与模拟标记** | `system_server` 合成 12 颗卫星并抹除模拟标记 | 标准 `TestProvider`（保留模拟标记） | 标准 `TestProvider`（保留模拟标记） |
| **基站 / Wi-Fi 隔离** | **完整支持**（Parcel 层源头隔离） | 支持指定包名扫描隔离 | 不支持 |
| **网络定位接口阻断** | 内核 `iptables` / `ip6tables` 阻断 | 局部 DNS 分流 + RST-VPN | 局部 DNS 分流 + RST-VPN |

## 快速开始

1. 安装 `LocationDrifter-release-signed.apk` 并打开，在启动弹窗或顶部 **「切换模式」** 中选择运行模式：
   - **Root + LSPosed 完整模式**：在 LSPosed 中启用本模块，确认作用域仅勾选 `android` 与 `com.android.phone`，重启手机后生效。
   - **免 Root + Shizuku 增强模式**：启动 Shizuku 服务后点击 **「授权」** 自动放行权限即可使用。
   - **纯免 Root 基础模式**：在系统 `开发者选项 → 选择模拟位置信息应用` 中选中本应用即可使用。
2. 在地图点击、搜索地名或输入坐标（支持 `GCJ-02` / `WGS-84` / `BD-09` 自动换算），点击 **「开启定位漂移」**；运行中可随时选点后点击 **「同步新选点」** 实时更新。

## 开源协议

[MIT License](LICENSE)
