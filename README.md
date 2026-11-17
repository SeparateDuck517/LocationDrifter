# LocationDrifter (定位漂移助手)

专为 Android 设计的 **零三方应用侵入（Zero App Hook）** 地图选点与全局定位防漂移测试工具，支持 **Root + LSPosed 完整模式**，同时内置 **纯免 Root 降级兼容模式**（无需 Root，也无需 Shizuku）。

---

## 双模式架构对比

| 能力维度 | 完整模式（Root + LSPosed） | 免 Root 降级兼容模式（无 Root / 无 Shizuku） |
| :--- | :--- | :--- |
| **三方应用侵入性** | **零注入**（仅作用域 `android` + `com.android.phone`） | **零注入**（纯系统公开 API） |
| **坐标注入方式** | `system_server` 主动拦截并合成 GPS/北斗 10 颗工作卫星 | 系统 `LocationManager` 标准 `TestProvider` 模拟定位 |
| **抹除 `isMock` 标记** | **支持**（在 Binder `Parcel` 序列化层彻底抹除） | **不支持**（受限于免 Root 权限边界） |
| **基站 / Wi-Fi 源头隔离** | **支持**（拦截 `WifiServiceImpl`、`WifiInfo`、`ServiceState`） | **不支持**（建议在系统位置服务中手动关闭 Wi-Fi/蓝牙扫描） |
| **网络定位域名阻断** | 内核 `iptables` / `ip6tables` 返回 `REJECT` | **局部 RST-VPN 通道**（用户态解析并主动回发 TCP RST / ICMP 不可达） |

---

## 核心原理

### 1. 完整模式（Root + LSPosed 零三方应用注入）
1. **作用域仅限系统进程（`android` + `com.android.phone`）**：
   - 绝不向任何第三方业务 App 注入 Hook 代码，规避应用层 Hook 检测、加固壳冲突与签名校验。
2. **`system_server` 主动式位置与 GNSS 卫星注入**：
   - 挂钩 `LocationManagerService`、`LocationProviderManager`、`GnssManagerService`、`LocationResult` 与 `Location`，在写入 Binder `Parcel` 前彻底抹除 `mIsFromMockProvider` 与 `mFieldsMask`（`HAS_MOCK_PROVIDER_MASK`）标记。
3. **Binder IPC 全链路基站/Wi-Fi 源头隔离**：
   - 拦截 `WifiServiceImpl.getScanResults`、`ParceledListSlice`（含 Android 16 独立 Wi-Fi Mainline APEX）、`WifiInfo.writeToParcel` 与 `NetworkRegistrationInfo.writeToParcel`（抹除 `mCellIdentity`）。
4. **定向内核阻断**：
   - `iptables` / `ip6tables` 针对特定网络定位接口返回 `REJECT` 迫使定位 SDK 秒级回落至系统 GPS。

### 2. 免 Root 降级兼容模式（无 Root / 无 Shizuku）
- 当应用检测到设备未 Root 时，会自动切换为 **免 Root 降级兼容模式** 并在顶部展示明确的状态与能力边界提示：
  1. **系统模拟定位**：通过系统「开发者选项 → 选择模拟位置信息应用」授权后，向 `gps`、`network`、`fused` 持续推送带时间步长微抖动的坐标。
  2. **主动回发 RST 的局部 VPN 通道**：仅对高频网络定位域名 IP 建立精准主机路由（不接管正常上网流量），收到定位 SDK 的 TCP/UDP 请求时直接在用户态回写 `TCP RST` 与 `ICMP Port Unreachable`，模拟内核 `REJECT` 的秒级回落效果。

---

## 使用步骤

### 方式 A：Root + LSPosed 完整模式
1. 安装打包好的 `app-debug.apk`。
2. 打开 **LSPosed 管理器** -> **模块** -> 启用 **「定位漂移助手」**（默认作用域已勾选 `android` 与 `com.android.phone`）。
3. **重启一次手机（或执行软重启）** 使系统服务层 Hook 完整生效。
4. 打开 **定位漂移助手**，授予 Root 权限，选点后点击 **「一键开启定位漂移」**。

### 方式 B：免 Root 降级兼容模式（无需 Root / 无需 Shizuku）
1. 安装 `app-debug.apk` 并打开，顶部状态栏会自动识别为 **「模式: 免Root降级兼容」** 并展开能力说明横幅。
2. 点击横幅中的 **「去授权模拟位置」**（或前往系统 `设置 → 开发者选项 → 选择模拟位置信息应用`），选择 **「定位漂移助手」**。
3. 返回应用后确认 **「系统GPS注入」** 与 **「VPN阻断(推荐)」** 已勾选，选点后点击 **「一键开启定位漂移」** 并允许 VPN 连接请求即可。

---

## 开源协议 (License)

本项目基于 [MIT License](LICENSE) 开源，仅供个人学习、系统框架研究与定位功能调试使用。
