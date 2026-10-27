# 运动世界校园 · 去广告（LSPosed 模块 + 规则）

针对 **运动世界校园 7.3.93** 的非侵入式去广告方案：不改包、不重签、不触碰服务端，只在运行时关掉 App 自己的广告中台。

> App 使用网易易盾整体加固（签名绑定 + 类抽取），任何重打包/重签名都会直接崩溃，所以本项目走 LSPosed 路线。

## 基本信息

| 项目 | 值 |
|---|---|
| 目标应用 | `com.zjwh.android_wh_physicalfitness`（运动世界校园） |
| 目标版本 | 7.3.93（versionCode 407） |
| 模块版本 | **v17**（53 KB，极限性能版） |
| 测试环境 | Android 15 x86_64（MuMu 模拟器 + ARM 翻译）/ LSPosed 2.2.1 |
| 首屏启动 | **1.56–1.60 s**（仅注入空模块的对照组 1.87–2.07 s） |

## 效果

- 开屏广告：无，直接进入首页
- 首页 / 广场信息流 / 运动 / 我的：无第三方广告（广场 feed 不出现「广告」条目）
- 广告 SDK 完全不初始化：AdGain、观点（YFAds）、天目、XYZ、领业
- 广告网络请求基本消失（SDK init + 配置请求都被拦在源头）

## 原理

App 自带广告中台 `com.zjwh.android_wh_physicalfitness.advertise`：

| 类 | 作用 |
|---|---|
| `AdvertiseControl` | 6 个 `canLoad*` 开关 + `requestAdConfig` 配置请求 |
| `AdManager` | `init` / `checkCanLoad` / `isSlotAllowed` / `loadSplash` / `loadInteraction` / `loadRunHistoryAd` |
| `AdPosition` | 12 个广告位（开屏、热开屏、首页原生、插屏×4、跑步记录、游戏视频、天幕悬挂、悦动 banner…） |
| `AdPlatformType` | ADGAIN / BEIZI / GUANGDIAN / HONGMENG_JINGHONG / LINGYE / MEISHU / TIANMU / XYZ |

v17 只挂 8 + 4 + 1 个门控，全部位于冷启动阶段、调用频率极低，**没有全局热路径 hook**（v13 及以前的 DNS 黑洞、`View.setVisibility`、`addView`、`Dialog.show` 已全部移除）：

1. `AdvertiseControl.canLoadColdSplash / canLoadHotSplash / canLoadHostHome / canLoadInformationHome / canLoadMine / canLoadReverseHome` → `false`
2. `AdvertiseControl.requestAdConfig` / `AdManager.requestAdConfig` → 直接返回（不请求广告配置）
3. `AdManager.init(Context)` → 直接返回（广告 Provider 不初始化）
4. `AdManager.checkCanLoad` / `isSlotAllowed` → `false`
5. `AdManager.loadSplash` / `loadInteraction` / `loadRunHistoryAd` → 直接返回
6. Provider（AdGain / Guandian / TianMu / Xyz / LingYe）的 `init()` → no-op 兜底
7. `SplashActivity.loadSplashAd` → 拦截，并立即 `startJump`

Hook 在 `ContextWrapper.attachBaseContext` 阶段装好（早于 Application.onCreate），因此广告 SDK 连第一次初始化机会都没有。

## 版本演进

| 版本 | 策略 | 首屏 TotalTime | APK |
|---|---|---|---|
| v13 | 全局 DNS + 隐藏 View + addView 拦截（兜底层，代价高） | 偏慢（曾出现 30 s 卡顿） | 41 KB |
| v15 | 关闭广告中台开关（canLoad*） | 1847 / 1797 / 2041 ms | 95 KB |
| v16 | + 阻止 AdManager.init 与各 Provider.init | 1699 / 1659 / 1721 ms | 95 KB |
| **v17** | + 移除全部热路径 hook、开屏 500 ms→0 ms、去原生库 | **1602 / 1564 / 1582 / 1584 ms** | **53 KB** |

## 安装

1. 手机安装并激活 **LSPosed**（Zygisk 版），Android 8.0+
2. 安装 `apk/CampusRunMod-v17.apk`
3. 打开 LSPosed → 模块 → 启用「运动世界校园去广告」→ 作用域勾选「运动世界校园」
4. **每次更新模块后必须强制停止目标 App**，否则旧代码仍在运行
5. 打开 App 验证：开屏无广告、首页无广告位

不使用 LSPosed 的备用方案：`rules/` 下提供 Clash / AdGuard / hosts 规则（见 [docs/01-部署与使用说明.md](docs/01-部署与使用说明.md)）。规则只拦域名，无法清掉 App 自绘的原生广告位，效果弱于模块。

## 构建

需要 JDK 8+、Android SDK platform android-35、build-tools 37.0.0：

```bash
cd module
bash build.sh   # 产物 build/module-probe.apk
```

详见 [docs/构建说明.md](docs/构建说明.md)。已发布 APK 与源码构建产物 SHA256 一致。

## 目录

```
apk/                 已构建模块（v17）与 SHA256
module/              模块源码（Java + 资源 + build.sh + api-82.jar）
docs/                逆向分析报告、部署说明、构建说明
docs/recon/          运行时反射恢复的广告类/方法签名、App 类名子集
rules/               Clash / AdGuard / hosts 规则
```

## 关于「App 源码」

易盾加固把业务代码抽取到运行时，静态反编译只能看到 30 个壳类。本项目通过**进程内原生 dump + raw syscall 读自身内存**还原广告中台类结构（`module/src/io/dsh/campusrun/DexDumper.java`、`docs/recon/`），这也是 `AdvertiseControl` 等类名能够被 hook 的前提。

## 免责声明

本项目仅供个人学习与安全研究，**请勿用于商业用途或二次分发**。使用本模块可能违反目标应用的用户协议，产生的一切后果由使用者自行承担。请支持正版，尊重开发者。
