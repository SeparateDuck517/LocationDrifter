# LocationDrifter (定位漂移助手)

专为已 Root + LSPosed 设备设计的 **零三方应用侵入（Zero App Hook）** 地图选点与全局定位防漂移测试工具，无需 Hook 任何第三方业务 App 即可全局生效。

## 核心原理（严守「零三方应用注入」原则）
1. **作用域仅限系统进程（`android` + `com.android.phone`）**：
   - 绝不向任何第三方业务 App 注入 Hook 代码，彻底规避应用层 Hook 检测、加固壳冲突与签名校验。
2. **`system_server` 主动式位置与 GNSS 卫星注入**：
   - 在系统服务层直接挂钩 `LocationManagerService`、`LocationProviderManager`、`GnssManagerService`、`LocationResult` 与 `Location`。
   - 当三方应用调用 `getLastLocation`、`getCurrentLocation` 或 `registerLocationListener` 时，即使室内无真实 GPS 信号，系统服务层也会毫秒级合成并下发虚拟 `WGS-84` 坐标与满格 `GnssStatus`（10 颗工作卫星），并在写入 Binder `Parcel` 前彻底抹除 `mIsFromMockProvider` 与 `mFieldsMask`（`HAS_MOCK_PROVIDER_MASK`）标记。
3. **Binder IPC 全链路基站/Wi-Fi 源头隔离**：
   - **Wi-Fi 隔离**：拦截 `WifiServiceImpl.getScanResults`、跨进程 `ParceledListSlice`（含 Android 16 独立 Wi-Fi Mainline APEX）及 `WifiInfo.writeToParcel`（脱敏 `mBSSID`、`mMacAddress`、Wi-Fi 7 `mApMldMacAddress`）。
   - **蜂窝基站与 `ServiceState` 隔离**：拦截 `PhoneInterfaceManager`、`TelephonyRegistry` 以及 Android 10+ 的 `NetworkRegistrationInfo.writeToParcel`（抹除 `ServiceState` 中的驻留基站 `mCellIdentity`），阻断三方定位 SDK 获取真实基站 CID/TAC 或周边 Wi-Fi 指纹。
4. **定向内核阻断**：
   - `iptables` / `ip6tables` 针对特定网络定位接口返回 `REJECT` 迫使定位 SDK 秒级回落至系统 GPS；同时放行常用逆地理编码接口，兼顾防漂移与地址解析。

---

## 使用步骤

1. 安装打包好的 `app-debug.apk`。
2. 打开 **LSPosed 管理器** -> **模块** -> 找到并启用 **「定位漂移助手」**。
3. 在模块作用域中仅需勾选（默认已配置）：
   - **系统框架** (`android`)
   - **电话服务** (`com.android.phone`)
   *(无需勾选任何第三方应用，真正零三方应用注入)*
4. 修改系统框架/电话服务作用域后，**重启一次手机（或执行软重启）** 使系统服务层 Hook 完整生效。
5. 打开 **定位漂移助手**：
   - 授予 Root 权限（会自动完成模拟位置授权与安全设置权限授予）。
   - 在地图上点击/搜索目标位置，点击 **「一键开启定位漂移」** 即可。

---

## 开源协议 (License)

本项目基于 [MIT License](LICENSE) 开源，仅供个人学习、系统框架研究与定位功能调试使用。
