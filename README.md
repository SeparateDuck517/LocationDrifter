# LocationDrifter (定位漂移助手)

专为 Android 设计的 **零三方应用侵入（Zero App Hook）** 地图选点与全局定位防漂移测试工具，启动时支持按设备环境自由选择 **三种运行模式**，覆盖从完整 Root + LSPosed 到免 Root Shizuku 增强及纯免 Root 零依赖场景。

---

## 三种运行模式能力对比

| 能力维度 | 1. Root + LSPosed 完整模式 | 2. 免 Root + Shizuku 增强模式 | 3. 纯免 Root 基础降级模式 |
| :--- | :--- | :--- | :--- |
| **三方应用侵入性** | **零注入**（仅作用域 `android` + `com.android.phone`） | **零注入**（纯系统 API + ADB Shell 辅助） | **零注入**（纯系统公开 API） |
| **模拟位置权限授权** | 自动授予 | **Shizuku 一键自动授予**（免进开发者选项） | 需手动去系统开发者选项设置 |
| **系统后台扫描防回弹** | 无需关闭（直接 IPC 源头隔离） | **漂移时自动关闭 Wi-Fi/蓝牙扫描，停止自动还原** | 需手动去系统位置服务中关闭 |
| **坐标与卫星注入** | `system_server` 主动合成 GPS/北斗 10 颗工作卫星 | 系统 `LocationManager` 标准 `TestProvider` | 系统 `LocationManager` 标准 `TestProvider` |
| **抹除 `isMock` 标记** | **支持**（Binder `Parcel` 序列化层彻底抹除） | **不支持**（受 SELinux 对 `shell` 域限制） | **不支持** |
| **基站 / Wi-Fi 源头隔离** | **支持**（拦截 `WifiServiceImpl`、`WifiInfo`、`ServiceState`） | **不支持**（无法隐藏当前连接 Wi-Fi 或驻留基站） | **不支持** |
| **网络定位域名阻断** | 内核 `iptables` / `ip6tables` 返回 `REJECT` | **局部 RST-VPN 通道**（用户态主动回发 TCP RST） | **局部 RST-VPN 通道** |

---

## 核心原理

### 1. Root + LSPosed 完整模式（零三方应用注入）
1. **作用域仅限系统进程（`android` + `com.android.phone`）**：绝不向任何第三方业务 App 注入 Hook 代码，规避应用层 Hook 检测、加固壳冲突与签名校验。
2. **`system_server` 主动式位置与 GNSS 卫星注入**：挂钩 `LocationManagerService`、`LocationProviderManager`、`GnssManagerService`、`LocationResult` 与 `Location`，在写入 Binder `Parcel` 前彻底抹除 `mIsFromMockProvider` 与 `mFieldsMask`（`HAS_MOCK_PROVIDER_MASK`）标记。
3. **Binder IPC 全链路基站/Wi-Fi 源头隔离**：拦截 `WifiServiceImpl.getScanResults`、`ParceledListSlice`（含 Android 16 独立 Wi-Fi Mainline APEX）、`WifiInfo.writeToParcel` 与 `NetworkRegistrationInfo.writeToParcel`（抹除 `mCellIdentity`）。
4. **定向内核阻断**：`iptables` / `ip6tables` 针对特定网络定位接口返回 `REJECT` 迫使定位 SDK 秒级回落至系统 GPS。

### 2. 免 Root + Shizuku 增强模式（免 Root 推荐）
- 无需解锁 Bootloader 或 Root，只需安装并启动 **Shizuku**（Android 11+ 支持无线调试免电脑启动）：
  1. **一键自动授权**：通过 `UID 2000` 自动执行 `appops set` 放行模拟位置权限及定位/通知权限。
  2. **自动扫描压制与恢复**：开启漂移时自动备份并关闭 `wifi_scan_always_enabled` 与 `ble_scan_always_enabled`，大幅降低室内 Wi-Fi/蓝牙辅助定位回弹；停止漂移时自动按原值恢复。
  3. **局部 RST-VPN 拒止通道**：仅对高频网络定位域名 IP 添加精准主机路由，收到请求时直接回写 `TCP RST` 与 `ICMP Port Unreachable`，不影响正常上网。

### 3. 纯免 Root 基础降级模式（零外部依赖）
- 既无 Root 也未安装 Shizuku 时可用：通过系统「开发者选项 → 选择模拟位置信息应用」授权本应用，配合局部 `RST-VPN` 通道实现基础模拟定位。

---

## 使用步骤

1. 安装打包好的 `LocationDrifter-release-signed.apk` 并打开，启动时会自动弹出 **「选择运行模式」** 卡片界面，清晰展示三种模式的实时环境状态与能力差别（之后也可随时点击顶部 **「切换模式」** 重新打开）。
2. 根据设备条件选择对应模式：
   - **完整模式**：在 LSPosed 中启用模块并确认作用域（`android` + `com.android.phone`），按应用提示重启一次手机即可生效。
   - **Shizuku 增强模式**：确保 Shizuku 服务已启动，在弹窗内点击 **「授权 Shizuku」** 放行后直接选点开启漂移。
   - **纯免 Root 基础模式**：按引导在系统「开发者选项」中选中本应用为模拟位置信息应用，勾选 `系统GPS注入` 与 `VPN阻断(推荐)` 即可开启。

---

## 开源协议 (License)

本项目基于 [MIT License](LICENSE) 开源，仅供个人学习、系统框架研究与定位功能调试使用。
