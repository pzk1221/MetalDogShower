# 金属狗洗浴（MetalDogShower）

面向 Android 手机与 Wear OS 手表的第三方淋浴控制客户端。当前版本为 **v0.7.1（versionCode 9）**，支持两间浴室、手机与手表共享登录状态、桌面小组件、状态轮询和定时刷新。

> 本项目不是金属狗或相关服务商的官方产品，也未获得其认可或授权。仅供用户控制本人账号下、本人有权使用的浴室设备。请勿用于他人账号、他人设备、批量控制、扫描、压测或绕过任何身份验证与访问控制。

## 下载

- [夸克网盘：手机端与 Wear OS 手表端 APK](https://pan.quark.cn/s/522b755e9899)

下载后请核对版本号与文件来源。GitHub 仓库不提交 APK、登录凭据、二维码数据或签名文件，所有安装包均由源码单独构建。

## 主要功能

- 配置“浴室 1 / 浴室 2”两条独立设备路由，防止控制错房间。
- 通过官方短信验证码登录，并使用 Android Keystore 加密保存本地会话。
- 手机端查询状态、开启和关闭浴室。
- Wear OS 手表端可独立查询与控制，不要求手机应用始终处于前台。
- 手机向手表安全同步浴室配置与登录状态，两端分别用各自的 Keystore 密钥保存。
- 提供多尺寸桌面小组件，可切换浴室、刷新状态并快速控制。
- 自动适配浅色与深色模式，手机端带新手指引、启动动画和底部 Dock。
- 支持前台轮询，以及每天或仅一次的后台定时刷新窗口。

## 界面设计

手机端使用 Jetpack Compose 实现。底部 Dock 通过半透明渐变、圆角、边缘高光、柔和阴影和动画选中块形成轻量玻璃效果，没有使用高开销的实时背景模糊。深浅色文字均读取 Material 主题颜色，避免深色模式下出现黑字不可见的问题。

手表端针对圆形 Wear OS 屏幕设计，使用纯黑背景降低 OLED 功耗，并尽量减少不必要的重组与动画。

## 项目结构

- `core`：协议模型、网络访问、状态读取、控制保护和 Fake/Real Repository。
- `mobile`：手机界面、扫码录入、短信登录、会话存储、手表同步、轮询和桌面小组件。
- `wear`：Wear OS 界面、独立控制、加密配置存储和 Tile。

Wear 模块包含两种后端变体：

- `fake`：无真实网络副作用，用于开发与测试。
- `real`：连接真实服务；没有有效本地凭据时不会发送受保护请求。

## 构建环境

- JDK 17
- Android Gradle Plugin 9.3.1
- Gradle 9.5.0
- Android SDK Platform 37 / Build Tools 37.0.0
- Android 手机最低版本：API 25
- Wear OS 最低版本：API 30

配置好 Android SDK 后运行：

```bash
./gradlew \
  :core:testDebugUnitTest \
  :mobile:testDebugUnitTest \
  :wear:testRealDebugUnitTest \
  :mobile:lintOptimized \
  :wear:lintRealOptimized \
  :mobile:assembleOptimized \
  :wear:assembleRealOptimized
```

输出位置：

```text
mobile/build/outputs/apk/optimized/mobile-optimized.apk
wear/build/outputs/apk/real/optimized/wear-real-optimized.apk
```

开发与 UI 验证应优先使用 Fake 变体。真实控制只应由账号本人在设备现场主动执行。

## 使用方法

1. 在手机端分别选择“浴室 1”和“浴室 2”，扫描或粘贴各自的二维码链接并保存。
2. 使用本人手机号获取并输入官方短信验证码。
3. 登录后在首页选择具体浴室，先刷新并确认状态，再执行开启或关闭。
4. 打开设置页，确认手机与手表已连接，然后将配置安全同步到手表。
5. 如需桌面快捷控制，长按手机桌面并添加“金属狗洗浴”小组件。

每次控制前都会重新读取所选浴室的状态。若请求结果无法确认，应用不会自动重复发送控制请求，需要用户现场确认后手动刷新。

## 安全与隐私

- 不在源码中保存手机号、验证码、token、Cookie、设备二维码或真实浴室标识。
- 手机与手表的凭据分别使用 Android Keystore AES-256/GCM 加密。
- 手机到手表的同步使用一次性挑战、临时 AES 密钥和 RSA-OAEP 封装。
- 控制请求不自动重试，并带有互斥、去抖和冷却保护。
- Release 构建不记录请求体、响应体或敏感请求头。
- Git 忽略 APK、签名文件、构建缓存、本地配置与抓包文件。

更详细的设计说明：

- [认证流程](AUTH_FLOW.md)
- [协议说明](API_DOCUMENTATION.md)
- [网络限制策略](NETWORK_LIMIT_POLICY.md)
- [研究依据](RESEARCH_EVIDENCE.md)
- [安全设计](SECURITY.md)
- [签名说明](SIGNING.md)
- [测试报告](TEST_REPORT.md)
- [技术限制](TECHNICAL_LIMITATION.md)

## 免责声明

本项目包含对公开客户端行为的兼容性实现，相关商标、服务名称与接口归其权利人所有。项目不提供官方服务、会员资格或设备访问权限，也不保证第三方接口长期可用。使用者应自行确认其行为符合当地法律、服务条款和场馆规则，并自行承担使用风险。

## 许可证

源码采用 [MIT License](LICENSE) 发布。许可证不授予任何第三方商标、品牌或服务接口的权利。
